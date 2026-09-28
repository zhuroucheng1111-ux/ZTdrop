use crate::engine::code_lease::{CodeLeaseManager, CodePacket};
use crate::engine::code_share::{CodeRegistry, CodeShareItem};
use crate::engine::diagnostics::Diagnostics;
use crate::engine::messenger::Messenger;
use crate::engine::network::NetworkPolicy;
use crate::engine::protocol;
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use std::net::{IpAddr, SocketAddr};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tauri::{AppHandle, Emitter};
use tokio::net::UdpSocket;
use tokio::sync::RwLock;

pub const DISCOVERY_PORT: u16 = 52110;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PeerDevice {
    pub node_id: String,
    pub device_name: String,
    pub ip: String,
    pub port: u16,
    /// 对端最近上报的协议版本；`None` 表示还没收到带版本号的报文。
    #[serde(default)]
    pub protocol_version: Option<u64>,
    #[serde(skip)]
    pub last_seen: Option<Instant>,
}

pub type PeerRegistry = Arc<RwLock<HashMap<String, PeerDevice>>>;

fn heartbeat_targets(
    peers: &HashMap<String, PeerDevice>,
    network: &NetworkPolicy,
) -> HashSet<SocketAddr> {
    let mut targets = HashSet::new();
    for peer in peers.values() {
        if peer.port != crate::engine::transport::TRANSFER_PORT {
            continue;
        }
        if let Ok(IpAddr::V4(ip)) = peer.ip.parse::<IpAddr>() {
            if (ip.is_private() || ip.is_loopback()) && network.allows_route_to(IpAddr::V4(ip)) {
                targets.insert(SocketAddr::new(IpAddr::V4(ip), DISCOVERY_PORT));
            }
        }
    }
    targets
}

fn is_lan_address(address: std::net::IpAddr) -> bool {
    match address {
        std::net::IpAddr::V4(ip) => ip.is_private() || ip.is_loopback(),
        std::net::IpAddr::V6(ip) => ip.is_unique_local() || ip.is_loopback(),
    }
}

