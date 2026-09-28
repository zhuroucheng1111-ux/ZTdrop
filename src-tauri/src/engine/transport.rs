use crate::engine::code_share::CodeRegistry;
use crate::engine::diagnostics::{Diagnostics, LogFields};
use crate::engine::discovery::PeerRegistry;
use crate::engine::network::NetworkPolicy;
use crate::engine::transfer::serve_connection;
use anyhow::{Context, Result};
use std::net::IpAddr;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use tauri::{AppHandle, Emitter};
use tokio::net::TcpListener;
use tokio::sync::Semaphore;

pub const TRANSFER_PORT: u16 = 52111;
/// 同时进行的 TCP 发送连接上限。超过上限的新连接会被立即关闭并记录日志，
/// 避免多设备并发时把内存和磁盘 IO 拖垮。
pub const MAX_CONCURRENT_TRANSFERS: usize = 8;

pub struct TransportEngine {
    pub listen_port: u16,
    node_id: String,
}

impl TransportEngine {
    pub async fn init(
        shares: CodeRegistry,
        node_id: String,
        app: AppHandle,
        peers: PeerRegistry,
        diagnostics: Arc<Diagnostics>,
        active_transfers: Arc<AtomicUsize>,
        network: Arc<NetworkPolicy>,
    ) -> Result<Self> {
        let listener = TcpListener::bind(("0.0.0.0", TRANSFER_PORT))
            .await
            .context("无法启动局域网直传服务，请检查 TCP 52111 是否被占用")?;

        let slots = Arc::new(Semaphore::new(MAX_CONCURRENT_TRANSFERS));
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
                let is_lan = match remote.ip() {
                    IpAddr::V4(ip) => ip.is_private() || ip.is_loopback(),
                    IpAddr::V6(ip) => ip.is_unique_local() || ip.is_loopback(),
                };
                if is_lan {
                    let registry = shares.clone();
                    let app = app.clone();
                    let peer_ip = remote.ip().to_string();
                    let Ok(permit) = slots.clone().try_acquire_owned() else {
                        diagnostics.record(
                            "TRANSFER",
                            format!("拒绝 {peer_ip}：并发传输已达上限 {MAX_CONCURRENT_TRANSFERS}"),
                        );
                        drop(stream);
                        continue;
                    };
                    let peer_name = peers
                        .read()
                        .await
                        .values()
                        .find(|peer| peer.ip == peer_ip)
                        .map(|peer| peer.device_name.clone())
                        .unwrap_or_else(|| peer_ip.clone());
                    let diagnostics = diagnostics.clone();
                    let active = active_transfers.clone();
                    let log_ip = peer_ip.clone();
                    tokio::spawn(async move {
                        let _permit = permit;
                        active.fetch_add(1, Ordering::Relaxed);
                        diagnostics.record_event(
                            "TRANSFER",
                            format!("TCP connection from {peer_ip}"),
                            LogFields {
                                peer_ip: Some(log_ip),
                                ..LogFields::default()
                            },
                        );
                        let result = serve_connection(
                            stream,
                            registry,
                            peer_name,
                            peer_ip.clone(),
                            |progress| {
                                let _ = app.emit("sender-progress", progress);
                            },
                        )
                        .await;
                        if let Err(err) = result {
                            diagnostics.record("TRANSFER", format!("TCP {peer_ip}: {err:#}"));
                        }
                        active.fetch_sub(1, Ordering::Relaxed);
                    });
                }
            }
        });

        Ok(Self {
            listen_port: TRANSFER_PORT,
            node_id,
        })
    }

    pub fn node_id(&self) -> String {
        self.node_id.clone()
    }
}
