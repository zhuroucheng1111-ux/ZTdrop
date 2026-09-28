use crate::engine::code_share::{register_claimed_code, CodeRegistry, CodeShareItem};
use crate::engine::diagnostics::Diagnostics;
use crate::engine::discovery::{PeerRegistry, DISCOVERY_PORT};
use crate::engine::network::NetworkPolicy;
use crate::engine::protocol;
use crate::engine::transport::TRANSFER_PORT;
use anyhow::{bail, Result};
use rand::Rng;
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use std::net::{IpAddr, SocketAddr};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tauri::{AppHandle, Emitter};
use tokio::net::UdpSocket;
use tokio::sync::Mutex;

const LEASE_TIMEOUT: Duration = Duration::from_secs(20);
const CLAIM_WINDOW: Duration = Duration::from_millis(350);

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CodePacket {
    #[serde(rename = "type")]
    kind: String,
    protocol_version: u8,
    message_id: String,
    code: String,
    device_id: String,
    share_id: String,
    claim_id: String,
    #[serde(default)]
    target_claim_id: Option<String>,
}

impl CodePacket {
    fn new(kind: &str, code: &str, device_id: &str, share_id: &str, claim_id: &str) -> Self {
        Self {
            kind: kind.into(),
            protocol_version: protocol::PROTOCOL_VERSION as u8,
            message_id: random_id(),
            code: code.into(),
            device_id: device_id.into(),
            share_id: share_id.into(),
            claim_id: claim_id.into(),
            target_claim_id: None,
        }
    }

    pub fn valid(&self) -> bool {
        matches!(self.protocol_version as u64, protocol::PROTOCOL_VERSION)
            && self.code.len() == 4
            && self.code.bytes().all(|byte| byte.is_ascii_digit())
            && [
                &self.device_id,
                &self.share_id,
                &self.claim_id,
                &self.message_id,
            ]
            .iter()
            .all(|id| valid_id(id))
    }
}

fn valid_id(id: &str) -> bool {
    id.len() == 32 && id.bytes().all(|byte| byte.is_ascii_hexdigit())
}

fn random_id() -> String {
    format!("{:032x}", rand::random::<u128>())
}

#[derive(Clone)]
struct Lease {
    device_id: String,
    share_id: String,
    claim_id: String,
    last_seen: Instant,
    local: bool,
}

impl Lease {
    fn matches(&self, packet: &CodePacket) -> bool {
        self.device_id == packet.device_id
            && self.share_id == packet.share_id
            && self.claim_id == packet.claim_id
    }

    fn wins_over(&self, other: &Lease) -> bool {
        (&self.claim_id, &self.device_id) > (&other.claim_id, &other.device_id)
    }
}

struct Pending {
    share_id: String,
    claim_id: String,
    lost: bool,
    created_at: Instant,
}

#[derive(Default)]
struct LeaseState {
    active: HashMap<String, Lease>,
    pending: HashMap<String, Pending>,
}

impl LeaseState {
    fn prune_remote(&mut self) {
        self.active
            .retain(|_, lease| lease.local || lease.last_seen.elapsed() < LEASE_TIMEOUT);
        self.pending
            .retain(|_, claim| claim.created_at.elapsed() < Duration::from_secs(2));
    }

    fn release_matching(&mut self, packet: &CodePacket) -> bool {
        if self
            .active
            .get(&packet.code)
            .is_some_and(|lease| !lease.local && lease.matches(packet))
        {
            self.active.remove(&packet.code);
            return true;
        }
        false
    }
}

pub struct CodeLeaseManager {
    socket: Arc<UdpSocket>,
    peers: PeerRegistry,
    shares: CodeRegistry,
    device_id: String,
    app: AppHandle,
    diagnostics: Arc<Diagnostics>,
    network: Arc<NetworkPolicy>,
    state: Mutex<LeaseState>,
}

#[derive(Serialize)]
pub struct CodeLeaseView {
    pub code: String,
    pub device_id: String,
    pub local: bool,
    pub remaining_seconds: u64,
}

impl CodeLeaseManager {
    pub fn new(
        socket: Arc<UdpSocket>,
        peers: PeerRegistry,
        shares: CodeRegistry,
        device_id: String,
        app: AppHandle,
        diagnostics: Arc<Diagnostics>,
        network: Arc<NetworkPolicy>,
    ) -> Self {
        Self {
            socket,
            peers,
            shares,
            device_id,
            app,
            diagnostics,
            network,
            state: Mutex::new(LeaseState::default()),
        }
    }

