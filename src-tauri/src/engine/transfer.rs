use crate::engine::code_share::CodeRegistry;
use crate::engine::network::NetworkPolicy;
use anyhow::{bail, Context, Result};
use serde::{Deserialize, Serialize};
use std::net::SocketAddr;
use std::path::{Component, Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, Instant, UNIX_EPOCH};
use tauri::{AppHandle, Emitter};
use tokio::fs::{File, OpenOptions};
use tokio::io::{AsyncReadExt, AsyncSeekExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::time::timeout;

const CHUNK_SIZE: usize = 256 * 1024;
const MAX_MANIFEST_BYTES: usize = 32 * 1024 * 1024;
const MAX_FILES: usize = 100_000;
const PROTOCOL_MAGIC: &[u8; 4] = b"ZTB4";

#[derive(Clone, Serialize, Deserialize)]
struct FileEntry {
    relative: String,
    size: u64,
    modified_ms: u64,
    /// 仅发送端使用；不把本机绝对路径写进传输清单。
    #[serde(skip)]
    source_path: Option<PathBuf>,
}

#[derive(Clone, Serialize, Deserialize)]
pub(crate) struct Manifest {
    is_folder: bool,
    directories: Vec<String>,
    files: Vec<FileEntry>,
}

impl Manifest {
    /// 供会话详情展示：文件名与大小的只读列表。
    pub(crate) fn file_summaries(&self, limit: usize) -> Vec<(String, u64)> {
        self.files
            .iter()
            .take(limit)
            .map(|entry| (entry.relative.clone(), entry.size))
            .collect()
    }
}

#[derive(Clone, serde::Serialize)]
pub struct ProgressPayload {
    pub task_id: String,
    pub token: String,
    pub file_name: String,
    pub transferred: u64,
    pub total: u64,
    pub speed_mb: f64,
    pub status: String,
    pub error_msg: Option<String>,
}

#[derive(Clone, Serialize)]
pub struct SenderProgressPayload {
    pub transfer_id: String,
    pub share_id: String,
    pub token: String,
    pub peer_name: String,
    pub peer_ip: String,
    pub file_name: String,
    pub transferred: u64,
    pub total: u64,
    pub speed_mb: f64,
    pub status: String,
    pub error_msg: Option<String>,
}

fn modified_ms(metadata: &std::fs::Metadata) -> Result<u64> {
    Ok(metadata
        .modified()?
        .duration_since(UNIX_EPOCH)?
        .as_millis()
        .try_into()
        .context("文件修改时间超出支持范围")?)
}

fn scan_manifest(root: &Path, is_folder: bool) -> Result<Manifest> {
    if !is_folder {
        let metadata = std::fs::metadata(root)?;
        if !metadata.is_file() {
            bail!("原文件已不可用");
        }
        return Ok(Manifest {
            is_folder: false,
            directories: Vec::new(),
            files: vec![FileEntry {
                relative: String::new(),
                size: metadata.len(),
                modified_ms: modified_ms(&metadata)?,
                source_path: Some(root.to_path_buf()),
            }],
        });
    }

    let mut manifest = Manifest {
        is_folder: true,
        directories: Vec::new(),
        files: Vec::new(),
    };
    let mut pending = vec![root.to_path_buf()];
    while let Some(directory) = pending.pop() {
        for entry in std::fs::read_dir(&directory)? {
            let entry = entry?;
            let path = entry.path();
            let metadata = std::fs::symlink_metadata(&path)?;
            if metadata.file_type().is_symlink() {
                continue;
            }
            let relative = path
                .strip_prefix(root)?
                .to_str()
                .context("文件夹包含无法表示的文件名")?
                .replace('\\', "/");
            if metadata.is_dir() {
                manifest.directories.push(relative);
                if manifest.directories.len() > MAX_FILES {
                    bail!("文件夹超过 10 万个子目录，请分批分享");
                }
                pending.push(path);
            } else if metadata.is_file() {
                manifest.files.push(FileEntry {
                    relative,
                    size: metadata.len(),
                    modified_ms: modified_ms(&metadata)?,
                    source_path: Some(path),
                });
                if manifest.files.len() > MAX_FILES {
                    bail!("文件夹超过 10 万个文件，请分批分享");
                }
            }
        }
    }
    manifest.directories.sort();
    manifest.files.sort_by(|a, b| a.relative.cmp(&b.relative));
    Ok(manifest)
}

/// 多源清单：把若干「拖进来的文件 / 文件夹」合并成一份清单。
/// 单个来源保持原行为；多个来源时文件名前缀各自的目录名，冲突名自动加序号。
pub(crate) fn scan_manifest_multi(sources: &[PathBuf]) -> Result<Manifest> {
    if sources.len() == 1 {
        let is_folder = std::fs::metadata(&sources[0])?.is_dir();
        return scan_manifest(&sources[0], is_folder);
    }

    let mut manifest = Manifest {
        is_folder: true,
        directories: Vec::new(),
        files: Vec::new(),
    };
    let mut used: std::collections::HashSet<String> = std::collections::HashSet::new();
    for source in sources {
        let metadata = std::fs::metadata(source)?;
        if metadata.is_file() {
            let name = source
                .file_name()
                .and_then(|name| name.to_str())
                .context("文件名无效")?
                .to_string();
            push_entry(
                &mut manifest,
                &mut used,
                name,
                metadata.len(),
                modified_ms(&metadata)?,
                source.clone(),
            )?;
        } else if metadata.is_dir() {
            let prefix = source
                .file_name()
                .and_then(|name| name.to_str())
                .context("文件夹名无效")?
                .to_string();
            let mut pending = vec![source.clone()];
            while let Some(directory) = pending.pop() {
                for entry in std::fs::read_dir(&directory)? {
                    let entry = entry?;
                    let path = entry.path();
                    let metadata = std::fs::symlink_metadata(&path)?;
                    if metadata.file_type().is_symlink() {
                        continue;
                    }
                    let relative = path
                        .strip_prefix(source)?
                        .to_str()
                        .context("文件夹包含无法表示的文件名")?
                        .replace('\\', "/");
                    let relative = format!("{prefix}/{relative}");
                    if metadata.is_dir() {
                        manifest.directories.push(relative);
                        if manifest.directories.len() > MAX_FILES {
                            bail!("文件夹超过 10 万个子目录，请分批分享");
                        }
                        pending.push(path);
                    } else if metadata.is_file() {
                        push_entry(
                            &mut manifest,
                            &mut used,
                            relative,
                            metadata.len(),
                            modified_ms(&metadata)?,
                            path,
                        )?;
                    }
                }
            }
        } else {
            bail!("仅支持文件与文件夹");
        }
    }
    manifest.directories.sort();
    manifest.files.sort_by(|a, b| a.relative.cmp(&b.relative));
    if manifest.files.is_empty() {
        bail!("所选内容里没有可发送的文件");
    }
    Ok(manifest)
}

/// 追加文件条目；重名时在扩展名前加「 (n)」，避免接收端互相覆盖。
fn push_entry(
    manifest: &mut Manifest,
    used: &mut std::collections::HashSet<String>,
    relative: String,
    size: u64,
    modified: u64,
    source_path: PathBuf,
) -> Result<()> {
    let mut candidate = relative;
    if !used.insert(candidate.to_lowercase()) {
        let (stem, extension) = match candidate.rsplit_once('.') {
            Some((stem, extension)) if !stem.is_empty() => {
                (stem.to_string(), format!(".{extension}"))
            }
            _ => (candidate.clone(), String::new()),
        };
        let mut index = 2;
        loop {
            let next = format!("{stem} ({index}){extension}");
            if used.insert(next.to_lowercase()) {
                candidate = next;
                break;
            }
            index += 1;
        }
    }
    manifest.files.push(FileEntry {
        relative: candidate,
        size,
        modified_ms: modified,
        source_path: Some(source_path),
    });
    if manifest.files.len() > MAX_FILES {
        bail!("内容超过 10 万个文件，请分批分享");
    }
    Ok(())
}

pub(crate) async fn prepare_manifest(
    sources: Vec<PathBuf>,
    _is_folder: bool,
) -> std::result::Result<std::sync::Arc<Manifest>, String> {
    tokio::task::spawn_blocking(move || scan_manifest_multi(&sources))
        .await
        .map_err(|err| err.to_string())?
        .map(std::sync::Arc::new)
        .map_err(|err| format!("扫描分享内容失败: {err:#}"))
}

fn safe_relative(relative: &str) -> Result<&Path> {
    let path = Path::new(relative);
    if relative.is_empty()
        || relative.chars().any(|ch| matches!(ch, '\\' | ':' | '\0'))
        || relative.split('/').any(|part| {
            part.is_empty()
                || part == "."
                || part == ".."
                || part.ends_with(' ')
                || part.ends_with('.')
        })
        || !path
            .components()
            .all(|part| matches!(part, Component::Normal(_)))
    {
        bail!("发送端包含不安全的相对路径");
    }
    Ok(path)
}

fn partial_path(target: &Path, entry: &FileEntry) -> Result<PathBuf> {
    // The stable name is based on metadata only; file contents are never hashed.
    let identity = format!("{}|{}|{}", target.display(), entry.size, entry.modified_ms);
    let mut key = 0xcbf29ce484222325u64;
    for byte in identity.as_bytes() {
        key = (key ^ u64::from(*byte)).wrapping_mul(0x100000001b3);
    }
    Ok(target.with_file_name(format!(".ztdrop-{key:016x}.part")))
}

pub async fn serve_connection<F>(
    mut stream: TcpStream,
    shares: CodeRegistry,
    peer_name: String,
    peer_ip: String,
    mut report: F,
) -> Result<()>
where
    F: FnMut(SenderProgressPayload) + Send,
{
    let mut magic = [0u8; 4];
    timeout(Duration::from_secs(5), stream.read_exact(&mut magic))
        .await
        .context("等待协议头超时")??;
    if &magic != PROTOCOL_MAGIC {
        bail!("客户端版本不兼容");
    }
    let mut token = [0u8; 32];
    timeout(Duration::from_secs(5), stream.read_exact(&mut token))
        .await
        .context("等待分享令牌超时")??;
    let token = std::str::from_utf8(&token)?;
    let item = shares
        .read()
        .await
        .values()
        .find(|item| item.token == token && item.is_resumable())
        .cloned();
    let Some(item) = item else {
        stream.write_all(&[0]).await?;
        bail!("分享令牌无效或已过期");
    };

    let mut stopped = item.stop_tx.subscribe();
    if *stopped.borrow() {
        stream.write_all(&[0]).await?;
        bail!("分享已结束");
    }
    // 统一资源模型：进入一次传输计数；处于“传完再停”或已停止时拒绝新连接。
    let Some(_transfer_guard) = item.begin_transfer() else {
        stream.write_all(&[0]).await?;
        bail!("分享已停止接收新连接");
    };
    let transfer_id = format!("{:032x}", rand::random::<u128>());
    let mut transferred = 0u64;
    let mut total = 0u64;
    let result = {
        let mut on_progress = |current: u64, expected: u64, speed_mb: f64| {
            transferred = current;
            total = expected;
            report(SenderProgressPayload {
                transfer_id: transfer_id.clone(),
                share_id: item.share_id.clone(),
                token: item.token.clone(),
                peer_name: peer_name.clone(),
                peer_ip: peer_ip.clone(),
                file_name: item.file_name.clone(),
                transferred: current,
                total: expected,
                speed_mb,
                status: "sending".into(),
                error_msg: None,
            });
        };
        tokio::select! {
            result = serve_active_connection(&mut stream, &item, &mut on_progress) => result,
            _ = stopped.changed() => Err(anyhow::anyhow!("分享已结束，已中断发送")),
        }
    };
    let status = if result.is_ok() {
        "completed"
    } else if *stopped.borrow() {
        "stopped"
    } else {
        "error"
    };
    report(SenderProgressPayload {
        transfer_id,
        share_id: item.share_id.clone(),
        token: item.token.clone(),
        peer_name,
        peer_ip,
        file_name: item.file_name.clone(),
        transferred,
        total,
        speed_mb: 0.0,
        status: status.into(),
        error_msg: result.as_ref().err().map(|err| format!("{err:#}")),
    });
    result
}

async fn serve_active_connection(
    stream: &mut TcpStream,
    item: &crate::engine::code_share::CodeShareItem,
    report: &mut (dyn FnMut(u64, u64, f64) + Send),
) -> Result<()> {
    let cached = item
        .manifest
        .get_or_init(|| prepare_manifest(item.sources.clone(), item.is_folder))
        .await;
    let manifest = cached
        .as_ref()
        .map_err(|err| anyhow::anyhow!(err.clone()))?;
    let manifest_bytes = serde_json::to_vec(manifest.as_ref())?;
    if manifest_bytes.len() > MAX_MANIFEST_BYTES {
        stream.write_all(&[0]).await?;
        bail!("文件夹清单过大，请分批分享");
    }
    stream.write_all(&[1]).await?;
    stream
        .write_all(&(manifest_bytes.len() as u32).to_be_bytes())
        .await?;
    stream.write_all(&manifest_bytes).await?;

    let count = timeout(Duration::from_secs(60), stream.read_u32()).await?? as usize;
    if count > manifest.files.len() {
        bail!("请求文件数量无效");
    }
    let mut requests = vec![0u8; count * 12];
    timeout(Duration::from_secs(60), stream.read_exact(&mut requests)).await??;
    let mut seen = std::collections::HashSet::new();
    let mut total = 0u64;
    for request in requests.chunks_exact(12) {
        let index = u32::from_be_bytes(request[..4].try_into()?) as usize;
        let offset = u64::from_be_bytes(request[4..].try_into()?);
        if index >= manifest.files.len() || !seen.insert(index) {
            bail!("请求文件序号无效");
        }
        let entry = &manifest.files[index];
        if offset > entry.size {
            bail!("续传位置超出文件大小");
        }
        total = total
            .checked_add(entry.size - offset)
            .context("传输大小超出支持范围")?;
    }
    let mut transferred = 0u64;
    let started = Instant::now();
    let mut last_report = Instant::now();
    report(0, total, 0.0);
    for request in requests.chunks_exact(12) {
        let index = u32::from_be_bytes(request[..4].try_into()?) as usize;
        let offset = u64::from_be_bytes(request[4..].try_into()?);
        let entry = &manifest.files[index];
        let path = entry
            .source_path
            .as_ref()
            .context("分享内容已发生变化，找不到对应文件")?;
        let mut file = File::open(&path).await?;
        let metadata = file.metadata().await?;
        if !metadata.is_file()
            || metadata.len() != entry.size
            || modified_ms(&metadata)? != entry.modified_ms
        {
            stream.write_all(&[0]).await?;
            bail!("分享期间原文件已发生变化");
        }
        file.seek(std::io::SeekFrom::Start(offset)).await?;
        stream.write_all(&[1]).await?;
        let mut sent = offset;
        let mut buffer = vec![0u8; CHUNK_SIZE];
        while sent < entry.size {
            let remaining = (entry.size - sent).min(CHUNK_SIZE as u64) as usize;
            let read = file.read(&mut buffer[..remaining]).await?;
            if read == 0 {
                bail!("发送期间原文件被截断");
            }
            timeout(Duration::from_secs(30), stream.write_all(&buffer[..read]))
                .await
                .context("接收端长时间未读取数据")??;
            sent += read as u64;
            transferred += read as u64;
            if last_report.elapsed() >= Duration::from_millis(200) || transferred == total {
                let speed_mb = transferred as f64
                    / started.elapsed().as_secs_f64().max(0.001)
                    / 1024.0
                    / 1024.0;
                report(transferred, total, speed_mb);
                last_report = Instant::now();
            }
        }
    }
    stream.shutdown().await?;
    Ok(())
}

pub async fn download_stream(
    app: AppHandle,
    task_id: String,
    file_name: String,
    address: SocketAddr,
    token: String,
    target_path: PathBuf,
    declared_size: u64,
    is_folder: bool,
    overwrite: bool,
    network: Arc<NetworkPolicy>,
) -> Result<()> {
    let emit =
        |status: &str, transferred: u64, total: u64, speed_mb: f64, error_msg: Option<String>| {
            let _ = app.emit(
                "transfer-progress",
                ProgressPayload {
                    task_id: task_id.clone(),
                    token: token.clone(),
                    file_name: file_name.clone(),
                    transferred,
                    total,
                    speed_mb,
                    status: status.into(),
                    error_msg,
                },
            );
        };
    let mut last_total = declared_size;
    let mut last_progress = 0u64;
    for attempt in 0..3 {
        let result = receive_attempt(
            address,
            Some(&network),
            &token,
            &target_path,
            declared_size,
            is_folder,
            overwrite,
            |status, transferred, total, speed_mb| {
                last_total = total;
                last_progress = transferred;
                emit(status, transferred, total, speed_mb, None);
            },
        )
        .await;
        match result {
            Ok(()) => {
                emit("completed", last_total, last_total, 0.0, None);
                return Ok(());
            }
            Err(err) if attempt < 2 && last_progress > 0 => {
                emit("connecting", last_progress, last_total, 0.0, None);
                tokio::time::sleep(Duration::from_secs(attempt + 1)).await;
                let _ = err;
            }
            Err(err) => {
                emit(
                    "error",
                    last_progress,
                    last_total,
                    0.0,
                    Some(format!("{err:#}")),
                );
                return Err(err);
            }
        }
    }
    unreachable!()
}

struct FilePlan {
    index: usize,
    offset: u64,
    target: PathBuf,
    partial: PathBuf,
}

async fn receive_attempt<F>(
    address: SocketAddr,
    network: Option<&NetworkPolicy>,
    token: &str,
    target_root: &Path,
    declared_size: u64,
    is_folder: bool,
    overwrite: bool,
    mut report: F,
) -> Result<()>
where
    F: FnMut(&str, u64, u64, f64),
{
    if target_root.exists() && !overwrite {
        bail!("目标已存在，请先确认是否覆盖或继续接收");
    }
    report("connecting", 0, declared_size, 0.0);
    let mut stream = timeout(Duration::from_secs(5), async {
        match network {
            Some(policy) => policy.connect_tcp(address).await,
            None => Ok(TcpStream::connect(address).await?),
        }
    })
    .await
    .context("连接发送端超时")??;
    stream.set_nodelay(true)?;
    stream.write_all(PROTOCOL_MAGIC).await?;
    stream.write_all(token.as_bytes()).await?;
    let mut status = [0u8; 1];
    timeout(Duration::from_secs(60), stream.read_exact(&mut status))
        .await
        .context("等待发送端清单超时")??;
    if status[0] != 1 {
        bail!("发送端拒绝传输，分享可能已过期或原文件已变化");
    }
    let manifest_len = stream.read_u32().await? as usize;
    if manifest_len == 0 || manifest_len > MAX_MANIFEST_BYTES {
        bail!("发送端清单大小无效");
    }
    let mut bytes = vec![0u8; manifest_len];
    timeout(Duration::from_secs(60), stream.read_exact(&mut bytes))
        .await
        .context("读取发送端清单超时")??;
    let manifest: Manifest = serde_json::from_slice(&bytes)?;
    if manifest.is_folder != is_folder || manifest.files.len() > MAX_FILES {
        bail!("发送端清单与分享信息不一致");
    }
    if !is_folder && (manifest.files.len() != 1 || manifest.files[0].size != declared_size) {
        bail!("发送端文件大小与分享信息不一致");
    }
    let total = manifest.files.iter().try_fold(0u64, |sum, entry| {
        sum.checked_add(entry.size).context("文件夹总大小超出范围")
    })?;
    if is_folder {
        tokio::fs::create_dir_all(target_root).await?;
        for directory in &manifest.directories {
            tokio::fs::create_dir_all(target_root.join(safe_relative(directory)?)).await?;
        }
    }

    let mut progress = 0u64;
    let mut plan = Vec::new();
    for (index, entry) in manifest.files.iter().enumerate() {
        let target = if is_folder {
            target_root.join(safe_relative(&entry.relative)?)
        } else {
            target_root.to_path_buf()
        };
        if let Ok(metadata) = tokio::fs::metadata(&target).await {
            if !metadata.is_file() {
                bail!("目标路径已有同名文件夹，无法覆盖为文件");
            }
            if metadata.is_file()
                && metadata.len() == entry.size
                && modified_ms(&metadata).ok() == Some(entry.modified_ms)
            {
                progress += entry.size;
                continue;
            }
            if !overwrite {
                bail!("目标文件已存在，请先确认是否覆盖");
            }
        }
        let partial = partial_path(&target, entry)?;
        let offset = match tokio::fs::metadata(&partial).await {
            Ok(metadata) if metadata.is_file() => metadata.len(),
            Ok(_) => bail!("续传临时路径不是文件"),
            Err(err) if err.kind() == std::io::ErrorKind::NotFound => 0,
            Err(err) => return Err(err.into()),
        };
        if offset > entry.size {
            bail!("续传临时文件大于原文件，请手动处理该临时文件");
        }
        progress += offset;
        plan.push(FilePlan {
            index,
            offset,
            target,
            partial,
        });
    }
    report("transferring", progress, total, 0.0);
    let mut request = Vec::with_capacity(4 + plan.len() * 12);
    request.extend_from_slice(&(plan.len() as u32).to_be_bytes());
    for item in &plan {
        request.extend_from_slice(&(item.index as u32).to_be_bytes());
        request.extend_from_slice(&item.offset.to_be_bytes());
    }
    stream.write_all(&request).await?;

    let started = Instant::now();
    let initial_progress = progress;
    let mut last_report = Instant::now();
    for item in plan {
        let entry = &manifest.files[item.index];
        timeout(Duration::from_secs(30), stream.read_exact(&mut status))
            .await
            .context("等待发送端文件数据超时")??;
        if status[0] != 1 {
            bail!("发送端文件发生变化，请重新分享");
        }
        if let Some(parent) = item.target.parent() {
            tokio::fs::create_dir_all(parent).await?;
        }
        let mut output = OpenOptions::new()
            .append(true)
            .create(true)
            .open(&item.partial)
            .await
            .context("无法创建续传临时文件")?;
        if output.metadata().await?.len() != item.offset {
            bail!("续传临时文件在传输前发生变化");
        }
        let mut received = item.offset;
        let mut buffer = vec![0u8; CHUNK_SIZE];
        while received < entry.size {
            let remaining = (entry.size - received).min(CHUNK_SIZE as u64) as usize;
            let read = timeout(
                Duration::from_secs(30),
                stream.read(&mut buffer[..remaining]),
            )
            .await
            .context("等待发送数据超时")??;
            if read == 0 {
                bail!("连接中断，已保留临时文件供续传");
            }
            output.write_all(&buffer[..read]).await?;
            received += read as u64;
            progress += read as u64;
            if last_report.elapsed() >= Duration::from_millis(200) || progress == total {
                let speed_mb = (progress - initial_progress) as f64
                    / started.elapsed().as_secs_f64().max(0.001)
                    / 1024.0
                    / 1024.0;
                report("transferring", progress, total, speed_mb);
                last_report = Instant::now();
            }
        }
        output.flush().await?;
        drop(output);
        let backup = item
            .target
            .with_file_name(format!(".ztdrop-{:032x}.backup", rand::random::<u128>()));
        if item.target.exists() {
            if !overwrite {
                bail!("传输期间目标文件已出现，未覆盖原文件");
            }
            if item.target.is_dir() {
                bail!("目标路径已有同名文件夹，无法覆盖为文件");
            }
            tokio::fs::rename(&item.target, &backup)
                .await
                .context("无法备份原文件，未覆盖")?;
        }
        if let Err(err) = tokio::fs::rename(&item.partial, &item.target).await {
            if backup.exists() {
                let _ = tokio::fs::rename(&backup, &item.target).await;
            }
            return Err(err).context("保存接收文件失败");
        }
        let _ = std::fs::OpenOptions::new()
            .write(true)
            .open(&item.target)
            .and_then(|file| {
                file.set_modified(UNIX_EPOCH + Duration::from_millis(entry.modified_ms))
            });
        if backup.exists() {
            let _ = tokio::fs::remove_file(&backup).await;
        }
    }
    report("saving", total, total, 0.0);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::engine::code_share::register_new_code;
    use std::collections::HashMap;
    use std::sync::Arc;
    use tokio::net::TcpListener;
    use tokio::sync::RwLock;

    #[test]
    fn rejects_paths_that_escape_or_alias_a_windows_target() {
        assert!(safe_relative("nested/ok.txt").is_ok());
        for path in [
            "../escape",
            "C:/escape",
            "folder\\escape",
            "file:stream",
            "a//b",
            "a./b",
        ] {
            assert!(safe_relative(path).is_err(), "accepted {path}");
        }
    }

    async fn one_shot(
        shares: CodeRegistry,
    ) -> Result<(SocketAddr, tokio::task::JoinHandle<Result<()>>)> {
        let listener = TcpListener::bind("127.0.0.1:0").await?;
        let address = listener.local_addr()?;
        let task = tokio::spawn(async move {
            let (stream, _) = listener.accept().await?;
            serve_connection(stream, shares, "test".into(), "127.0.0.1".into(), |_| {}).await
        });
        Ok((address, task))
    }

    #[tokio::test]
    async fn stopping_share_interrupts_current_connection_and_rejects_token() -> Result<()> {
        let root =
            std::env::temp_dir().join(format!("ztdrop-stop-{:032x}", rand::random::<u128>()));
        tokio::fs::create_dir_all(&root).await?;
        let source = root.join("source.bin");
        tokio::fs::write(&source, vec![7u8; 1024 * 1024]).await?;
        let shares: CodeRegistry = Arc::new(RwLock::new(HashMap::new()));
        let item = register_new_code(
            shares.clone(),
            "source.bin".into(),
            1024 * 1024,
            false,
            vec![source],
        )
        .await?;

        let (address, sender) = one_shot(shares.clone()).await?;
        let mut stream = TcpStream::connect(address).await?;
        stream.write_all(PROTOCOL_MAGIC).await?;
        stream.write_all(item.token.as_bytes()).await?;
        assert_eq!(stream.read_u8().await?, 1);
        let manifest_len = stream.read_u32().await? as usize;
        let mut manifest = vec![0u8; manifest_len];
        stream.read_exact(&mut manifest).await?;
        item.stop();
        let stopped = tokio::time::timeout(Duration::from_secs(2), sender).await??;
        assert!(stopped.is_err());

        let (address, sender) = one_shot(shares).await?;
        let mut stream = TcpStream::connect(address).await?;
        stream.write_all(PROTOCOL_MAGIC).await?;
        stream.write_all(item.token.as_bytes()).await?;
        assert_eq!(stream.read_u8().await?, 0);
        assert!(sender.await?.is_err());
        tokio::fs::remove_dir_all(root).await?;
        Ok(())
    }

    #[tokio::test]
    async fn draining_share_finishes_current_transfer_and_refuses_new_connections() -> Result<()> {
        let root =
            std::env::temp_dir().join(format!("ztdrop-drain-{:032x}", rand::random::<u128>()));
        tokio::fs::create_dir_all(&root).await?;
        let source = root.join("payload.bin");
        let contents = vec![0x5au8; 1024 * 1024 + 3];
        tokio::fs::write(&source, &contents).await?;
        let shares: CodeRegistry = Arc::new(RwLock::new(HashMap::new()));
        let item = register_new_code(
            shares.clone(),
            "payload.bin".into(),
            contents.len() as u64,
            false,
            vec![source],
        )
        .await?;

        let listener = TcpListener::bind("127.0.0.1:0").await?;
        let address = listener.local_addr()?;
        let server = {
            let shares = shares.clone();
            tokio::spawn(async move {
                let mut sessions = Vec::new();
                for _ in 0..2 {
                    let (stream, _) = listener.accept().await?;
                    let shares = shares.clone();
                    sessions.push(tokio::spawn(async move {
                        let _ = serve_connection(
                            stream,
                            shares,
                            "test".into(),
                            "127.0.0.1".into(),
                            |_| {},
                        )
                        .await;
                    }));
                }
                for session in sessions {
                    let _ = session.await;
                }
                Ok::<(), anyhow::Error>(())
            })
        };

        // 第一个下载进入传输阶段（已读到清单）。
        let mut stream = TcpStream::connect(address).await?;
        stream.write_all(PROTOCOL_MAGIC).await?;
        stream.write_all(item.token.as_bytes()).await?;
        assert_eq!(stream.read_u8().await?, 1);
        let manifest_len = stream.read_u32().await? as usize;
        let mut manifest = vec![0u8; manifest_len];
        stream.read_exact(&mut manifest).await?;
        let mut request = Vec::new();
        request.extend_from_slice(&1u32.to_be_bytes());
        request.extend_from_slice(&0u32.to_be_bytes());
        request.extend_from_slice(&0u64.to_be_bytes());
        stream.write_all(&request).await?;

        // 传完再停：拒绝新连接，但当前这条要继续跑完。
        item.drain();
        assert_eq!(item.active_transfers(), 1, "在传的连接应计入传输数");
        let mut refused = TcpStream::connect(address).await?;
        refused.write_all(PROTOCOL_MAGIC).await?;
        refused.write_all(item.token.as_bytes()).await?;
        assert_eq!(refused.read_u8().await?, 0, "传完再停期间必须拒绝新连接");

        assert_eq!(stream.read_u8().await?, 1);
        let mut received = vec![0u8; contents.len()];
        stream.read_exact(&mut received).await?;
        assert_eq!(received, contents);
        server.await??;
        tokio::fs::remove_dir_all(root).await?;
        Ok(())
    }

    #[tokio::test]
    async fn serves_multiple_dropped_files_in_one_share() -> Result<()> {
        use crate::engine::code_share::describe_sources;
        let root =
            std::env::temp_dir().join(format!("ztdrop-multi-{:032x}", rand::random::<u128>()));
        tokio::fs::create_dir_all(root.join("资料")).await?;
        let first = root.join("01.第一章.mp4");
        let second = root.join("02.第二章.mp4");
        let nested = root.join("资料").join("说明.txt");
        tokio::fs::write(&first, b"first").await?;
        tokio::fs::write(&second, b"second").await?;
        tokio::fs::write(&nested, b"nested").await?;

        let sources = vec![first.clone(), second.clone(), root.join("资料")];
        let (name, size, is_folder) = describe_sources(&sources).await?;
        assert!(is_folder, "多来源必须按容器文件夹发送");
        // 只有散落文件能立刻统计大小，文件夹内容要等清单扫描，与单文件夹分享保持一致。
        assert_eq!(size, 5 + 6);
        assert!(name.contains("等 3 项"), "名称应体现项目数：{name}");

        let shares: CodeRegistry = Arc::new(RwLock::new(HashMap::new()));
        let item = register_new_code(shares.clone(), name, size, is_folder, sources).await?;
        let (address, sender) = one_shot(shares).await?;
        let target = root.join("收到的内容");
        receive_attempt(
            address,
            None,
            &item.token,
            &target,
            0,
            true,
            false,
            |_, _, _, _| {},
        )
        .await?;
        sender.await??;

        assert_eq!(
            tokio::fs::read(target.join("01.第一章.mp4")).await?,
            b"first"
        );
        assert_eq!(
            tokio::fs::read(target.join("02.第二章.mp4")).await?,
            b"second"
        );
        assert_eq!(
            tokio::fs::read(target.join("资料").join("说明.txt")).await?,
            b"nested"
        );
        tokio::fs::remove_dir_all(root).await?;
        Ok(())
    }

    #[tokio::test]
    async fn same_named_sources_keep_their_own_contents() -> Result<()> {
        let root =
            std::env::temp_dir().join(format!("ztdrop-same-name-{:032x}", rand::random::<u128>()));
        let first = root.join("first").join("report.txt");
        let second = root.join("second").join("report.txt");
        tokio::fs::create_dir_all(first.parent().unwrap()).await?;
        tokio::fs::create_dir_all(second.parent().unwrap()).await?;
        tokio::fs::write(&first, b"first report").await?;
        tokio::fs::write(&second, b"second report").await?;
        let sources = vec![first, second];
        let shares: CodeRegistry = Arc::new(RwLock::new(HashMap::new()));
        let item = register_new_code(shares.clone(), "reports".into(), 25, true, sources).await?;
        let (address, sender) = one_shot(shares).await?;
        let target = root.join("received");
        receive_attempt(
            address,
            None,
            &item.token,
            &target,
            0,
            true,
            false,
            |_, _, _, _| {},
        )
        .await?;
        sender.await??;
        assert_eq!(
            tokio::fs::read(target.join("report.txt")).await?,
            b"first report"
        );
        assert_eq!(
            tokio::fs::read(target.join("report (2).txt")).await?,
            b"second report"
        );
        tokio::fs::remove_dir_all(root).await?;
        Ok(())
    }

    #[tokio::test]
    async fn streams_file_and_folder_without_blob_cache() -> Result<()> {
        let root =
            std::env::temp_dir().join(format!("ztdrop-batch-{:032x}", rand::random::<u128>()));
        let source_dir = root.join("资料");
        tokio::fs::create_dir_all(source_dir.join("空目录")).await?;
        tokio::fs::create_dir_all(source_dir.join("子目录")).await?;
        let contents = vec![0x5au8; 2 * 1024 * 1024 + 17];
        tokio::fs::write(source_dir.join("样本.pdf"), &contents).await?;
        tokio::fs::write(source_dir.join("子目录").join("说明.txt"), b"hello").await?;
        let shares: CodeRegistry = Arc::new(RwLock::new(HashMap::new()));
        let item =
            register_new_code(shares.clone(), "资料".into(), 0, true, vec![source_dir]).await?;
        let (address, sender) = one_shot(shares).await?;
        let target = root.join("收到的资料");
        receive_attempt(
            address,
            None,
            &item.token,
            &target,
            0,
            true,
            false,
            |_, _, _, _| {},
        )
        .await?;
        sender.await??;
        assert_eq!(tokio::fs::read(target.join("样本.pdf")).await?, contents);
        assert_eq!(
            tokio::fs::read(target.join("子目录").join("说明.txt")).await?,
            b"hello"
        );
        assert!(target.join("空目录").is_dir());
        tokio::fs::remove_dir_all(root).await?;
        Ok(())
    }

    #[tokio::test]
    async fn serves_many_concurrent_downloads_of_one_share() -> Result<()> {
        const CLIENTS: usize = 8;
        let root =
            std::env::temp_dir().join(format!("ztdrop-stress-{:032x}", rand::random::<u128>()));
        tokio::fs::create_dir_all(&root).await?;
        let source = root.join("payload.bin");
        let contents = vec![0x33u8; 512 * 1024 + 7];
        let size = contents.len() as u64;
        tokio::fs::write(&source, &contents).await?;
        let shares: CodeRegistry = Arc::new(RwLock::new(HashMap::new()));
        let item = register_new_code(
            shares.clone(),
            "payload.bin".into(),
            size,
            false,
            vec![source],
        )
        .await?;

        let listener = TcpListener::bind("127.0.0.1:0").await?;
        let address = listener.local_addr()?;
        let server = tokio::spawn(async move {
            let mut sessions = Vec::with_capacity(CLIENTS);
            for _ in 0..CLIENTS {
                let (stream, _) = listener.accept().await?;
                let shares = shares.clone();
                sessions.push(tokio::spawn(async move {
                    serve_connection(stream, shares, "test".into(), "127.0.0.1".into(), |_| {})
                        .await
                }));
            }
            for session in sessions {
                session.await??;
            }
            Ok::<(), anyhow::Error>(())
        });

        let mut downloads = Vec::with_capacity(CLIENTS);
        for index in 0..CLIENTS {
            let target = root.join(format!("copy-{index}.bin"));
            let token = item.token.clone();
            downloads.push(tokio::spawn(async move {
                receive_attempt(
                    address,
                    None,
                    &token,
                    &target,
                    size,
                    false,
                    false,
                    |_, _, _, _| {},
                )
                .await
            }));
        }
        for download in downloads {
            download.await??;
        }
        server.await??;
        for index in 0..CLIENTS {
            assert_eq!(
                tokio::fs::read(root.join(format!("copy-{index}.bin"))).await?,
                contents
            );
        }
        tokio::fs::remove_dir_all(root).await?;
        Ok(())
    }

    #[tokio::test]
    async fn interrupted_file_resumes_without_replacing_old_target() -> Result<()> {
        let root =
            std::env::temp_dir().join(format!("ztdrop-resume-{:032x}", rand::random::<u128>()));
        tokio::fs::create_dir_all(&root).await?;
        let source = root.join("source.txt");
        let target = root.join("target.txt");
        tokio::fs::write(&source, b"short and then the rest").await?;
        tokio::fs::write(&target, b"keep me").await?;
        let manifest = scan_manifest(&source, false)?;
        let shares: CodeRegistry = Arc::new(RwLock::new(HashMap::new()));
        let item =
            register_new_code(shares.clone(), "source.txt".into(), 23, false, vec![source]).await?;
        let listener = TcpListener::bind("127.0.0.1:0").await?;
        let address = listener.local_addr()?;
        let interrupted = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await?;
            let mut request = [0u8; 36];
            stream.read_exact(&mut request).await?;
            stream.write_all(&[1]).await?;
            let bytes = serde_json::to_vec(&manifest)?;
            stream
                .write_all(&(bytes.len() as u32).to_be_bytes())
                .await?;
            stream.write_all(&bytes).await?;
            let mut plan = [0u8; 16];
            stream.read_exact(&mut plan).await?;
            assert_eq!(u64::from_be_bytes(plan[8..].try_into()?), 0);
            stream.write_all(&[1]).await?;
            stream.write_all(b"short").await?;
            Ok::<(), anyhow::Error>(())
        });
        let result = receive_attempt(
            address,
            None,
            &item.token,
            &target,
            23,
            false,
            true,
            |_, _, _, _| {},
        )
        .await;
        assert!(result.is_err());
        interrupted.await??;
        assert_eq!(tokio::fs::read(&target).await?, b"keep me");
        let partial = partial_path(
            &target,
            &scan_manifest(&root.join("source.txt"), false)?.files[0],
        )?;
        assert_eq!(tokio::fs::metadata(&partial).await?.len(), 5);

        let (address, sender) = one_shot(shares).await?;
        receive_attempt(
            address,
            None,
            &item.token,
            &target,
            23,
            false,
            true,
            |_, _, _, _| {},
        )
        .await?;
        sender.await??;
        assert_eq!(tokio::fs::read(&target).await?, b"short and then the rest");
        assert!(!partial.exists());
        tokio::fs::remove_dir_all(root).await?;
        Ok(())
    }

    #[tokio::test]
    async fn folder_resume_skips_completed_files_and_continues_partial() -> Result<()> {
        let root = std::env::temp_dir().join(format!(
            "ztdrop-folder-resume-{:032x}",
            rand::random::<u128>()
        ));
        let source = root.join("source");
        tokio::fs::create_dir_all(&source).await?;
        tokio::fs::write(source.join("a.txt"), b"complete first").await?;
        tokio::fs::write(source.join("b.txt"), b"short and the rest").await?;
        let manifest = scan_manifest(&source, true)?;
        let target = root.join("target");
        let token = "a".repeat(32);

        let listener = TcpListener::bind("127.0.0.1:0").await?;
        let address = listener.local_addr()?;
        let first_manifest = manifest.clone();
        let first = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await?;
            let mut hello = [0u8; 36];
            stream.read_exact(&mut hello).await?;
            let bytes = serde_json::to_vec(&first_manifest)?;
            stream.write_all(&[1]).await?;
            stream
                .write_all(&(bytes.len() as u32).to_be_bytes())
                .await?;
            stream.write_all(&bytes).await?;
            let mut plan = [0u8; 28];
            stream.read_exact(&mut plan).await?;
            assert_eq!(u32::from_be_bytes(plan[..4].try_into()?), 2);
            stream.write_all(&[1]).await?;
            stream.write_all(b"complete first").await?;
            stream.write_all(&[1]).await?;
            stream.write_all(b"short").await?;
            Ok::<(), anyhow::Error>(())
        });
        assert!(receive_attempt(
            address,
            None,
            &token,
            &target,
            0,
            true,
            false,
            |_, _, _, _| {}
        )
        .await
        .is_err());
        first.await??;
        assert_eq!(
            tokio::fs::read(target.join("a.txt")).await?,
            b"complete first"
        );

        let listener = TcpListener::bind("127.0.0.1:0").await?;
        let address = listener.local_addr()?;
        let second = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await?;
            let mut hello = [0u8; 36];
            stream.read_exact(&mut hello).await?;
            let bytes = serde_json::to_vec(&manifest)?;
            stream.write_all(&[1]).await?;
            stream
                .write_all(&(bytes.len() as u32).to_be_bytes())
                .await?;
            stream.write_all(&bytes).await?;
            let mut plan = [0u8; 16];
            stream.read_exact(&mut plan).await?;
            assert_eq!(u32::from_be_bytes(plan[..4].try_into()?), 1);
            assert_eq!(u32::from_be_bytes(plan[4..8].try_into()?), 1);
            assert_eq!(u64::from_be_bytes(plan[8..].try_into()?), 5);
            stream.write_all(&[1]).await?;
            stream.write_all(b" and the rest").await?;
            Ok::<(), anyhow::Error>(())
        });
        receive_attempt(
            address,
            None,
            &token,
            &target,
            0,
            true,
            true,
            |_, _, _, _| {},
        )
        .await?;
        second.await??;
        assert_eq!(
            tokio::fs::read(target.join("b.txt")).await?,
            b"short and the rest"
        );
        tokio::fs::remove_dir_all(root).await?;
        Ok(())
    }
}
