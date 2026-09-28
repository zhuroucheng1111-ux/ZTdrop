use crate::engine::network::NetworkPolicy;
use anyhow::{bail, Context, Result};
use qrcode::{render::svg, QrCode};
use serde::Serialize;
use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime};
use tokio::io::{AsyncReadExt, AsyncSeekExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{watch, RwLock, Semaphore};

pub const WEB_PORT: u16 = 52112;
const MAX_WEB_CONNECTIONS: usize = 8;
const MAX_WEB_SESSIONS: usize = 256;

#[derive(Clone, Serialize)]
pub struct WebShareView {
    pub share_id: String,
    pub token: String,
    pub file_name: String,
    pub file_size: u64,
    pub url: String,
    pub qr_svg: String,
    pub expires_in: Option<u64>,
    pub visitors: usize,
    pub downloads: usize,
    pub sessions: Vec<WebSessionView>,
}

#[derive(Clone, Serialize)]
pub struct WebSessionView {
    pub browser: String,
    pub ip: String,
    pub transferred: u64,
    pub total: u64,
    pub status: String,
}

/// 统一会话视图里的浏览器分享条目。
#[derive(Clone, Serialize)]
pub struct WebShareSummary {
    pub token: String,
    pub share_id: String,
    pub file_name: String,
    pub file_size: u64,
    pub expires_in: Option<u64>,
    pub visitors: u32,
}

struct WebSession {
    last_seen: Instant,
    view: WebSessionView,
}

struct WebShare {
    id: String,
    token: String,
    path: PathBuf,
    name: String,
    size: u64,
    modified: SystemTime,
    expires: Option<Instant>,
    stop: watch::Sender<bool>,
    /// 传完再停：不再接受新请求，已建立的下载继续。
    draining: std::sync::atomic::AtomicBool,
    admission: RwLock<()>,
    sessions: RwLock<HashMap<String, WebSession>>,
    downloads: std::sync::atomic::AtomicUsize,
}

struct WebDownloadGuard<'a>(&'a std::sync::atomic::AtomicUsize);

impl Drop for WebDownloadGuard<'_> {
    fn drop(&mut self) {
        self.0.fetch_sub(1, std::sync::atomic::Ordering::Relaxed);
    }
}

impl WebShare {
    fn active(&self) -> bool {
        !*self.stop.borrow()
            && !self.draining.load(std::sync::atomic::Ordering::Relaxed)
            && self.expires.is_none_or(|at| Instant::now() < at)
    }

    fn is_draining(&self) -> bool {
        self.draining.load(std::sync::atomic::Ordering::Relaxed)
    }

    fn is_stopped(&self) -> bool {
        *self.stop.borrow()
    }

    fn is_expired(&self) -> bool {
        self.expires.is_some_and(|at| Instant::now() >= at)
    }
}

#[derive(Default)]
pub struct WebShareServer {
    shares: RwLock<HashMap<String, Arc<WebShare>>>,
}

fn token() -> String {
    let bytes: [u8; 32] = rand::random();
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

fn valid_token(value: &str) -> bool {
    value.len() == 64 && value.bytes().all(|b| b.is_ascii_hexdigit())
}

fn valid_session_id(value: &str) -> bool {
    value.len() == 32 && value.bytes().all(|b| b.is_ascii_hexdigit())
}

fn browser_label(agent: &str) -> String {
    if agent.contains("iPhone") || agent.contains("iPad") {
        "iPhone / iPad Safari".into()
    } else if agent.contains("Android") {
        "Android Chrome".into()
    } else if agent.contains("Edg/") {
        "Microsoft Edge".into()
    } else if agent.contains("Chrome/") {
        "Google Chrome".into()
    } else if agent.contains("Safari/") {
        "Safari".into()
    } else {
        "浏览器".into()
    }
}

fn safe_html(value: &str) -> String {
    value
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&#39;")
}

fn encoded_filename(name: &str) -> String {
    let mut out = String::new();
    for byte in name.bytes() {
        if byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_' | b'.') {
            out.push(byte as char);
        } else {
            out.push_str(&format!("%{byte:02X}"));
        }
    }
    out
}