    async fn send_to_lan(&self, packet: &CodePacket) {
        let Ok(mut value) = serde_json::to_value(packet) else {
            return;
        };
        protocol::stamp(&mut value, &packet.kind, &packet.device_id);
        let Ok(bytes) = serde_json::to_vec(&value) else {
            return;
        };
        let mut targets = HashSet::new();
        for peer in self.peers.read().await.values() {
            if peer.port != TRANSFER_PORT {
                continue;
            }
            if let Ok(IpAddr::V4(ip)) = peer.ip.parse() {
                if (ip.is_private() || ip.is_loopback())
                    && self.network.allows_route_to(IpAddr::V4(ip))
                {
                    targets.insert(SocketAddr::new(IpAddr::V4(ip), DISCOVERY_PORT));
                }
            }
        }
        for target in targets {
            let _ = self.network.send_to(&self.socket, &bytes, target).await;
        }
        self.network
            .send_broadcast(&self.socket, &bytes, DISCOVERY_PORT)
            .await;
    }

    pub async fn claim_share(
        &self,
        file_name: String,
        file_size: u64,
        is_folder: bool,
        sources: Vec<PathBuf>,
    ) -> Result<CodeShareItem> {
        for _ in 0..40 {
            let code = format!("{:04}", rand::thread_rng().gen_range(0..10_000));
            let share_id = random_id();
            let claim_id = random_id();
            let mut state = self.state.lock().await;
            state.prune_remote();
            if state.active.contains_key(&code)
                || state.pending.contains_key(&code)
                || self.shares.read().await.contains_key(&code)
            {
                continue;
            }
            state.pending.insert(
                code.clone(),
                Pending {
                    share_id: share_id.clone(),
                    claim_id: claim_id.clone(),
                    lost: false,
                    created_at: Instant::now(),
                },
            );
            drop(state);

            let claim = CodePacket::new("CODE_CLAIM", &code, &self.device_id, &share_id, &claim_id);
            self.diagnostics
                .record("CODE", format!("CODE_CLAIM {code}"));
            self.send_to_lan(&claim).await;
            tokio::time::sleep(Duration::from_millis(150)).await;
            self.send_to_lan(&claim).await;
            tokio::time::sleep(CLAIM_WINDOW - Duration::from_millis(150)).await;

            let mut state = self.state.lock().await;
            let pending = state.pending.remove(&code);
            if !pending.is_some_and(|pending| !pending.lost && pending.claim_id == claim_id)
                || state.active.contains_key(&code)
            {
                self.diagnostics
                    .record("CODE", format!("CODE_CLAIM conflict {code}"));
                continue;
            }
            let item = register_claimed_code(
                self.shares.clone(),
                code.clone(),
                share_id.clone(),
                claim_id.clone(),
                file_name.clone(),
                file_size,
                is_folder,
                sources.clone(),
            )
            .await?;
            state.active.insert(
                code.clone(),
                Lease {
                    device_id: self.device_id.clone(),
                    share_id: share_id.clone(),
                    claim_id: claim_id.clone(),
                    last_seen: Instant::now(),
                    local: true,
                },
            );
            drop(state);
            self.send_to_lan(&CodePacket::new(
                "CODE_ACTIVE",
                &code,
                &self.device_id,
                &share_id,
                &claim_id,
            ))
            .await;
            if !self
                .shares
                .read()
                .await
                .get(&code)
                .is_some_and(|current| current.share_id == share_id && current.is_active())
            {
                self.diagnostics
                    .record("CODE", format!("CODE_ACTIVE lost {code}"));
                continue;
            }
            self.diagnostics
                .record("CODE", format!("CODE_ACTIVE {code}"));
            return Ok(item);
        }
        bail!("分享码冲突过多，请稍后重试")
    }

    pub async fn create_direct_share(
        &self,
        file_name: String,
        file_size: u64,
        is_folder: bool,
        sources: Vec<PathBuf>,
    ) -> Result<CodeShareItem> {
        let share_id = random_id();
        let item = register_claimed_code(
            self.shares.clone(),
            format!("direct:{share_id}"),
            share_id,
            random_id(),
            file_name,
            file_size,
            is_folder,
            sources,
        )
        .await?;
        self.shares
            .write()
            .await
            .get_mut(&item.code)
            .unwrap()
            .expire_seconds = u64::MAX;
        Ok(item)
    }

    pub async fn restore_direct_share(
        &self,
        share_id: String,
        token: String,
        file_name: String,
        file_size: u64,
        is_folder: bool,
        sources: Vec<PathBuf>,
    ) -> Result<()> {
        let item = register_claimed_code(
            self.shares.clone(),
            format!("direct:{share_id}"),
            share_id,
            random_id(),
            file_name,
            file_size,
            is_folder,
            sources,
        )
        .await?;
        if let Some(stored) = self.shares.write().await.get_mut(&item.code) {
            stored.token = token;
            stored.expire_seconds = u64::MAX;
        }
        Ok(())
    }