pub async fn start_discovery_service(
    app: AppHandle,
    my_node_id: String,
    device_name: String,
    quic_port: u16,
    peers: PeerRegistry,
    shares: CodeRegistry,
    socket: Arc<UdpSocket>,
    leases: Arc<CodeLeaseManager>,
    messenger: Arc<Messenger>,
    diagnostics: Arc<Diagnostics>,
    network: Arc<NetworkPolicy>,
) -> anyhow::Result<()> {
    // One socket owns the signaling port for presence, code leases and code lookups.
    let send_sock = socket.clone();
    let heartbeat_peers = peers.clone();
    let heartbeat_network = network.clone();
    let own_id = my_node_id.clone();
    tokio::spawn(async move {
        loop {
            let mut heartbeat = serde_json::json!({
                "device_name": device_name,
                "port": quic_port,
                "node_id": own_id,
            });
            protocol::stamp(&mut heartbeat, "HEARTBEAT", &own_id);
            let heartbeat = heartbeat.to_string();
            let targets = {
                let known = heartbeat_peers.read().await;
                heartbeat_targets(&*known, &heartbeat_network)
            };
            for target in targets {
                let _ = heartbeat_network
                    .send_to(&send_sock, heartbeat.as_bytes(), target)
                    .await;
            }
            heartbeat_network
                .send_broadcast(&send_sock, heartbeat.as_bytes(), DISCOVERY_PORT)
                .await;
            tokio::time::sleep(Duration::from_secs(3)).await;
        }
    });

    let recv_sock = socket.clone();
    let recv_peers = peers.clone();
    let recv_network = network.clone();
    tokio::spawn(async move {
        let mut buf = [0u8; 4096];
        loop {
            let Ok((len, remote_addr)) = recv_sock.recv_from(&mut buf).await else {
                continue;
            };
            if !recv_network.allows_route_to(remote_addr.ip()) {
                continue;
            }
            let packet = &buf[..len];
            let Ok(value) = serde_json::from_slice::<serde_json::Value>(packet) else {
                continue;
            };
            if value
                .get("type")
                .and_then(|v| v.as_str())
                .is_some_and(|kind| {
                    matches!(
                        kind,
                        "FRIEND_REQUEST"
                            | "FRIEND_ACCEPT"
                            | "FRIEND_REJECT"
                            | "FRIEND_REMOVE"
                            | "CHAT_MESSAGE"
                            | "CHAT_ACK"
                    )
                })
            {
                if is_lan_address(remote_addr.ip()) {
                    messenger.handle_packet(&value, remote_addr).await;
                }
                continue;
            }
            // PING/PONG：轻量连通性探测，用于诊断页与排查“设备在线但不通”。
            if protocol::message_type(&value) == Some("PING") {
                if !is_lan_address(remote_addr.ip()) || !protocol::is_supported(&value) {
                    continue;
                }
                let mut pong = serde_json::json!({
                    "reply_to": protocol::string_field(&value, "message_id"),
                });
                protocol::stamp(&mut pong, "PONG", &my_node_id);
                let _ = recv_network
                    .send_to(&recv_sock, pong.to_string().as_bytes(), remote_addr)
                    .await;
                continue;
            }
            if value.get("type").and_then(|v| v.as_str()) == Some("CODE_QUERY") {
                if !is_lan_address(remote_addr.ip()) || !protocol::is_supported(&value) {
                    continue;
                }
                let Some(code) = protocol::string_field(&value, "code") else {
                    continue;
                };
                let Some(request_id) = protocol::string_field(&value, "message_id") else {
                    continue;
                };
                if code.len() != 4
                    || !code.bytes().all(|byte| byte.is_ascii_digit())
                    || request_id.len() != 32
                    || !request_id.bytes().all(|byte| byte.is_ascii_hexdigit())
                {
                    continue;
                }
                let item = shares.read().await.get(code).cloned();
                if let Some(item) = item.filter(CodeShareItem::is_active) {
                    let mut response = serde_json::json!({
                        "request_id": request_id,
                        "code": item.code,
                        "device_id": my_node_id,
                        "share_id": item.share_id,
                        "claim_id": item.claim_id,
                        "file_name": item.file_name,
                        "file_size": item.file_size,
                        "is_folder": item.is_folder,
                        "token": item.token,
                    });
                    protocol::stamp(&mut response, "CODE_RESPONSE", &my_node_id);
                    let response = response.to_string();
                    let _ = recv_network
                        .send_to(&recv_sock, response.as_bytes(), remote_addr)
                        .await;
                    diagnostics.record(
                        "CODE",
                        format!("CODE_QUERY {code} from {}", remote_addr.ip()),
                    );
                }
                continue;
            }
            if value
                .get("type")
                .and_then(|v| v.as_str())
                .is_some_and(|kind| {
                    matches!(
                        kind,
                        "CODE_CLAIM"
                            | "CODE_CONFLICT"
                            | "CODE_ACTIVE"
                            | "CODE_HEARTBEAT"
                            | "CODE_RELEASE"
                    )
                })
            {
                if is_lan_address(remote_addr.ip()) {
                    if let Ok(message) = serde_json::from_value::<CodePacket>(value.clone()) {
                        leases.handle_packet(message, remote_addr).await;
                    }
                }
                continue;
            }
            if value.get("type").and_then(|v| v.as_str()) == Some("DIRECT_OFFER") {
                if !is_lan_address(remote_addr.ip())
                    || !protocol::is_supported(&value)
                    || protocol::string_field(&value, "to_node_id") != Some(my_node_id.as_str())
                {
                    continue;
                }
                let Some(offer_id) = protocol::string_field(&value, "offer_id") else {
                    continue;
                };
                let Some(token) = protocol::string_field(&value, "token") else {
                    continue;
                };
                if offer_id.len() != 32
                    || !offer_id.bytes().all(|ch| ch.is_ascii_hexdigit())
                    || token.len() != 32
                    || !token.bytes().all(|ch| ch.is_ascii_hexdigit())
                {
                    continue;
                }
                let Some(file_size) = protocol::u64_field(&value, "file_size") else {
                    continue;
                };
                let Some(is_folder) = protocol::bool_field(&value, "is_folder") else {
                    continue;
                };
                let Some(raw_name) = protocol::string_field(&value, "file_name") else {
                    continue;
                };
                let file_name = std::path::Path::new(raw_name)
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
                    });
                let Some(file_name) = file_name else {
                    continue;
                };
                let offer = serde_json::json!({
                    "offer_id": offer_id,
                    "from_device_name": protocol::string_field(&value, "from_device_name").unwrap_or("局域网设备"),
                    "file_name": file_name,
                    "file_size": file_size,
                    "is_folder": is_folder,
                    "token": token,
                    "address": std::net::SocketAddr::new(remote_addr.ip(), crate::engine::transport::TRANSFER_PORT).to_string(),
                });
                let _ = app.emit("direct-offer", offer);
                diagnostics.record(
                    "DISCOVERY",
                    format!("DIRECT_OFFER from {}", remote_addr.ip()),
                );
                let mut ack = serde_json::json!({ "offer_id": offer_id });
                protocol::stamp(&mut ack, "DIRECT_OFFER_ACK", &my_node_id);
                let ack = ack.to_string();
                let _ = recv_network
                    .send_to(&recv_sock, ack.as_bytes(), remote_addr)
                    .await;
                continue;
            }
            if value.get("type").and_then(|v| v.as_str()) != Some("HEARTBEAT") {
                continue;
            }
            if !protocol::is_supported(&value) {
                continue;
            }
            let Some(peer_id) = protocol::string_field(&value, "node_id") else {
                continue;
            };
            if peer_id.is_empty() || peer_id == my_node_id {
                continue;
            }
            let Some(peer_port) =
                protocol::u64_field(&value, "port").and_then(|value| u16::try_from(value).ok())
            else {
                continue;
            };
            let peer_name = protocol::string_field(&value, "device_name")
                .unwrap_or("Nearby Device")
                .to_string();
            let peer_protocol = protocol::version_of(&value);
            let peer_protocol = peer_protocol.unwrap_or(protocol::PROTOCOL_VERSION);
            let previous = recv_peers.write().await.insert(
                peer_id.to_string(),
                PeerDevice {
                    node_id: peer_id.to_string(),
                    device_name: peer_name.clone(),
                    ip: remote_addr.ip().to_string(),
                    port: peer_port,
                    protocol_version: Some(peer_protocol),
                    last_seen: Some(Instant::now()),
                },
            );
            if previous
                .as_ref()
                .is_none_or(|old| old.ip != remote_addr.ip().to_string())
            {
                diagnostics.record(
                    "DISCOVERY",
                    format!("Peer {peer_name} at {}", remote_addr.ip()),
                );
            }
        }
    });

    tokio::spawn(async move {
        loop {
            tokio::time::sleep(Duration::from_secs(4)).await;
            peers.write().await.retain(|_, peer| {
                peer.last_seen
                    .is_some_and(|seen| seen.elapsed() < Duration::from_secs(9))
            });
        }
    });
    Ok(())
}