fn parse_range(value: &str, size: u64) -> Option<(u64, u64)> {
    let text = value.strip_prefix("bytes=")?;
    if text.contains(',') {
        return None;
    }
    let (start, end) = text.split_once('-')?;
    if size == 0 {
        return None;
    }
    if start.is_empty() {
        let suffix: u64 = end.parse().ok()?;
        if suffix == 0 {
            return None;
        }
        return Some((size.saturating_sub(suffix), size - 1));
    }
    let first: u64 = start.parse().ok()?;
    let last = if end.is_empty() {
        size - 1
    } else {
        end.parse::<u64>().ok()?.min(size - 1)
    };
    (first < size && first <= last).then_some((first, last))
}

impl WebShareServer {
    pub async fn start(self: Arc<Self>, network: Arc<NetworkPolicy>) -> Result<()> {
        let listener = TcpListener::bind(("0.0.0.0", WEB_PORT))
            .await
            .context("无法启动 HTTP 52112，请检查端口是否被占用")?;
        let cleanup = self.clone();
        tokio::spawn(async move {
            loop {
                tokio::time::sleep(Duration::from_secs(60)).await;
                let mut shares = cleanup.shares.write().await;
                shares.retain(|_, share| {
                    // 处于“传完再停”的分享要保留到下载结束，只有停止或过期才清理。
                    if !share.is_stopped() && !share.is_expired() {
                        true
                    } else {
                        share.stop.send_replace(true);
                        false
                    }
                });
                let active: Vec<_> = shares.values().cloned().collect();
                drop(shares);
                for share in active {
                    share
                        .sessions
                        .write()
                        .await
                        .retain(|_, session| session.last_seen.elapsed() < Duration::from_secs(60));
                }
            }
        });
        let slots = Arc::new(Semaphore::new(MAX_WEB_CONNECTIONS));
        tokio::spawn(async move {
            loop {
                let Ok((stream, remote)) = listener.accept().await else {
                    continue;
                };
                if !stream
                    .local_addr()
                    .is_ok_and(|local| network.allows_connection(local, remote))
                {
                    continue;
                }
                let _ = socket2::SockRef::from(&stream).set_keepalive(true);
                if !matches!(remote.ip(), IpAddr::V4(ip) if ip.is_private() || ip.is_loopback())
                    && !matches!(remote.ip(), IpAddr::V6(ip) if ip.is_unique_local() || ip.is_loopback())
                {
                    continue;
                }
                let Ok(permit) = slots.clone().try_acquire_owned() else {
                    continue;
                };
                let server = self.clone();
                tokio::spawn(async move {
                    let _permit = permit;
                    let _ = server.handle(stream, remote).await;
                });
            }
        });
        Ok(())
    }

    pub async fn create(
        &self,
        source: &Path,
        lifetime: Option<Duration>,
        ip: &str,
    ) -> Result<WebShareView> {
        let metadata = tokio::fs::metadata(source).await?;
        if !metadata.is_file() {
            bail!("浏览器单独分享请选择一个文件");
        }
        let name = source
            .file_name()
            .context("文件名无效")?
            .to_string_lossy()
            .to_string();
        let id = format!("{:032x}", rand::random::<u128>());
        let web_token = token();
        let (stop, _) = watch::channel(false);
        let share = Arc::new(WebShare {
            id,
            token: web_token.clone(),
            path: source.to_path_buf(),
            name,
            size: metadata.len(),
            modified: metadata.modified()?,
            expires: lifetime.map(|duration| Instant::now() + duration),
            stop,
            draining: std::sync::atomic::AtomicBool::new(false),
            admission: RwLock::new(()),
            sessions: RwLock::new(HashMap::new()),
            downloads: std::sync::atomic::AtomicUsize::new(0),
        });
        self.shares.write().await.insert(web_token, share.clone());
        Ok(Self::view(&share, ip).await?)
    }