    pub async fn stop_share(&self, code: &str, share_id: &str) -> Result<()> {
        let mut state = self.state.lock().await;
        let mut shares = self.shares.write().await;
        let item = shares
            .get(code)
            .filter(|item| item.share_id == share_id)
            .cloned()
            .ok_or_else(|| anyhow::anyhow!("分享已结束或不存在"))?;
        item.stop();
        shares.remove(code);
        if state
            .active
            .get(code)
            .is_some_and(|lease| lease.local && lease.share_id == share_id)
        {
            state.active.remove(code);
        }
        drop(shares);
        drop(state);
        self.send_to_lan(&CodePacket::new(
            "CODE_RELEASE",
            code,
            &self.device_id,
            share_id,
            &item.claim_id,
        ))
        .await;
        self.diagnostics
            .record("CODE", format!("CODE_RELEASE {code}"));
        Ok(())
    }

    pub async fn snapshot(&self) -> Vec<CodeLeaseView> {
        let mut state = self.state.lock().await;
        state.prune_remote();
        let shares = self.shares.read().await;
        let mut leases: Vec<_> = state
            .active
            .iter()
            .map(|(code, lease)| {
                let remaining_seconds = if lease.local {
                    shares
                        .get(code)
                        .and_then(|item| {
                            item.created_at.map(|created| {
                                item.expire_seconds
                                    .saturating_sub(created.elapsed().as_secs())
                            })
                        })
                        .unwrap_or(0)
                } else {
                    LEASE_TIMEOUT
                        .saturating_sub(lease.last_seen.elapsed())
                        .as_secs()
                };
                CodeLeaseView {
                    code: code.clone(),
                    device_id: lease.device_id.clone(),
                    local: lease.local,
                    remaining_seconds,
                }
            })
            .collect();
        leases.sort_by(|a, b| a.code.cmp(&b.code));
        leases
    }

    pub async fn handle_packet(&self, packet: CodePacket, remote: SocketAddr) {
        if !packet.valid() || packet.device_id == self.device_id {
            return;
        }
        let mut response = None;
        let mut invalidated = None;
        let mut state = self.state.lock().await;
        state.prune_remote();
        match packet.kind.as_str() {
            "CODE_CLAIM" => {
                self.diagnostics.record(
                    "CODE",
                    format!("CODE_CLAIM {} from {}", packet.code, remote.ip()),
                );
                if let Some(existing) = state.active.get(&packet.code) {
                    response = Some(self.conflict(&packet, existing));
                } else if let Some(pending) = state.pending.get_mut(&packet.code) {
                    if (&pending.claim_id, &self.device_id) > (&packet.claim_id, &packet.device_id)
                    {
                        response = Some(CodePacket {
                            target_claim_id: Some(packet.claim_id.clone()),
                            ..CodePacket::new(
                                "CODE_CONFLICT",
                                &packet.code,
                                &self.device_id,
                                &pending.share_id,
                                &pending.claim_id,
                            )
                        });
                    } else {
                        pending.lost = true;
                    }
                }
            }
            "CODE_CONFLICT" => {
                self.diagnostics.record(
                    "CODE",
                    format!("CODE_CONFLICT {} from {}", packet.code, remote.ip()),
                );
                if let Some(pending) = state.pending.get_mut(&packet.code) {
                    if packet.target_claim_id.as_deref() == Some(&pending.claim_id) {
                        pending.lost = true;
                    }
                }
            }
            "CODE_ACTIVE" | "CODE_HEARTBEAT" => {
                if packet.kind == "CODE_ACTIVE" {
                    self.diagnostics.record(
                        "CODE",
                        format!("CODE_ACTIVE {} from {}", packet.code, remote.ip()),
                    );
                }
                if let Some(pending) = state.pending.get_mut(&packet.code) {
                    pending.lost = true;
                }
                let incoming = Lease {
                    device_id: packet.device_id.clone(),
                    share_id: packet.share_id.clone(),
                    claim_id: packet.claim_id.clone(),
                    last_seen: Instant::now(),
                    local: false,
                };
                match state.active.get_mut(&packet.code) {
                    Some(existing) if existing.matches(&packet) => {
                        existing.last_seen = Instant::now()
                    }
                    Some(existing) if existing.wins_over(&incoming) => {
                        if existing.local {
                            response = Some(CodePacket::new(
                                "CODE_ACTIVE",
                                &packet.code,
                                &self.device_id,
                                &existing.share_id,
                                &existing.claim_id,
                            ));
                        }
                    }
                    Some(existing) => {
                        if existing.local {
                            invalidated = Some(existing.share_id.clone());
                        }
                        state.active.insert(packet.code.clone(), incoming);
                    }
                    None => {
                        state.active.insert(packet.code.clone(), incoming);
                    }
                }
                if let Some(share_id) = &invalidated {
                    let mut shares = self.shares.write().await;
                    if shares
                        .get(&packet.code)
                        .is_some_and(|item| &item.share_id == share_id)
                    {
                        if let Some(item) = shares.remove(&packet.code) {
                            item.stop();
                        }
                    }
                }
            }
            "CODE_RELEASE" => {
                state.release_matching(&packet);
                self.diagnostics.record(
                    "CODE",
                    format!("CODE_RELEASE {} from {}", packet.code, remote.ip()),
                );
            }
            _ => {}
        }
        drop(state);
        if let Some(share_id) = invalidated {
            let _ = self.app.emit(
                "share-invalidated",
                serde_json::json!({"code": packet.code, "share_id": share_id, "reason": "分享码冲突，已停止当前分享"}),
            );
        }
        if let Some(response) = response {
            if let Ok(bytes) = serde_json::to_vec(&response) {
                let _ = self.network.send_to(&self.socket, &bytes, remote).await;
            }
        }
    }

