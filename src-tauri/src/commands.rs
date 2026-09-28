use crate::engine::code_lease::{CodeLeaseManager, CodeLeaseView};
use crate::engine::code_share::CodeShareItem;
use crate::engine::diagnostics::{Diagnostics, LogEntry};
use crate::engine::discovery::{PeerDevice, PeerRegistry, DISCOVERY_PORT};
use crate::engine::messenger::{ChatMessage, FriendView, Messenger};
use crate::engine::network::NetworkPolicy;
use crate::engine::protocol;
use crate::engine::resource::ShareResource;
use crate::engine::share_manager::{ShareFileView, ShareManager};
use crate::engine::transfer::download_stream;
use crate::engine::transport::{TransportEngine, MAX_CONCURRENT_TRANSFERS, TRANSFER_PORT};
use crate::engine::web_share::{WebShareServer, WebShareView, WEB_PORT};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::collections::HashSet;
use std::net::{IpAddr, SocketAddr};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tauri::{AppHandle, Manager, State};
use tokio::net::UdpSocket;

#[derive(Serialize)]
pub struct LocalNodeInfo {
    pub node_id: String,
    pub device_name: String,
    pub ip: String,
    pub port: u16,
}

#[derive(Deserialize, Serialize)]
pub struct ResolvedShare {
    pub token: String,
    pub file_name: String,
    pub file_size: u64,
    pub is_folder: bool,
    pub address: String,
}

pub struct AppState {
    pub transport: Arc<TransportEngine>,
    pub peers: PeerRegistry,
    pub code_leases: Arc<CodeLeaseManager>,
    pub messenger: Arc<Messenger>,
    pub web_shares: Arc<WebShareServer>,
    pub diagnostics: Arc<Diagnostics>,
    pub device_name: String,
    pub local_ip: String,
    pub network: Arc<NetworkPolicy>,
    pub active_transfers: Arc<AtomicUsize>,
    pub share_manager: Arc<ShareManager>,
    pub data_dir: std::path::PathBuf,
}

#[derive(Serialize)]
pub struct NetworkSnapshot {
    pub device_id: String,
    pub device_name: String,
    pub local_ip: String,
    pub protocol_version: u64,
    pub udp_port: u16,
    pub tcp_port: u16,
    pub web_port: u16,
    pub max_concurrent_transfers: usize,
    pub active_transfers: usize,
    pub interface: String,
    pub port_health: PortHealth,
    pub log_categories: Vec<String>,
    pub log_path: Option<String>,
    /// 便携式数据目录：聊天记录、日志、WebView 缓存都在这里。
    pub data_dir: String,
    pub peers: Vec<PeerDevice>,
    pub leases: Vec<CodeLeaseView>,
    pub sessions: Vec<ShareResource>,
    pub logs: Vec<LogEntry>,
}

/// 三个监听端口的健康状态（诊断页“UDP/TCP/HTTP：正常 / 异常”）。
#[derive(Clone, Serialize)]
pub struct PortHealth {
    pub udp: bool,
    pub tcp: bool,
    pub http: bool,
}

async fn reachable_local_ip(state: &AppState) -> String {
    let interfaces = state.network.interfaces();
    for peer in state.peers.read().await.values() {
        if let Ok(remote) = peer.ip.parse::<IpAddr>() {
            if !state.network.allows_route_to(remote) {
                continue;
            }
            if state.network.physical_lan_only() {
                if let IpAddr::V4(remote) = remote {
                    if let Some(nic) = interfaces.iter().find(|nic| nic.contains(remote)) {
                        return nic.ip.to_string();
                    }
                }
                continue;
            }
            if let Ok(socket) = std::net::UdpSocket::bind("0.0.0.0:0") {
                if socket
                    .connect(SocketAddr::new(remote, DISCOVERY_PORT))
                    .is_ok()
                {
                    if let Ok(local) = socket.local_addr() {
                        if !local.ip().is_loopback() {
                            return local.ip().to_string();
                        }
                    }
                }
            }
        }
    }
    if let Some(nic) = interfaces.first() {
        return nic.ip.to_string();
    }
    if state.network.physical_lan_only() {
        "未找到已连接的物理局域网网卡".into()
    } else {
        state.local_ip.clone()
    }
}

fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
        .unwrap_or(0)
}