    async fn view(share: &WebShare, ip: &str) -> Result<WebShareView> {
        let url = format!("http://{ip}:{WEB_PORT}/s/{}", share.token);
        let qr_svg = QrCode::new(url.as_bytes())?
            .render::<svg::Color>()
            .min_dimensions(220, 220)
            .build();
        let mut sessions = share.sessions.write().await;
        sessions.retain(|_, session| session.last_seen.elapsed() < Duration::from_secs(60));
        Ok(WebShareView {
            share_id: share.id.clone(),
            token: share.token.clone(),
            file_name: share.name.clone(),
            file_size: share.size,
            url,
            qr_svg,
            expires_in: share
                .expires
                .map(|at| at.saturating_duration_since(Instant::now()).as_secs()),
            visitors: sessions.len(),
            downloads: share.downloads.load(std::sync::atomic::Ordering::Relaxed),
            sessions: sessions
                .values()
                .map(|session| session.view.clone())
                .collect(),
        })
    }

    pub async fn status(&self, web_token: &str, ip: &str) -> Result<WebShareView> {
        let share = self
            .shares
            .read()
            .await
            .get(web_token)
            .cloned()
            .context("分享不存在")?;
        if share.is_stopped() || share.is_expired() {
            bail!("分享已结束或过期");
        }
        Self::view(&share, ip).await
    }

    pub async fn stop(&self, web_token: &str, share_id: &str) -> Result<()> {
        let mut shares = self.shares.write().await;
        let share = shares.get(web_token).context("分享不存在")?;
        if share.id != share_id {
            bail!("分享身份不匹配");
        }
        share.stop.send_replace(true);
        shares.remove(web_token);
        Ok(())
    }

    /// 传完再停：停止接受新请求，等待已经开始的下载结束。
    pub async fn drain(&self, web_token: &str, share_id: &str) -> Result<()> {
        let share = self
            .shares
            .read()
            .await
            .get(web_token)
            .cloned()
            .context("分享不存在")?;
        if share.id != share_id {
            bail!("分享身份不匹配");
        }
        let _admission = share.admission.write().await;
        share
            .draining
            .store(true, std::sync::atomic::Ordering::Relaxed);
        Ok(())
    }

    pub async fn state_of(&self, web_token: &str) -> Option<(bool, bool, usize)> {
        let share = self.shares.read().await.get(web_token).cloned()?;
        let downloads = share.downloads.load(std::sync::atomic::Ordering::Relaxed);
        Some((share.is_stopped(), share.is_draining(), downloads))
    }

    /// 当前仍然有效的浏览器分享，供统一会话视图和“停止全部”使用。
    pub async fn sessions(&self) -> Vec<WebShareSummary> {
        let mut shares = self.shares.write().await;
        shares.retain(|_, share| !share.is_stopped() && !share.is_expired());
        let mut list = Vec::with_capacity(shares.len());
        for share in shares.values() {
            let mut sessions = share.sessions.write().await;
            sessions.retain(|_, session| session.last_seen.elapsed() < Duration::from_secs(60));
            list.push(WebShareSummary {
                token: share.token.clone(),
                share_id: share.id.clone(),
                file_name: share.name.clone(),
                file_size: share.size,
                expires_in: share
                    .expires
                    .map(|at| at.saturating_duration_since(Instant::now()).as_secs()),
                visitors: sessions.len() as u32,
            });
        }
        list
    }