    fn conflict(&self, incoming: &CodePacket, existing: &Lease) -> CodePacket {
        let mut packet = CodePacket::new(
            "CODE_CONFLICT",
            &incoming.code,
            &existing.device_id,
            &existing.share_id,
            &existing.claim_id,
        );
        packet.target_claim_id = Some(incoming.claim_id.clone());
        packet
    }

    pub async fn maintain(self: Arc<Self>) {
        loop {
            tokio::time::sleep(Duration::from_secs(5)).await;
            let mut messages = Vec::new();
            let mut expired = Vec::new();
            let mut state = self.state.lock().await;
            state.prune_remote();
            let shares = self.shares.read().await;
            state.active.retain(|code, lease| {
                if !lease.local {
                    return true;
                }
                let active = shares.get(code).is_some_and(CodeShareItem::is_active);
                messages.push(CodePacket::new(
                    if active {
                        "CODE_HEARTBEAT"
                    } else {
                        "CODE_RELEASE"
                    },
                    code,
                    &self.device_id,
                    &lease.share_id,
                    &lease.claim_id,
                ));
                if !active {
                    expired.push((code.clone(), lease.share_id.clone()));
                }
                active
            });
            drop(shares);
            drop(state);
            for packet in messages {
                self.send_to_lan(&packet).await;
            }
            for (code, share_id) in expired {
                self.diagnostics
                    .record("CODE", format!("Share expired {code}"));
                let _ = self.app.emit(
                    "share-invalidated",
                    serde_json::json!({"code": code, "share_id": share_id, "reason": "分享码已过期"}),
                );
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn lease(device_id: &str, claim_id: &str) -> Lease {
        Lease {
            device_id: device_id.into(),
            share_id: "b".repeat(32),
            claim_id: claim_id.into(),
            last_seen: Instant::now(),
            local: false,
        }
    }

    #[test]
    fn simultaneous_claims_choose_same_winner() {
        let low = lease(&"a".repeat(32), &"1".repeat(32));
        let high = lease(&"b".repeat(32), &"2".repeat(32));
        assert!(high.wins_over(&low));
        assert!(!low.wins_over(&high));
    }

    #[test]
    fn release_only_removes_matching_owner() {
        let mut state = LeaseState::default();
        state
            .active
            .insert("0427".into(), lease(&"a".repeat(32), &"1".repeat(32)));
        let wrong = CodePacket::new(
            "CODE_RELEASE",
            "0427",
            &"a".repeat(32),
            &"b".repeat(32),
            &"2".repeat(32),
        );
        assert!(!state.release_matching(&wrong));
        assert!(state.active.contains_key("0427"));
        let correct = CodePacket::new(
            "CODE_RELEASE",
            "0427",
            &"a".repeat(32),
            &"b".repeat(32),
            &"1".repeat(32),
        );
        assert!(state.release_matching(&correct));
        assert!(!state.active.contains_key("0427"));
    }

    #[test]
    fn expired_remote_lease_is_reclaimed_without_removing_local_share() {
        let mut state = LeaseState::default();
        let mut remote = lease(&"a".repeat(32), &"1".repeat(32));
        remote.last_seen = Instant::now() - LEASE_TIMEOUT - Duration::from_secs(1);
        state.active.insert("0427".into(), remote);
        let mut local = lease(&"b".repeat(32), &"2".repeat(32));
        local.local = true;
        local.last_seen = Instant::now() - LEASE_TIMEOUT - Duration::from_secs(1);
        state.active.insert("0428".into(), local);
        state.prune_remote();
        assert!(!state.active.contains_key("0427"));
        assert!(state.active.contains_key("0428"));
    }
}