/// 端口健康检查：TCP/HTTP 用一次本地连接探测，UDP 在服务启动成功即视为正常。
fn port_open(port: u16) -> bool {
    std::net::TcpStream::connect_timeout(
        &SocketAddr::from(([127, 0, 0, 1], port)),
        Duration::from_millis(300),
    )
    .is_ok()
}

/// 找到承载该地址的网卡名称，用于诊断页显示“当前网络接口”。
fn interface_name(ip: &str) -> String {
    local_ip_address::list_afinet_netifas()
        .ok()
        .and_then(|list| {
            list.into_iter()
                .find(|(_, address)| address.to_string() == ip)
                .map(|(name, _)| name)
        })
        .unwrap_or_else(|| "未知".into())
}

#[tauri::command]
pub async fn get_active_sessions(state: State<'_, AppState>) -> Result<Vec<ShareResource>, String> {
    Ok(state.share_manager.sessions().await)
}

/// 发送页展开详情用：读取某个分享里包含的文件清单。
#[tauri::command]
pub async fn get_share_files(
    code: String,
    state: State<'_, AppState>,
) -> Result<Vec<ShareFileView>, String> {
    state
        .share_manager
        .share_files(&code)
        .await
        .map_err(|error| error.to_string())
}

/// 统一的停止入口。`drain = true` 表示“传完再停”（规划 §25）：
/// 立即拒绝新连接，等当前传输结束后自动收尾。
#[tauri::command]
pub async fn stop_session(
    id: String,
    share_id: String,
    drain: Option<bool>,
    state: State<'_, AppState>,
) -> Result<(), String> {
    let _ = share_id;
    state
        .share_manager
        .stop(&id, drain.unwrap_or(false))
        .await
        .map_err(|error| error.to_string())
}

#[tauri::command]
pub async fn stop_all_sessions(
    drain: Option<bool>,
    state: State<'_, AppState>,
) -> Result<usize, String> {
    state
        .share_manager
        .stop_all(drain.unwrap_or(false))
        .await
        .map_err(|error| error.to_string())
}

/// 导出诊断报告：版本、设备、端口、会话、租约和最近日志，便于离线排查。
#[tauri::command]
pub async fn export_diagnostics(
    path: String,
    state: State<'_, AppState>,
) -> Result<String, String> {
    let peers: Vec<PeerDevice> = state.peers.read().await.values().cloned().collect();
    let leases = state.code_leases.snapshot().await;
    let sessions = state.share_manager.sessions().await;
    let local_ip = reachable_local_ip(&state).await;
    let report = json!({
        "product": "ZTDrop",
        "protocol_version": protocol::PROTOCOL_VERSION,
        "exported_at_ms": now_ms(),
        "device_id": state.transport.node_id(),
        "device_name": state.device_name,
        "local_ip": local_ip,
        "interface": interface_name(&local_ip),
        "ports": { "udp": DISCOVERY_PORT, "tcp": TRANSFER_PORT, "http": WEB_PORT },
        "port_health": {
            "udp": true,
            "tcp": port_open(TRANSFER_PORT),
            "http": port_open(WEB_PORT),
        },
        "physical_lan_only": state.network.physical_lan_only(),
        "max_concurrent_transfers": MAX_CONCURRENT_TRANSFERS,
        "active_transfers": state.active_transfers.load(Ordering::Relaxed),
        "peers": peers,
        "leases": leases,
        "sessions": sessions,
        "logs": state.diagnostics.recent(),
    });
    let text = serde_json::to_string_pretty(&report).map_err(|error| error.to_string())?;
    std::fs::write(&path, text).map_err(|error| format!("写入诊断报告失败：{error}"))?;
    state
        .diagnostics
        .record("DIAG", format!("导出诊断报告到 {path}"));
    Ok(path)
}

#[tauri::command]
pub fn get_network_preference(state: State<'_, AppState>) -> bool {
    state.network.physical_lan_only()
}

#[tauri::command]
pub fn set_network_preference(
    physical_lan_only: bool,
    state: State<'_, AppState>,
) -> Result<(), String> {
    state
        .network
        .set_physical_lan_only(physical_lan_only)
        .map_err(|error| error.to_string())
}