    async fn handle(&self, mut stream: TcpStream, remote: SocketAddr) -> Result<()> {
        let mut request = Vec::with_capacity(2048);
        let mut chunk = [0u8; 1024];
        let deadline = tokio::time::Instant::now() + Duration::from_secs(5);
        loop {
            let size = tokio::time::timeout_at(deadline, stream.read(&mut chunk)).await??;
            if size == 0 {
                return Ok(());
            }
            request.extend_from_slice(&chunk[..size]);
            if request.len() > 16_384 {
                return reply(
                    &mut stream,
                    "431 Request Header Fields Too Large",
                    "text/plain",
                    b"Header too large",
                    &[],
                )
                .await;
            }
            if request.windows(4).any(|part| part == b"\r\n\r\n") {
                break;
            }
        }
        let request = std::str::from_utf8(&request)?;
        let mut lines = request.split("\r\n");
        let first = lines.next().context("请求行缺失")?;
        let mut fields = first.split_whitespace();
        let method = fields.next().unwrap_or("");
        let path = fields.next().unwrap_or("");
        if !matches!(method, "GET" | "HEAD") {
            return reply(
                &mut stream,
                "405 Method Not Allowed",
                "text/plain",
                b"Method not allowed",
                &[],
            )
            .await;
        }
        let range = lines.clone().find_map(|line| {
            line.strip_prefix("Range: ")
                .or_else(|| line.strip_prefix("range: "))
        });
        let agent = lines
            .clone()
            .find_map(|line| {
                line.strip_prefix("User-Agent: ")
                    .or_else(|| line.strip_prefix("user-agent: "))
            })
            .unwrap_or("Browser");
        let session_id = lines
            .find_map(|line| {
                line.strip_prefix("Cookie: ")
                    .or_else(|| line.strip_prefix("cookie: "))
            })
            .and_then(|cookie| {
                cookie
                    .split(';')
                    .find_map(|part| part.trim().strip_prefix("ztdrop_session="))
            })
            .filter(|id| valid_session_id(id))
            .map(str::to_string)
            .unwrap_or_else(|| format!("{:032x}", rand::random::<u128>()));
        let cookie_header =
            format!("ztdrop_session={session_id}; HttpOnly; SameSite=Lax; Path=/; Max-Age=86400");
        let segments: Vec<&str> = path.trim_start_matches('/').split('/').collect();
        let (kind, web_token) = match segments.as_slice() {
            ["s", token] => ("page", *token),
            ["api", "share", token] => ("api", *token),
            ["api", "share", token, "status"] => ("api", *token),
            ["download", token] => ("download", *token),
            _ => {
                return reply(
                    &mut stream,
                    "404 Not Found",
                    "text/plain",
                    b"Not found",
                    &[],
                )
                .await
            }
        };
        if !valid_token(web_token) {
            return reply(
                &mut stream,
                "404 Not Found",
                "text/plain",
                b"Not found",
                &[],
            )
            .await;
        }
        let Some(share) = self.shares.read().await.get(web_token).cloned() else {
            return reply(&mut stream, "410 Gone", "text/plain", b"Share ended", &[]).await;
        };
        let _download_guard = if kind == "download" {
            let _admission = share.admission.read().await;
            if share.active() {
                share
                    .downloads
                    .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                Some(WebDownloadGuard(&share.downloads))
            } else {
                None
            }
        } else {
            None
        };
        if !share.active() && _download_guard.is_none() {
            return reply(&mut stream, "410 Gone", "text/plain", b"Share ended", &[]).await;
        }
        {
            let mut sessions = share.sessions.write().await;
            if !sessions.contains_key(&session_id) && sessions.len() >= MAX_WEB_SESSIONS {
                if let Some(oldest) = sessions
                    .iter()
                    .min_by_key(|(_, session)| session.last_seen)
                    .map(|(id, _)| id.clone())
                {
                    sessions.remove(&oldest);
                }
            }
            let session = sessions
                .entry(session_id.clone())
                .or_insert_with(|| WebSession {
                    last_seen: Instant::now(),
                    view: WebSessionView {
                        browser: browser_label(agent),
                        ip: remote.ip().to_string(),
                        transferred: 0,
                        total: share.size,
                        status: "浏览中".into(),
                    },
                });
            session.last_seen = Instant::now();
        }
        if kind == "page" {
            let name = safe_html(&share.name);
            let page = format!("<!doctype html><html lang=zh-CN><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>ZTDrop 下载</title><style>body{{font-family:system-ui,sans-serif;background:#f6f7f8;color:#20252a;margin:0;padding:30px}}main{{max-width:480px;margin:12vh auto;background:white;padding:28px;border-radius:18px;box-shadow:0 8px 30px #0001}}a{{display:inline-block;background:#282d31;color:white;padding:12px 22px;border-radius:8px;text-decoration:none}}p{{color:#667}}</style><main><h2>ZTDrop 局域网分享</h2><p>{name}</p><p>{} 字节</p><a href='/download/{web_token}'>下载文件</a><p id=status></p></main><script>setInterval(async()=>{{try{{let r=await fetch('/api/share/{web_token}/status');if(!r.ok)document.querySelector('#status').textContent='分享已结束或过期'}}catch{{}}}},20000)</script></html>", share.size);
            return reply(
                &mut stream,
                "200 OK",
                "text/html; charset=utf-8",
                page.as_bytes(),
                &[("Set-Cookie", cookie_header.clone())],
            )
            .await;
        }
        if kind == "api" {
            let data = serde_json::json!({"file_name":share.name,"file_size":share.size,"active":true,
                "expires_in":share.expires.map(|at| at.saturating_duration_since(Instant::now()).as_secs())}).to_string();
            return reply(
                &mut stream,
                "200 OK",
                "application/json; charset=utf-8",
                data.as_bytes(),
                &[("Set-Cookie", cookie_header.clone())],
            )
            .await;
        }
        let metadata = tokio::fs::metadata(&share.path).await;
        if !metadata.as_ref().is_ok_and(|m| {
            m.is_file() && m.len() == share.size && m.modified().ok() == Some(share.modified)
        }) {
            return reply(
                &mut stream,
                "409 Conflict",
                "text/plain",
                b"Source file changed",
                &[],
            )
            .await;
        }
        let range = if let Some(value) = range {
            match parse_range(value, share.size) {
                Some(r) => Some(r),
                None => {
                    return reply(
                        &mut stream,
                        "416 Range Not Satisfiable",
                        "text/plain",
                        b"Invalid range",
                        &[("Content-Range", format!("bytes */{}", share.size))],
                    )
                    .await
                }
            }
        } else {
            None
        };
        let (start, end) = range.unwrap_or((0, share.size.saturating_sub(1)));
        let length = if share.size == 0 { 0 } else { end - start + 1 };
        let status = if range.is_some() {
            "206 Partial Content"
        } else {
            "200 OK"
        };
        let mut headers = vec![
            (
                "Content-Disposition",
                format!(
                    "attachment; filename*=UTF-8''{}",
                    encoded_filename(&share.name)
                ),
            ),
            ("Accept-Ranges", "bytes".into()),
            ("Set-Cookie", cookie_header),
        ];
        if range.is_some() {
            headers.push((
                "Content-Range",
                format!("bytes {start}-{end}/{}", share.size),
            ));
        }
        let mut head = format!("HTTP/1.1 {status}\r\nContent-Type: application/octet-stream\r\nContent-Length: {length}\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\nCache-Control: no-store\r\n");
        for (key, value) in headers {
            head.push_str(&format!("{key}: {value}\r\n"));
        }
        head.push_str("\r\n");
        stream.write_all(head.as_bytes()).await?;
        if method == "HEAD" {
            return Ok(());
        }
        let mut file = tokio::fs::File::open(&share.path).await?;
        file.seek(std::io::SeekFrom::Start(start)).await?;
        let mut remaining = length;
        let mut stopped = share.stop.subscribe();
        if let Some(session) = share.sessions.write().await.get_mut(&session_id) {
            session.view.transferred = start;
            session.view.status = "下载中".into();
        }
        let result = async {
            let mut buffer = [0u8; 65_536];
            let mut chunks = 0u64;
            while remaining > 0 {
                if share.is_stopped() || share.is_expired() { break; }
                if chunks % 16 == 0 {
                    let current = file.metadata().await?;
                    if current.len() != share.size || current.modified()? != share.modified {
                        bail!("源文件在下载期间发生变化");
                    }
                }
                let limit = remaining.min(buffer.len() as u64) as usize;
                let amount = file.read(&mut buffer[..limit]).await?;
                if amount == 0 { bail!("源文件在下载期间提前结束"); }
                tokio::select! {
                    result = tokio::time::timeout(Duration::from_secs(60), stream.write_all(&buffer[..amount])) => result??,
                    _ = stopped.changed() => break,
                }
                remaining -= amount as u64;
                chunks += 1;
                if chunks % 16 == 0 || remaining == 0 {
                    if let Some(session) = share.sessions.write().await.get_mut(&session_id) {
                        session.view.transferred = end + 1 - remaining;
                        session.last_seen = Instant::now();
                    }
                }
            }
            Ok::<(), anyhow::Error>(())
        }.await;
        if let Some(session) = share.sessions.write().await.get_mut(&session_id) {
            session.view.status = if result.is_err() {
                "下载失败".into()
            } else if remaining == 0 {
                "已完成".into()
            } else {
                "已停止".into()
            };
            session.last_seen = Instant::now();
        }
        result
    }
}

