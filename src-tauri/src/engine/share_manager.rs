//! 统一分享管理器（规划 §19 / §60）：三种模式共用同一份会话快照、
//! 同一套停止语义，并支持“传完再停”（DRAINING，规划 §25）。

use crate::engine::code_lease::CodeLeaseManager;
use crate::engine::code_share::CodeRegistry;
use crate::engine::diagnostics::Diagnostics;
use crate::engine::resource::{ShareKind, ShareResource, ShareState};
use crate::engine::transfer::prepare_manifest;
use crate::engine::web_share::WebShareServer;
use anyhow::{bail, Result};
use serde::Serialize;
use std::sync::Arc;
use std::time::Duration;
use tauri::{AppHandle, Emitter};

pub struct ShareManager {
    shares: CodeRegistry,
    code_leases: Arc<CodeLeaseManager>,
    web_shares: Arc<WebShareServer>,
    diagnostics: Arc<Diagnostics>,
    app: AppHandle,
}

/// 分享内容明细：单个文件或文件夹/多来源里的每一项。
#[derive(Clone, Serialize)]
pub struct ShareFileView {
    pub relative: String,
    pub size: u64,
}

impl ShareManager {
    pub fn new(
        shares: CodeRegistry,
        code_leases: Arc<CodeLeaseManager>,
        web_shares: Arc<WebShareServer>,
        diagnostics: Arc<Diagnostics>,
        app: AppHandle,
    ) -> Self {
        Self {
            shares,
            code_leases,
            web_shares,
            diagnostics,
            app,
        }
    }

    /// 三种模式的统一会话快照。
    pub async fn sessions(&self) -> Vec<ShareResource> {
        let mut list = Vec::new();
        for item in self.shares.read().await.values() {
            let kind = if item.code.starts_with("direct:") {
                ShareKind::Direct
            } else {
                ShareKind::Code
            };
            list.push(ShareResource {
                resource_id: item.code.clone(),
                share_id: item.share_id.clone(),
                kind,
                state: ShareState::of(
                    item.is_stopped(),
                    item.is_draining(),
                    item.is_expired(),
                    item.active_transfers(),
                ),
                name: item.file_name.clone(),
                size: item.file_size,
                is_folder: item.is_folder,
                remaining_seconds: item.remaining_seconds(),
                active_transfers: item.active_transfers(),
            });
        }
        for summary in self.web_shares.sessions().await {
            let (stopped, draining, downloads) = self
                .web_shares
                .state_of(&summary.token)
                .await
                .unwrap_or((false, false, 0));
            list.push(ShareResource {
                resource_id: summary.token.clone(),
                share_id: summary.share_id.clone(),
                kind: ShareKind::Web,
                state: ShareState::of(stopped, draining, summary.expires_in == Some(0), downloads),
                name: summary.file_name.clone(),
                size: summary.file_size,
                is_folder: false,
                remaining_seconds: summary.expires_in,
                active_transfers: downloads,
            });
        }
        list.sort_by(|left, right| {
            left.kind
                .label()
                .cmp(right.kind.label())
                .then_with(|| left.name.cmp(&right.name))
        });
        list
    }

    /// 读取某个四位码分享的内容清单（用于发送页展开详情）。
    pub async fn share_files(&self, resource_id: &str) -> Result<Vec<ShareFileView>> {
        let item = self
            .shares
            .read()
            .await
            .values()
            .find(|item| item.code == resource_id)
            .cloned()
            .ok_or_else(|| anyhow::anyhow!("分享不存在或已结束"))?;
        let cached = item
            .manifest
            .get_or_init(|| prepare_manifest(item.sources.clone(), item.is_folder))
            .await;
        let manifest = cached
            .as_ref()
            .map_err(|error| anyhow::anyhow!(error.clone()))?;
        let mut files = Vec::new();
        for (relative, size) in manifest.file_summaries(500) {
            let relative = if relative.is_empty() {
                item.file_name.clone()
            } else {
                relative
            };
            files.push(ShareFileView { relative, size });
        }
        Ok(files)
    }

    /// 统一停止入口。`drain = true` 表示“传完再停”：立刻拒绝新连接，
    /// 等当前传输结束后由维护循环收尾。
    pub async fn stop(&self, resource_id: &str, drain: bool) -> Result<()> {
        let code_item = self
            .shares
            .read()
            .await
            .values()
            .find(|item| item.code == resource_id)
            .cloned();
        if let Some(item) = code_item {
            if drain {
                item.drain();
                self.diagnostics.record(
                    "SESSION",
                    format!(
                        "{} 进入传完再停（当前 {} 条传输）",
                        resource_id,
                        item.active_transfers()
                    ),
                );
                return Ok(());
            }
            return self
                .code_leases
                .stop_share(&item.code, &item.share_id)
                .await;
        }
        let web_item = self
            .web_shares
            .sessions()
            .await
            .into_iter()
            .find(|summary| summary.token == resource_id);
        if let Some(summary) = web_item {
            if drain {
                self.web_shares
                    .drain(&summary.token, &summary.share_id)
                    .await?;
                self.diagnostics
                    .record("SESSION", "浏览器分享进入传完再停".to_string());
                return Ok(());
            }
            return self
                .web_shares
                .stop(&summary.token, &summary.share_id)
                .await;
        }
        bail!("分享不存在或已结束")
    }

    pub async fn stop_all(&self, drain: bool) -> Result<usize> {
        let sessions = self.sessions().await;
        let mut stopped = 0usize;
        for session in sessions {
            if self.stop(&session.resource_id, drain).await.is_ok() {
                stopped += 1;
            }
        }
        self.diagnostics.record(
            "SESSION",
            format!(
                "{} 全部会话，共 {stopped} 个",
                if drain {
                    "传完再停"
                } else {
                    "立即停止"
                }
            ),
        );
        Ok(stopped)
    }

    /// 维护循环：把“传完再停”且已经没有传输的会话真正撤销。
    pub async fn maintain(self: Arc<Self>) {
        loop {
            tokio::time::sleep(Duration::from_secs(1)).await;
            let drained: Vec<(ShareKind, String, String)> = self
                .sessions()
                .await
                .into_iter()
                .filter(ShareResource::stops_when_drained)
                .map(|resource| (resource.kind, resource.resource_id, resource.share_id))
                .collect();
            for (kind, resource_id, share_id) in drained {
                let result = match kind {
                    ShareKind::Web => self.web_shares.stop(&resource_id, &share_id).await,
                    ShareKind::Code | ShareKind::Direct => {
                        self.code_leases.stop_share(&resource_id, &share_id).await
                    }
                };
                if result.is_ok() {
                    self.diagnostics
                        .record("SESSION", format!("{resource_id} 已传完并停止"));
                    // 通知界面：这个分享已经被后端收尾，前端要把分享卡片撤掉。
                    let _ = self.app.emit(
                        "share-ended",
                        serde_json::json!({ "id": resource_id, "reason": "drained" }),
                    );
                }
            }
        }
    }
}