#[tauri::command]
pub async fn get_network_diagnostics(
    state: State<'_, AppState>,
) -> Result<NetworkSnapshot, String> {
    let local_ip = reachable_local_ip(&state).await;
    Ok(NetworkSnapshot {
        device_id: state.transport.node_id(),
        device_name: state.device_name.clone(),
        local_ip: local_ip.clone(),
        protocol_version: protocol::PROTOCOL_VERSION,
        udp_port: DISCOVERY_PORT,
        tcp_port: TRANSFER_PORT,
        web_port: WEB_PORT,
        max_concurrent_transfers: MAX_CONCURRENT_TRANSFERS,
        active_transfers: state.active_transfers.load(Ordering::Relaxed),
        interface: interface_name(&local_ip),
        port_health: PortHealth {
            udp: true,
            tcp: port_open(TRANSFER_PORT),
            http: port_open(WEB_PORT),
        },
        log_categories: state.diagnostics.categories(),
        log_path: state
            .diagnostics
            .log_path()
            .map(|path| path.to_string_lossy().to_string()),
        data_dir: state.data_dir.to_string_lossy().to_string(),
        peers: state.peers.read().await.values().cloned().collect(),
        leases: state.code_leases.snapshot().await,
        sessions: state.share_manager.sessions().await,
        logs: state.diagnostics.recent(),
    })
}

#[tauri::command]
pub async fn get_local_node_info(state: State<'_, AppState>) -> Result<LocalNodeInfo, String> {
    Ok(LocalNodeInfo {
        node_id: state.transport.node_id(),
        device_name: state.device_name.clone(),
        ip: reachable_local_ip(&state).await,
        port: state.transport.listen_port,
    })
}

