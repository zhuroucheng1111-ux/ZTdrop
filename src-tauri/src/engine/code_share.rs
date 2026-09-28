use crate::engine::transfer::{prepare_manifest, Manifest};
use anyhow::{bail, Context, Result};
#[cfg(test)]
use rand::Rng;
use serde::Serialize;
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::{watch, OnceCell, RwLock};

#[derive(Clone, Serialize)]
pub struct CodeShareItem {
    pub code: String,
    pub share_id: String,
    pub claim_id: String,
    pub file_name: String,
    pub file_size: u64,
    pub is_folder: bool,
    pub token: String,
    pub expire_seconds: u64,
    #[serde(skip)]
    pub created_at: Option<Instant>,
    #[serde(skip)]
    pub sources: Vec<PathBuf>,
    #[serde(skip)]
    pub(crate) manifest: Arc<OnceCell<std::result::Result<Arc<Manifest>, String>>>,
    #[serde(skip)]
    pub(crate) stop_tx: watch::Sender<bool>,
    /// 传完再停：置位后不再接受新连接，但允许正在传输的连接跑完。
    #[serde(skip)]
    pub(crate) draining: Arc<AtomicBool>,
    /// 当前正在使用该令牌传输的连接数（统一会话视图与 DRAINING 判定使用）。
    #[serde(skip)]
    pub(crate) transfers: Arc<AtomicUsize>,
}

/// 传输计数守卫：连接结束（含出错）时自动减一。
pub struct TransferGuard {
    counter: Arc<AtomicUsize>,
}

impl Drop for TransferGuard {
    fn drop(&mut self) {
        self.counter.fetch_sub(1, Ordering::Relaxed);
    }
}

impl CodeShareItem {
    pub fn is_active(&self) -> bool {
        !self.is_stopped() && !self.is_draining() && !self.is_expired()
    }

    pub fn is_stopped(&self) -> bool {
        *self.stop_tx.borrow()
    }

    pub fn is_draining(&self) -> bool {
        self.draining.load(Ordering::Relaxed)
    }

    pub fn is_expired(&self) -> bool {
        !self
            .created_at
            .is_some_and(|created| created.elapsed() < Duration::from_secs(self.expire_seconds))
    }

    pub fn active_transfers(&self) -> usize {
        self.transfers.load(Ordering::Relaxed)
    }

    pub fn remaining_seconds(&self) -> Option<u64> {
        if self.expire_seconds == u64::MAX {
            return None;
        }
        self.created_at.map(|created| {
            self.expire_seconds
                .saturating_sub(created.elapsed().as_secs())
        })
    }

    /// 传完再停：停止接受新连接，当前连接继续。
    pub fn drain(&self) {
        self.draining.store(true, Ordering::Relaxed);
    }

    /// 是否还能接受新连接。
    pub fn accepts_new_transfer(&self) -> bool {
        self.is_resumable()
    }

    /// 进入一次传输；不满足条件时返回 `None`（调用方回绝该连接）。
    pub fn begin_transfer(&self) -> Option<TransferGuard> {
        if !self.accepts_new_transfer() {
            return None;
        }
        self.transfers.fetch_add(1, Ordering::Relaxed);
        Some(TransferGuard {
            counter: self.transfers.clone(),
        })
    }

    pub fn is_resumable(&self) -> bool {
        !self.is_stopped()
            && !self.is_draining()
            && (self.code.starts_with("direct:")
                || self
                    .created_at
                    .is_some_and(|created| created.elapsed() < Duration::from_secs(24 * 60 * 60)))
    }

    pub fn stop(&self) {
        self.stop_tx.send_replace(true);
    }
}

pub type CodeRegistry = Arc<RwLock<HashMap<String, CodeShareItem>>>;

/// 根据来源路径生成分享展示信息：名称、大小、是否按文件夹容器处理。
/// 单个来源沿用原名与类型；多个来源（多文件 / 混合）统一按容器文件夹处理。
pub async fn describe_sources(sources: &[PathBuf]) -> Result<(String, u64, bool)> {
    if sources.is_empty() {
        bail!("请选择要分享的文件或文件夹");
    }
    if sources.len() == 1 {
        let source = &sources[0];
        let metadata = tokio::fs::metadata(source)
            .await
            .context("选定内容不存在")?;
        let name = source
            .file_name()
            .map(|name| name.to_string_lossy().to_string())
            .context("无法识别文件名")?;
        let is_folder = metadata.is_dir();
        let size = if is_folder { 0 } else { metadata.len() };
        return Ok((name, size, is_folder));
    }
    let first = sources[0]
        .file_name()
        .map(|name| name.to_string_lossy().to_string())
        .context("无法识别文件名")?;
    let mut size = 0u64;
    for source in sources {
        let metadata = tokio::fs::metadata(source)
            .await
            .context("选定内容不存在")?;
        if metadata.is_file() {
            size = size.saturating_add(metadata.len());
        }
    }
    Ok((format!("{first} 等 {} 项", sources.len()), size, true))
}

#[cfg(test)]
pub async fn register_new_code(
    registry: CodeRegistry,
    file_name: String,
    file_size: u64,
    is_folder: bool,
    sources: Vec<PathBuf>,
) -> Result<CodeShareItem> {
    let code = {
        let mut map = registry.write().await;
        map.retain(|_, item| item.is_resumable());
        if map.len() == 10_000 {
            bail!("当前分享码已用尽，请等待已有分享过期");
        }
        let mut rng = rand::thread_rng();
        loop {
            let candidate = format!("{:04}", rng.gen_range(0..10_000));
            if !map.contains_key(&candidate) {
                break candidate;
            }
        }
    };
    register_claimed_code(
        registry,
        code,
        format!("{:032x}", rand::random::<u128>()),
        format!("{:032x}", rand::random::<u128>()),
        file_name,
        file_size,
        is_folder,
        sources,
    )
    .await
}

pub async fn register_claimed_code(
    registry: CodeRegistry,
    code: String,
    share_id: String,
    claim_id: String,
    file_name: String,
    file_size: u64,
    is_folder: bool,
    sources: Vec<PathBuf>,
) -> Result<CodeShareItem> {
    let mut map = registry.write().await;
    map.retain(|_, item| item.is_resumable());
    if map.contains_key(&code) {
        bail!("该分享码在本机仍被使用");
    }
    let manifest = Arc::new(OnceCell::new());
    let scan_sources = sources.clone();
    let scan_cache = manifest.clone();
    tokio::spawn(async move {
        let _ = scan_cache
            .get_or_init(|| prepare_manifest(scan_sources, is_folder))
            .await;
    });
    let (stop_tx, _) = watch::channel(false);
    let item = CodeShareItem {
        code: code.clone(),
        share_id,
        claim_id,
        file_name,
        file_size,
        is_folder,
        token: format!("{:032x}", rand::random::<u128>()),
        expire_seconds: 900,
        created_at: Some(Instant::now()),
        sources,
        manifest,
        stop_tx,
        draining: Arc::new(AtomicBool::new(false)),
        transfers: Arc::new(AtomicUsize::new(0)),
    };
    map.insert(code, item.clone());
    Ok(item)
}