async fn reply(
    stream: &mut TcpStream,
    status: &str,
    content_type: &str,
    body: &[u8],
    headers: &[(&str, String)],
) -> Result<()> {
    let mut head = format!("HTTP/1.1 {status}\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n", body.len());
    for (key, value) in headers {
        head.push_str(&format!("{key}: {value}\r\n"));
    }
    head.push_str("\r\n");
    stream.write_all(head.as_bytes()).await?;
    stream.write_all(body).await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    async fn request(server: Arc<WebShareServer>, raw: String) -> Result<String> {
        let listener = TcpListener::bind("127.0.0.1:0").await?;
        let address = listener.local_addr()?;
        let task = tokio::spawn(async move {
            let (stream, remote) = listener.accept().await?;
            server.handle(stream, remote).await
        });
        let mut client = TcpStream::connect(address).await?;
        client.write_all(raw.as_bytes()).await?;
        let mut bytes = Vec::new();
        client.read_to_end(&mut bytes).await?;
        task.await??;
        Ok(String::from_utf8(bytes)?)
    }

    #[tokio::test]
    async fn serves_range_and_revokes_token() -> Result<()> {
        let path =
            std::env::temp_dir().join(format!("ztdrop-web-{:032x}.txt", rand::random::<u128>()));
        tokio::fs::write(&path, b"abcdefghij").await?;
        let server = Arc::new(WebShareServer::default());
        let share = server
            .create(&path, Some(Duration::from_secs(60)), "127.0.0.1")
            .await?;
        let page = request(
            server.clone(),
            format!(
                "GET /s/{} HTTP/1.1\r\nHost: localhost\r\nUser-Agent: Chrome/120\r\n\r\n",
                share.token,
            ),
        )
        .await?;
        let cookie = page
            .lines()
            .find_map(|line| line.strip_prefix("Set-Cookie: "))
            .context("浏览器会话 Cookie 缺失")?
            .split(';')
            .next()
            .unwrap()
            .to_string();
        let response = request(
            server.clone(),
            format!(
                "GET /download/{} HTTP/1.1\r\nHost: localhost\r\nCookie: {cookie}\r\nRange: bytes=3-6\r\n\r\n",
                share.token,
            ),
        )
        .await?;
        assert!(response.starts_with("HTTP/1.1 206 Partial Content"));
        assert!(response.contains("Content-Range: bytes 3-6/10"));
        assert!(response.ends_with("\r\n\r\ndefg"));
        let session = server.status(&share.token, "127.0.0.1").await?;
        assert_eq!(session.visitors, 1);
        assert_eq!(session.sessions[0].transferred, 7);
        assert_eq!(session.sessions[0].status, "已完成");
        let invalid = request(
            server.clone(),
            format!(
                "GET /download/{} HTTP/1.1\r\nHost: localhost\r\nRange: bytes=99-\r\n\r\n",
                share.token
            ),
        )
        .await?;
        assert!(invalid.starts_with("HTTP/1.1 416 Range Not Satisfiable"));
        tokio::fs::write(&path, b"changed").await?;
        let changed = request(
            server.clone(),
            format!(
                "GET /download/{} HTTP/1.1\r\nHost: localhost\r\n\r\n",
                share.token
            ),
        )
        .await?;
        assert!(changed.starts_with("HTTP/1.1 409 Conflict"));
        server.stop(&share.token, &share.share_id).await?;
        let ended = request(
            server.clone(),
            format!(
                "GET /download/{} HTTP/1.1\r\nHost: localhost\r\n\r\n",
                share.token
            ),
        )
        .await?;
        assert!(ended.starts_with("HTTP/1.1 410 Gone"));
        tokio::fs::remove_file(path).await?;
        Ok(())
    }
    #[tokio::test]
    async fn draining_keeps_an_existing_browser_download_alive() -> Result<()> {
        let path = std::env::temp_dir().join(format!(
            "ztdrop-web-drain-{:032x}.bin",
            rand::random::<u128>()
        ));
        let contents = vec![0x5au8; 8 * 1024 * 1024];
        tokio::fs::write(&path, &contents).await?;
        let server = Arc::new(WebShareServer::default());
        let share = server.create(&path, None, "127.0.0.1").await?;
        let listener = TcpListener::bind("127.0.0.1:0").await?;
        let address = listener.local_addr()?;
        let serving = server.clone();
        let task = tokio::spawn(async move {
            let (stream, remote) = listener.accept().await?;
            serving.handle(stream, remote).await
        });
        let mut client = TcpStream::connect(address).await?;
        let _ = socket2::SockRef::from(&client).set_recv_buffer_size(4096);
        client
            .write_all(
                format!(
                    "GET /download/{} HTTP/1.1\r\nHost: localhost\r\n\r\n",
                    share.token
                )
                .as_bytes(),
            )
            .await?;
        let mut first = [0u8; 1024];
        let count = client.read(&mut first).await?;
        assert!(count > 0);
        tokio::time::timeout(Duration::from_secs(2), async {
            while server.state_of(&share.token).await.unwrap().2 == 0 {
                tokio::task::yield_now().await;
            }
        })
        .await?;
        assert_eq!(server.state_of(&share.token).await.unwrap().2, 1);
        server.drain(&share.token, &share.share_id).await?;
        assert!(server.status(&share.token, "127.0.0.1").await.is_ok());
        let mut response = first[..count].to_vec();
        tokio::time::timeout(Duration::from_secs(20), client.read_to_end(&mut response)).await??;
        task.await??;
        let body = response
            .windows(4)
            .position(|part| part == b"\r\n\r\n")
            .unwrap()
            + 4;
        assert_eq!(&response[body..], contents);
        tokio::fs::remove_file(path).await?;
        Ok(())
    }
    #[test]
    fn range_cases() {
        assert_eq!(parse_range("bytes=5-", 10), Some((5, 9)));
        assert_eq!(parse_range("bytes=-3", 10), Some((7, 9)));
        assert_eq!(parse_range("bytes=20-", 10), None);
        assert_eq!(parse_range("bytes=2-1", 10), None);
        assert_eq!(parse_range("bytes=0-0", 0), None);
    }
    #[test]
    fn html_and_name_escape() {
        assert_eq!(safe_html("<a&\""), "&lt;a&amp;&quot;");
        assert_eq!(encoded_filename("中.txt"), "%E4%B8%AD.txt");
    }
}