#[tauri::command]
pub async fn create_web_share(
    file_path: String,
    lifetime_seconds: Option<u64>,
    state: State<'_, AppState>,
) -> Result<WebShareView, String> {
    let lifetime = match lifetime_seconds {
        Some(900 | 3600 | 28800 | 86400) => lifetime_seconds.map(Duration::from_secs),
        None => None,
        _ => return Err("分享有效期无效".into()),
    };
    let ip = reachable_local_ip(&state).await;
    if ip.parse::<IpAddr>().is_err() {
        return Err("未找到已连接的物理局域网网卡；请检查网线或 Wi-Fi 连接".into());
    }
    state
        .web_shares
        .create(Path::new(&file_path), lifetime, &ip)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn get_web_share(
    token: String,
    state: State<'_, AppState>,
) -> Result<WebShareView, String> {
    let ip = reachable_local_ip(&state).await;
    if ip.parse::<IpAddr>().is_err() {
        return Err("未找到已连接的物理局域网网卡；请检查网线或 Wi-Fi 连接".into());
    }
    state
        .web_shares
        .status(&token, &ip)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn stop_web_share(
    token: String,
    share_id: String,
    state: State<'_, AppState>,
) -> Result<(), String> {
    state
        .web_shares
        .stop(&token, &share_id)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn get_nearby_devices(state: State<'_, AppState>) -> Result<Vec<PeerDevice>, String> {
    Ok(state.peers.read().await.values().cloned().collect())
}

#[tauri::command]
pub async fn get_friends(state: State<'_, AppState>) -> Result<Vec<FriendView>, String> {
    state.messenger.friends().await.map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn get_messages(
    peer_id: String,
    before_id: Option<String>,
    state: State<'_, AppState>,
) -> Result<Vec<ChatMessage>, String> {
    state
        .messenger
        .messages(&peer_id, before_id.as_deref())
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn request_friend(peer_id: String, state: State<'_, AppState>) -> Result<(), String> {
    state
        .messenger
        .request_friend(&peer_id)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn answer_friend(
    peer_id: String,
    accept: bool,
    state: State<'_, AppState>,
) -> Result<(), String> {
    state
        .messenger
        .answer_friend(&peer_id, accept)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn remove_friend(peer_id: String, state: State<'_, AppState>) -> Result<(), String> {
    state
        .messenger
        .remove_friend(&peer_id)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn send_text_message(
    peer_id: String,
    content: String,
    state: State<'_, AppState>,
) -> Result<ChatMessage, String> {
    state
        .messenger
        .send_text(&peer_id, &content)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn send_file_message(
    peer_id: String,
    file_paths: Vec<String>,
    state: State<'_, AppState>,
) -> Result<ChatMessage, String> {
    if file_paths.is_empty() {
        return Err("请先选择文件或文件夹".into());
    }
    state
        .messenger
        .send_file(&peer_id, file_paths.iter().map(PathBuf::from).collect())
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn set_message_download_status(
    message_id: String,
    status: String,
    state: State<'_, AppState>,
) -> Result<(), String> {
    state
        .messenger
        .set_download_status(&message_id, &status)
        .map_err(|e| e.to_string())
}

/// 把主窗口收进托盘（后台继续分享与接收）。
#[tauri::command]
pub fn hide_to_tray(app: AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.hide();
    }
}

/// 完全退出：终止所有连接并结束进程。
#[tauri::command]
pub fn quit_app(app: AppHandle) {
    app.exit(0);
}

/// 删除单条本机聊天记录（规划 §34：第一阶段只删除本机记录，不做跨设备撤回）。
#[tauri::command]
pub async fn delete_message(message_id: String, state: State<'_, AppState>) -> Result<(), String> {
    state
        .messenger
        .delete_message(&message_id)
        .map_err(|e| e.to_string())
}

/// 清空与某个好友的本机聊天记录；对方设备上的记录不受影响。
#[tauri::command]
pub async fn clear_conversation(
    peer_id: String,
    state: State<'_, AppState>,
) -> Result<usize, String> {
    state
        .messenger
        .clear_conversation(&peer_id)
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn target_exists(path: String) -> Result<bool, String> {
    Ok(Path::new(&path).exists())
}

#[tauri::command]
pub async fn directory_exists(path: String) -> Result<bool, String> {
    Ok(Path::new(&path).is_dir())
}

#[tauri::command]
pub async fn create_code_share(
    file_paths: Vec<String>,
    state: State<'_, AppState>,
) -> Result<CodeShareItem, String> {
    let (file_name, size, is_folder, sources) = inspect_sources(&file_paths).await?;
    state
        .code_leases
        .claim_share(file_name, size, is_folder, sources)
        .await
        .map_err(|err| err.to_string())
}

/// 校验并描述来源：支持一次选择多个文件 / 文件夹（多源按容器文件夹发送）。
async fn inspect_sources(
    file_paths: &[String],
) -> Result<(String, u64, bool, Vec<PathBuf>), String> {
    if file_paths.is_empty() {
        return Err("请先选择文件或文件夹".into());
    }
    let mut sources = Vec::with_capacity(file_paths.len());
    for file_path in file_paths {
        let path = Path::new(file_path);
        if !path.is_file() && !path.is_dir() {
            return Err(format!("选定内容不存在：{file_path}"));
        }
        sources.push(path.to_path_buf());
    }
    crate::engine::code_share::describe_sources(&sources)
        .await
        .map(|(name, size, is_folder)| (name, size, is_folder, sources))
        .map_err(|err| err.to_string())
}

#[tauri::command]
pub async fn stop_code_share(
    code: String,
    share_id: String,
    state: State<'_, AppState>,
) -> Result<(), String> {
    state
        .code_leases
        .stop_share(&code, &share_id)
        .await
        .map_err(|err| err.to_string())
}

#[tauri::command]
pub async fn offer_to_device(
    file_paths: Vec<String>,
    peer_id: String,
    state: State<'_, AppState>,
) -> Result<(), String> {
    let peer = state
        .peers
        .read()
        .await
        .get(&peer_id)
        .cloned()
        .ok_or("设备已离线，请刷新后重试")?;
    let peer_ip: IpAddr = peer.ip.parse().map_err(|_| "设备地址无效")?;
    let is_lan = match peer_ip {
        IpAddr::V4(ip) => ip.is_private() || ip.is_loopback(),
        IpAddr::V6(ip) => ip.is_unique_local() || ip.is_loopback(),
    };
    if !is_lan || peer.port != TRANSFER_PORT {
        return Err("设备地址不在局域网内".into());
    }

    let (file_name, size, is_folder, sources) = inspect_sources(&file_paths).await?;
    let item = state
        .code_leases
        .create_direct_share(file_name, size, is_folder, sources)
        .await
        .map_err(|err| err.to_string())?;
    let offer_id = format!("{:032x}", rand::random::<u128>());
    let mut offer = serde_json::json!({
        "offer_id": offer_id,
        "to_node_id": peer.node_id,
        "from_device_name": state.device_name,
        "file_name": item.file_name,
        "file_size": item.file_size,
        "is_folder": item.is_folder,
        "token": item.token,
    });
    protocol::stamp(&mut offer, "DIRECT_OFFER", &state.transport.node_id());
    let offer = offer.to_string();
    let socket = UdpSocket::bind("0.0.0.0:0")
        .await
        .map_err(|err| err.to_string())?;
    state
        .network
        .send_to(
            &socket,
            offer.as_bytes(),
            SocketAddr::new(peer_ip, DISCOVERY_PORT),
        )
        .await
        .map_err(|err| format!("发送邀请失败: {err}"))?;
    let mut buf = [0u8; 1024];
    let deadline = tokio::time::Instant::now() + Duration::from_secs(3);
    loop {
        let (len, remote) = tokio::time::timeout_at(deadline, socket.recv_from(&mut buf))
            .await
            .map_err(|_| "设备未响应，请检查两端是否都已更新并允许局域网通信".to_string())?
            .map_err(|err| err.to_string())?;
        if remote.ip() != peer_ip {
            continue;
        }
        let Ok(value) = serde_json::from_slice::<serde_json::Value>(&buf[..len]) else {
            continue;
        };
        if protocol::message_type(&value) == Some("DIRECT_OFFER_ACK")
            && protocol::string_field(&value, "offer_id") == Some(offer_id.as_str())
        {
            return Ok(());
        }
    }
}

#[tauri::command]
pub async fn resolve_code(
    code: String,
    state: State<'_, AppState>,
) -> Result<ResolvedShare, String> {
    if code.len() != 4 || !code.bytes().all(|byte| byte.is_ascii_digit()) {
        return Err("提取码必须为 4 位数字".into());
    }
    let network = if state.network.physical_lan_only() {
        if state.network.interfaces().is_empty() {
            return Err("未找到已连接的物理局域网网卡；请检查网线或 Wi-Fi 连接".into());
        }
        Some(state.network.as_ref())
    } else {
        None
    };
    let result = resolve_code_on_lan(
        &code,
        &state.peers,
        &state.transport.node_id(),
        DISCOVERY_PORT,
        Some(SocketAddr::from(([255, 255, 255, 255], DISCOVERY_PORT))),
        Duration::from_secs(3),
        network,
    )
    .await;
    match &result {
        Ok(share) => state.diagnostics.record(
            "CODE",
            format!("CODE_QUERY {code} resolved at {}", share.address),
        ),
        Err(err) => state
            .diagnostics
            .record("CODE", format!("CODE_QUERY {code} failed: {err}")),
    }
    result
}

async fn resolve_code_on_lan(
    code: &str,
    peers: &PeerRegistry,
    local_device_id: &str,
    discovery_port: u16,
    broadcast: Option<SocketAddr>,
    wait: Duration,
    network: Option<&NetworkPolicy>,
) -> Result<ResolvedShare, String> {
    let socket = UdpSocket::bind("0.0.0.0:0")
        .await
        .map_err(|err| err.to_string())?;
    socket.set_broadcast(true).map_err(|err| err.to_string())?;
    let request_id = format!("{:032x}", rand::random::<u128>());
    let mut query = serde_json::json!({
        "message_id": request_id,
        "code": code,
    });
    protocol::stamp(&mut query, "CODE_QUERY", local_device_id);
    let query = query.to_string();
    let mut buf = [0u8; 16_384];
    let deadline = tokio::time::sleep(wait);
    tokio::pin!(deadline);
    let mut retry = tokio::time::interval(Duration::from_millis(500));
    retry.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let mut first: Option<ResolvedShare> = None;
    let mut settle_at = None;
    loop {
        let settle_deadline = settle_at.unwrap_or_else(|| tokio::time::Instant::now() + wait);
        let (len, remote) = tokio::select! {
            _ = &mut deadline => {
                return first.ok_or_else(|| "局域网未找到该取件码，请检查双方网络和防火墙".into());
            }
            _ = tokio::time::sleep_until(settle_deadline), if first.is_some() => {
                return Ok(first.expect("已收到分享码响应"));
            }
            _ = retry.tick(), if first.is_none() => {
                let mut targets = HashSet::new();
                if network.is_none() {
                    if let Some(address) = broadcast {
                    targets.insert(address);
                    }
                }
                for peer in peers.read().await.values() {
                    if peer.port != TRANSFER_PORT
                        || !peer.last_seen.is_some_and(|seen| seen.elapsed() < Duration::from_secs(9))
                    {
                        continue;
                    }
                    if let Ok(IpAddr::V4(ip)) = peer.ip.parse() {
                        if (ip.is_private() || ip.is_loopback())
                            && network.is_none_or(|policy| policy.allows_route_to(IpAddr::V4(ip)))
                        {
                            targets.insert(SocketAddr::new(IpAddr::V4(ip), discovery_port));
                        }
                    }
                }
                for target in targets {
                    // One failed interface must not prevent queries over other interfaces.
                    if let Some(policy) = network {
                        let _ = policy.send_to(&socket, query.as_bytes(), target).await;
                    } else {
                        let _ = socket.send_to(query.as_bytes(), target).await;
                    }
                }
                if let Some(policy) = network {
                    policy.send_broadcast(&socket, query.as_bytes(), discovery_port).await;
                }
                continue;
            }
            packet = socket.recv_from(&mut buf) => packet.map_err(|err| err.to_string())?,
        };
        let is_lan_source = match remote.ip() {
            std::net::IpAddr::V4(ip) => ip.is_private() || ip.is_loopback(),
            std::net::IpAddr::V6(ip) => ip.is_unique_local() || ip.is_loopback(),
        };
        if !is_lan_source || network.is_some_and(|policy| !policy.allows_route_to(remote.ip())) {
            continue;
        }
        let Ok(value) = serde_json::from_slice::<serde_json::Value>(&buf[..len]) else {
            continue;
        };
        if protocol::message_type(&value) != Some("CODE_RESPONSE")
            || !protocol::is_supported(&value)
            || protocol::string_field(&value, "request_id") != Some(request_id.as_str())
            || protocol::string_field(&value, "code") != Some(code)
        {
            continue;
        }
        let Some(token) = protocol::string_field(&value, "token") else {
            continue;
        };
        if token.len() != 32 || !token.bytes().all(|byte| byte.is_ascii_hexdigit()) {
            continue;
        }
        let Some(file_size) = protocol::u64_field(&value, "file_size") else {
            continue;
        };
        let Some(is_folder) = protocol::bool_field(&value, "is_folder") else {
            continue;
        };
        let raw_name = protocol::string_field(&value, "file_name").unwrap_or("received_file");
        let file_name = Path::new(raw_name)
            .file_name()
            .and_then(|name| name.to_str())
            .filter(|name| {
                !name.is_empty()
                    && *name != "."
                    && *name != ".."
                    && !name.ends_with(' ')
                    && !name.ends_with('.')
                    && !name.chars().any(|ch| {
                        matches!(
                            ch,
                            '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|' | '\0'
                        )
                    })
            })
            .unwrap_or("received_file")
            .to_string();
        let resolved = ResolvedShare {
            token: token.to_string(),
            file_name,
            file_size,
            is_folder,
            address: SocketAddr::new(remote.ip(), TRANSFER_PORT).to_string(),
        };
        if let Some(previous) = &first {
            if previous.address != resolved.address || previous.token != resolved.token {
                return Err("局域网存在相同分享码的多个发送端，请让发送方重新生成分享码".into());
            }
        } else {
            first = Some(resolved);
            settle_at = Some(tokio::time::Instant::now() + Duration::from_millis(200));
        }
    }
}

#[cfg(test)]
mod code_query_tests {
    use super::*;
    use std::collections::HashMap;
    use tokio::sync::RwLock;

    #[tokio::test]
    async fn retries_a_known_peer_when_first_query_is_lost() {
        let responder = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let port = responder.local_addr().unwrap().port();
        let peers = Arc::new(RwLock::new(HashMap::from([(
            "peer".to_string(),
            PeerDevice {
                node_id: "peer".into(),
                device_name: "Test".into(),
                ip: "127.0.0.1".into(),
                port: TRANSFER_PORT,
                protocol_version: Some(protocol::PROTOCOL_VERSION),
                last_seen: Some(std::time::Instant::now()),
            },
        )])));
        let reply = tokio::spawn(async move {
            let mut buf = [0u8; 1024];
            for attempt in 0..2 {
                let (len, remote) = responder.recv_from(&mut buf).await.unwrap();
                let query: serde_json::Value = serde_json::from_slice(&buf[..len]).unwrap();
                assert_eq!(query["type"], "CODE_QUERY");
                assert_eq!(query["code"], "0427");
                // Simulate the first UDP datagram being lost.
                if attempt == 1 {
                    let response = serde_json::json!({
                        "type": "CODE_RESPONSE", "protocol_version": 2,
                        "request_id": query["message_id"],
                        "code": "0427", "token": "a".repeat(32),
                        "file_name": "sample.txt", "file_size": 9, "is_folder": false
                    })
                    .to_string();
                    responder
                        .send_to(response.as_bytes(), remote)
                        .await
                        .unwrap();
                }
            }
        });
        let found = resolve_code_on_lan(
            "0427",
            &peers,
            "test-device",
            port,
            None,
            Duration::from_secs(2),
            None,
        )
        .await
        .unwrap();
        assert_eq!(found.file_name, "sample.txt");
        assert_eq!(found.address, format!("127.0.0.1:{TRANSFER_PORT}"));
        reply.await.unwrap();
    }

    #[tokio::test]
    async fn rejects_two_different_owners_for_one_code() {
        let responder = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let port = responder.local_addr().unwrap().port();
        let peers = Arc::new(RwLock::new(HashMap::from([(
            "peer".into(),
            PeerDevice {
                node_id: "peer".into(),
                device_name: "Test".into(),
                ip: "127.0.0.1".into(),
                port: TRANSFER_PORT,
                protocol_version: Some(protocol::PROTOCOL_VERSION),
                last_seen: Some(std::time::Instant::now()),
            },
        )])));
        let reply = tokio::spawn(async move {
            let mut buf = [0u8; 1024];
            let (len, remote) = responder.recv_from(&mut buf).await.unwrap();
            let query: serde_json::Value = serde_json::from_slice(&buf[..len]).unwrap();
            for token in ["a".repeat(32), "b".repeat(32)] {
                let response = serde_json::json!({
                    "type": "CODE_RESPONSE", "protocol_version": 2,
                    "request_id": query["message_id"], "code": "0427",
                    "token": token, "file_name": "sample.txt", "file_size": 9,
                    "is_folder": false
                })
                .to_string();
                responder
                    .send_to(response.as_bytes(), remote)
                    .await
                    .unwrap();
            }
        });
        let result = resolve_code_on_lan(
            "0427",
            &peers,
            "test-device",
            port,
            None,
            Duration::from_secs(2),
            None,
        )
        .await;
        assert!(result.err().unwrap().contains("多个发送端"));
        reply.await.unwrap();
    }
}

#[tauri::command]
pub async fn start_download(
    app: AppHandle,
    share: ResolvedShare,
    save_path: String,
    overwrite: bool,
    state: State<'_, AppState>,
) -> Result<String, String> {
    let target = PathBuf::from(save_path);
    if target.is_dir() && !share.is_folder {
        return Err("保存路径必须是文件，不能是目录".into());
    }
    if target.is_file() && share.is_folder {
        return Err("保存路径必须是文件夹，不能是文件".into());
    }
    if target.exists() && !overwrite {
        return Err("目标文件已存在，请先确认是否覆盖".into());
    }
    let address: SocketAddr = share
        .address
        .parse()
        .map_err(|_| "发送端地址无效".to_string())?;
    let is_lan = match address.ip() {
        IpAddr::V4(ip) => ip.is_private() || ip.is_loopback(),
        IpAddr::V6(ip) => ip.is_unique_local() || ip.is_loopback(),
    };
    if !is_lan || address.port() != TRANSFER_PORT {
        return Err("发送端地址不在局域网内".into());
    }
    if share.token.len() != 32 || !share.token.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return Err("分享令牌无效".into());
    }

    let task_id = format!("{:032x}", rand::random::<u128>());
    let task = task_id.clone();
    let network = state.network.clone();
    tokio::spawn(async move {
        let _ = download_stream(
            app,
            task,
            share.file_name,
            address,
            share.token,
            target,
            share.file_size,
            share.is_folder,
            overwrite,
            network,
        )
        .await;
    });
    Ok(task_id)
}
