use crate::engine::code_lease::CodeLeaseManager;
use crate::engine::code_share::describe_sources;
use crate::engine::diagnostics::Diagnostics;
use crate::engine::discovery::{PeerRegistry, DISCOVERY_PORT};
use crate::engine::network::NetworkPolicy;
use crate::engine::protocol;
use anyhow::{bail, Context, Result};
use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};
use tauri::{AppHandle, Emitter};
use tokio::net::UdpSocket;

#[derive(Clone, Serialize)]
pub struct FriendView {
    pub device_id: String,
    pub device_name: String,
    pub status: String,
    pub online: bool,
}

#[derive(Clone, Serialize, Deserialize)]
pub struct ChatMessage {
    pub message_id: String,
    pub peer_id: String,
    pub sender_id: String,
    pub kind: String,
    pub content: String,
    pub file_name: Option<String>,
    pub file_size: Option<u64>,
    pub is_folder: bool,
    pub token: Option<String>,
    pub created_at: i64,
    pub download_status: String,
    pub delivery_status: String,
}

pub struct Messenger {
    path: PathBuf,
    device_id: String,
    device_name: String,
    peers: PeerRegistry,
    socket: Arc<UdpSocket>,
    network: Arc<NetworkPolicy>,
    leases: Arc<CodeLeaseManager>,
    app: AppHandle,
    diagnostics: Arc<Diagnostics>,
}

fn now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs() as i64
}

fn valid_id(id: &str) -> bool {
    id.len() == 32 && id.bytes().all(|byte| byte.is_ascii_hexdigit())
}

fn chat_ack(from: &str, to: &str, message_id: &str) -> Value {
    let mut packet = json!({"type":"CHAT_ACK","from":from,"to":to,"message_id":message_id});
    protocol::stamp(&mut packet, "CHAT_ACK", from);
    packet
}

fn messages_page(
    db: &Connection,
    peer_id: &str,
    before_id: Option<&str>,
) -> Result<Vec<ChatMessage>> {
    let mut query = db.prepare(
        "SELECT message_id, peer_id, sender_id, kind, content, file_name,
         file_size, is_folder, token, created_at, download_status, delivery_status FROM messages
         WHERE peer_id=?1 AND (?2 IS NULL OR (created_at, rowid) <
             (SELECT created_at, rowid FROM messages WHERE message_id=?2 AND peer_id=?1))
         ORDER BY created_at DESC, rowid DESC LIMIT 500",
    )?;
    let rows = query.query_map(params![peer_id, before_id], |r| {
        Ok(ChatMessage {
            message_id: r.get(0)?,
            peer_id: r.get(1)?,
            sender_id: r.get(2)?,
            kind: r.get(3)?,
            content: r.get(4)?,
            file_name: r.get(5)?,
            file_size: r.get(6)?,
            is_folder: r.get(7)?,
            token: r.get(8)?,
            created_at: r.get(9)?,
            download_status: r.get(10)?,
            delivery_status: r.get(11)?,
        })
    })?;
    let mut messages = rows.collect::<rusqlite::Result<Vec<_>>>()?;
    messages.reverse();
    Ok(messages)
}

fn safe_name(name: &str) -> bool {
    !name.is_empty()
        && name != "."
        && name != ".."
        && !name.ends_with(' ')
        && !name.ends_with('.')
        && !name.chars().any(|c| {
            matches!(
                c,
                '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|' | '\0'
            )
        })
}

fn message_image_path(
    db: &Connection,
    message_id: &str,
    device_id: &str,
) -> Result<Option<String>> {
    Ok(db.query_row("SELECT COALESCE(mm.local_path,r.file_path) FROM messages m LEFT JOIN message_media mm ON mm.message_id=m.message_id LEFT JOIN resources r ON r.token=m.token AND m.sender_id=?2 WHERE m.message_id=?1 AND m.is_folder=0 AND m.kind='file'", params![message_id, device_id], |r| r.get::<_, Option<String>>(0)).optional()?.flatten())
}

impl Messenger {
    pub fn new(
        directory: &Path,
        device_id: String,
        device_name: String,
        peers: PeerRegistry,
        socket: Arc<UdpSocket>,
        network: Arc<NetworkPolicy>,
        leases: Arc<CodeLeaseManager>,
        app: AppHandle,
        diagnostics: Arc<Diagnostics>,
    ) -> Result<Self> {
        std::fs::create_dir_all(directory)?;
        let this = Self {
            path: directory.join("messages.sqlite3"),
            device_id,
            device_name,
            peers,
            socket,
            network,
            leases,
            app,
            diagnostics,
        };
        this.init_db()?;
        Ok(this)
    }

    fn db(&self) -> Result<Connection> {
        let db = Connection::open(&self.path)?;
        db.busy_timeout(std::time::Duration::from_secs(3))?;
        Ok(db)
    }

    fn init_db(&self) -> Result<()> {
        let db = self.db()?;
        db.execute_batch("PRAGMA journal_mode=WAL;
            CREATE TABLE IF NOT EXISTS friends(device_id TEXT PRIMARY KEY, device_name TEXT NOT NULL, status TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS messages(message_id TEXT PRIMARY KEY, peer_id TEXT NOT NULL, sender_id TEXT NOT NULL,
                kind TEXT NOT NULL, content TEXT NOT NULL, file_name TEXT, file_size INTEGER, is_folder INTEGER NOT NULL DEFAULT 0,
                token TEXT, created_at INTEGER NOT NULL, download_status TEXT NOT NULL DEFAULT 'pending',
                delivery_status TEXT NOT NULL DEFAULT 'pending');
            CREATE TABLE IF NOT EXISTS message_media(message_id TEXT PRIMARY KEY, local_path TEXT NOT NULL);
            CREATE INDEX IF NOT EXISTS messages_peer_time ON messages(peer_id, created_at);
            CREATE TABLE IF NOT EXISTS resources(share_id TEXT PRIMARY KEY, token TEXT NOT NULL, file_path TEXT NOT NULL,
                file_name TEXT NOT NULL, file_size INTEGER NOT NULL, is_folder INTEGER NOT NULL);")?;
        Ok(())
    }

    pub async fn restore_resources(&self) -> Result<()> {
        let db = self.db()?;
        let mut query = db.prepare(
            "SELECT share_id, token, file_path, file_name, file_size, is_folder FROM resources",
        )?;
        let rows = query.query_map([], |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, String>(2)?,
                r.get::<_, String>(3)?,
                r.get::<_, u64>(4)?,
                r.get::<_, bool>(5)?,
            ))
        })?;
        let resources = rows.collect::<rusqlite::Result<Vec<_>>>()?;
        drop(query);
        drop(db);
        for (id, token, paths, name, size, folder) in resources {
            // 多来源用 `|` 连接保存（Windows 路径不允许出现该字符）。
            let sources: Vec<PathBuf> = paths
                .split('|')
                .filter(|part| !part.is_empty())
                .map(PathBuf::from)
                .collect();
            if !sources.is_empty() && sources.iter().all(|source| source.exists()) {
                self.leases
                    .restore_direct_share(id, token, name, size, folder, sources)
                    .await?;
            }
        }
        Ok(())
    }

    fn status(&self, peer_id: &str) -> Result<Option<String>> {
        let db = self.db()?;
        Ok(db
            .query_row(
                "SELECT status FROM friends WHERE device_id=?1",
                [peer_id],
                |r| r.get(0),
            )
            .optional()?)
    }

    pub async fn friends(&self) -> Result<Vec<FriendView>> {
        let stored = {
            let db = self.db()?;
            let mut query = db.prepare(
                "SELECT device_id, device_name, status FROM friends ORDER BY device_name",
            )?;
            let rows = query.query_map([], |r| {
                Ok((
                    r.get::<_, String>(0)?,
                    r.get::<_, String>(1)?,
                    r.get::<_, String>(2)?,
                ))
            })?;
            rows.collect::<rusqlite::Result<Vec<_>>>()?
        };
        let peers = self.peers.read().await;
        Ok(stored
            .into_iter()
            .map(|(device_id, device_name, status)| FriendView {
                online: peers.contains_key(&device_id),
                device_id,
                device_name,
                status,
            })
            .collect())
    }

    pub fn messages(&self, peer_id: &str, before_id: Option<&str>) -> Result<Vec<ChatMessage>> {
        if !valid_id(peer_id) {
            bail!("设备 ID 无效");
        }
        if before_id.is_some_and(|id| !valid_id(id)) {
            bail!("消息 ID 无效");
        }
        let db = self.db()?;
        messages_page(&db, peer_id, before_id)
    }

    async fn send(&self, peer_id: &str, value: &Value) -> Result<()> {
        let peer = self
            .peers
            .read()
            .await
            .get(peer_id)
            .cloned()
            .context("设备已离线")?;
        let ip: std::net::IpAddr = peer.ip.parse()?;
        let address = SocketAddr::new(ip, DISCOVERY_PORT);
        let mut stamped = value.clone();
        if let Some(kind) = protocol::message_type(value) {
            protocol::stamp(&mut stamped, kind, &self.device_id);
        }
        let bytes = serde_json::to_vec(&stamped)?;
        if bytes.len() > 3900 {
            bail!("消息过长");
        }
        for _ in 0..3 {
            self.network.send_to(&self.socket, &bytes, address).await?;
            tokio::time::sleep(std::time::Duration::from_millis(120)).await;
        }
        Ok(())
    }

    pub async fn request_friend(&self, peer_id: &str) -> Result<()> {
        if !valid_id(peer_id) || peer_id == self.device_id {
            bail!("设备 ID 无效");
        }
        let peer = self
            .peers
            .read()
            .await
            .get(peer_id)
            .cloned()
            .context("设备已离线")?;
        if self.status(peer_id)?.as_deref() == Some("accepted") {
            bail!("已经是好友");
        }
        self.db()?.execute("INSERT INTO friends VALUES(?1,?2,'pending_out')
            ON CONFLICT(device_id) DO UPDATE SET device_name=excluded.device_name,status='pending_out'",
            params![peer_id, peer.device_name])?;
        let _ = self
            .send(
                peer_id,
                &json!({"type":"FRIEND_REQUEST","from":self.device_id,"to":peer_id,
            "device_name":self.device_name,"message_id":format!("{:032x}",rand::random::<u128>())}),
            )
            .await;
        Ok(())
    }

    pub async fn answer_friend(&self, peer_id: &str, accept: bool) -> Result<()> {
        if self.status(peer_id)?.as_deref() != Some("pending_in") {
            bail!("没有待处理的好友请求");
        }
        let kind = if accept {
            "FRIEND_ACCEPT"
        } else {
            "FRIEND_REJECT"
        };
        if accept {
            self.db()?.execute(
                "UPDATE friends SET status='accepted' WHERE device_id=?1",
                [peer_id],
            )?;
        } else {
            self.db()?
                .execute("DELETE FROM friends WHERE device_id=?1", [peer_id])?;
        }
        let _ = self
            .send(
                peer_id,
                &json!({"type":kind,"from":self.device_id,"to":peer_id,
            "device_name":self.device_name,"message_id":format!("{:032x}",rand::random::<u128>())}),
            )
            .await;
        let _ = self.app.emit("messenger-changed", ());
        Ok(())
    }

    async fn remove_friend_data(&self, peer_id: &str) -> Result<bool> {
        let resources = {
            let mut db = self.db()?;
            let transaction = db.transaction()?;
            let mut query = transaction.prepare(
                "SELECT share_id FROM resources WHERE token IN
                 (SELECT token FROM messages WHERE peer_id=?1 AND sender_id=?2 AND token IS NOT NULL)",
            )?;
            let resources = query
                .query_map(params![peer_id, self.device_id], |row| {
                    row.get::<_, String>(0)
                })?
                .collect::<rusqlite::Result<Vec<_>>>()?;
            drop(query);
            let removed =
                transaction.execute("DELETE FROM friends WHERE device_id=?1", [peer_id])?;
            if removed == 0 {
                return Ok(false);
            }
            transaction.execute(
                "UPDATE messages SET delivery_status='cancelled' WHERE peer_id=?1 AND sender_id=?2 AND delivery_status='pending'",
                params![peer_id, self.device_id],
            )?;
            transaction.execute(
                "DELETE FROM resources WHERE token IN
                 (SELECT token FROM messages WHERE peer_id=?1 AND sender_id=?2 AND token IS NOT NULL)",
                params![peer_id, self.device_id],
            )?;
            transaction.commit()?;
            resources
        };
        for share_id in resources {
            let _ = self
                .leases
                .stop_share(&format!("direct:{share_id}"), &share_id)
                .await;
        }
        let _ = self.app.emit("messenger-changed", ());
        Ok(true)
    }

    pub async fn remove_friend(&self, peer_id: &str) -> Result<()> {
        if !valid_id(peer_id) || peer_id == self.device_id {
            bail!("设备 ID 无效");
        }
        if !self.remove_friend_data(peer_id).await? {
            bail!("好友关系已不存在");
        }
        let _ = self
            .send(
                peer_id,
                &json!({"type":"FRIEND_REMOVE","from":self.device_id,"to":peer_id}),
            )
            .await;
        Ok(())
    }

    pub async fn send_text(&self, peer_id: &str, content: &str) -> Result<ChatMessage> {
        if self.status(peer_id)?.as_deref() != Some("accepted") {
            bail!("请先添加设备好友");
        }
        let content = content.trim();
        if content.is_empty() || content.len() > 2000 {
            bail!("消息长度需在 1 到 2000 字节之间");
        }
        let message = ChatMessage {
            message_id: format!("{:032x}", rand::random::<u128>()),
            peer_id: peer_id.into(),
            sender_id: self.device_id.clone(),
            kind: "text".into(),
            content: content.into(),
            file_name: None,
            file_size: None,
            is_folder: false,
            token: None,
            created_at: now(),
            download_status: "pending".into(),
            delivery_status: "pending".into(),
        };
        self.store_message(&message)?;
        let _ = self.deliver(&message).await;
        Ok(message)
    }

    pub async fn send_file(&self, peer_id: &str, paths: Vec<PathBuf>) -> Result<ChatMessage> {
        if self.status(peer_id)?.as_deref() != Some("accepted") {
            bail!("请先添加设备好友");
        }
        if paths.is_empty() {
            bail!("请先选择文件或文件夹");
        }
        let (name, size, folder) = describe_sources(&paths).await?;
        let resource = self
            .leases
            .create_direct_share(name.clone(), size, folder, paths.clone())
            .await?;
        let message = ChatMessage {
            message_id: format!("{:032x}", rand::random::<u128>()),
            peer_id: peer_id.into(),
            sender_id: self.device_id.clone(),
            kind: if folder { "folder" } else { "file" }.into(),
            content: String::new(),
            file_name: Some(name.clone()),
            file_size: Some(size),
            is_folder: folder,
            token: Some(resource.token.clone()),
            created_at: now(),
            download_status: "pending".into(),
            delivery_status: "pending".into(),
        };
        self.db()?.execute(
            "INSERT INTO resources VALUES(?1,?2,?3,?4,?5,?6)",
            params![
                resource.share_id,
                resource.token,
                paths
                    .iter()
                    .map(|path| path.to_string_lossy().to_string())
                    .collect::<Vec<_>>()
                    .join("|"),
                name,
                size,
                folder
            ],
        )?;
        self.store_message(&message)?;
        if !folder && paths.len() == 1 {
            self.set_local_media(&message.message_id, &paths[0])?;
        }
        let _ = self.deliver(&message).await;
        Ok(message)
    }

    pub fn validate_media_download(&self, message_id: &str, token: &str) -> Result<()> {
        let found: bool = self.db()?.query_row("SELECT EXISTS(SELECT 1 FROM messages WHERE message_id=?1 AND token=?2 AND sender_id!=?3)", params![message_id, token, self.device_id], |r| r.get(0))?;
        if !found {
            bail!("文件与聊天消息不匹配");
        }
        Ok(())
    }

    pub fn set_local_media(&self, message_id: &str, path: &Path) -> Result<()> {
        self.db()?.execute("INSERT OR REPLACE INTO message_media(message_id,local_path) SELECT message_id,?2 FROM messages WHERE message_id=?1 AND is_folder=0", params![message_id, path.to_string_lossy()])?;
        let _ = self.app.emit("messenger-changed", ());
        Ok(())
    }

    pub fn image_preview(&self, message_id: &str) -> Result<Option<String>> {
        if !valid_id(message_id) {
            bail!("消息 ID 无效");
        }
        let path = message_image_path(&self.db()?, message_id, &self.device_id)?;
        match path {
            Some(path) if !path.contains('|') => {
                crate::engine::chat_media::read_image(Path::new(&path))
            }
            _ => Ok(None),
        }
    }

    fn store_message(&self, message: &ChatMessage) -> Result<()> {
        self.db()?.execute(
            "INSERT OR IGNORE INTO messages VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12)",
            params![
                message.message_id,
                message.peer_id,
                message.sender_id,
                message.kind,
                message.content,
                message.file_name,
                message.file_size,
                message.is_folder,
                message.token,
                message.created_at,
                message.download_status,
                message.delivery_status
            ],
        )?;
        Ok(())
    }

    async fn deliver(&self, message: &ChatMessage) -> Result<()> {
        self.send(
            &message.peer_id,
            &json!({"type":"CHAT_MESSAGE","from":self.device_id,"to":message.peer_id,"message":message}),
        ).await
    }

    pub async fn maintain(self: Arc<Self>) {
        loop {
            tokio::time::sleep(std::time::Duration::from_secs(6)).await;
            let pending = (|| -> Result<(Vec<String>, Vec<ChatMessage>)> {
                let db = self.db()?;
                let mut friends_query =
                    db.prepare("SELECT device_id FROM friends WHERE status='pending_out'")?;
                let friends = friends_query
                    .query_map([], |r| r.get(0))?
                    .collect::<rusqlite::Result<Vec<String>>>()?;
                let mut messages_query = db.prepare("SELECT message_id, peer_id, sender_id, kind, content, file_name,
                    file_size, is_folder, token, created_at, download_status, delivery_status FROM messages
                    WHERE sender_id=?1 AND delivery_status='pending'
                    AND peer_id IN (SELECT device_id FROM friends WHERE status='accepted')
                    ORDER BY created_at LIMIT 50")?;
                let messages = messages_query
                    .query_map([&self.device_id], |r| {
                        Ok(ChatMessage {
                            message_id: r.get(0)?,
                            peer_id: r.get(1)?,
                            sender_id: r.get(2)?,
                            kind: r.get(3)?,
                            content: r.get(4)?,
                            file_name: r.get(5)?,
                            file_size: r.get(6)?,
                            is_folder: r.get(7)?,
                            token: r.get(8)?,
                            created_at: r.get(9)?,
                            download_status: r.get(10)?,
                            delivery_status: r.get(11)?,
                        })
                    })?
                    .collect::<rusqlite::Result<Vec<_>>>()?;
                Ok((friends, messages))
            })();
            let Ok((friends, messages)) = pending else {
                continue;
            };
            for peer_id in friends {
                let _ = self.send(&peer_id, &json!({"type":"FRIEND_REQUEST","from":self.device_id,
                    "to":peer_id,"device_name":self.device_name,"message_id":format!("{:032x}",rand::random::<u128>())})).await;
            }
            for message in messages {
                let _ = self.deliver(&message).await;
            }
        }
    }

    pub fn set_download_status(&self, message_id: &str, status: &str) -> Result<()> {
        if !valid_id(message_id) || !matches!(status, "pending" | "completed" | "error") {
            bail!("下载状态无效");
        }
        self.db()?.execute(
            "UPDATE messages SET download_status=?1 WHERE message_id=?2 AND sender_id!=?3",
            params![status, message_id, self.device_id],
        )?;
        Ok(())
    }

    /// 删除单条本机聊天记录。规划 §34：第一阶段只删除本机记录，不做跨设备撤回。
    pub fn delete_message(&self, message_id: &str) -> Result<()> {
        if !valid_id(message_id) {
            bail!("消息 ID 无效");
        }
        self.db()?
            .execute("DELETE FROM messages WHERE message_id=?1", [message_id])?;
        Ok(())
    }

    /// 清空与某个好友的本机聊天记录，返回删除条数。对方设备上的记录不受影响。
    pub fn clear_conversation(&self, peer_id: &str) -> Result<usize> {
        if !valid_id(peer_id) {
            bail!("设备 ID 无效");
        }
        Ok(self
            .db()?
            .execute("DELETE FROM messages WHERE peer_id=?1", [peer_id])?)
    }

    pub async fn handle_packet(&self, value: &Value, remote: SocketAddr) {
        let Some(kind) = protocol::message_type(value) else {
            return;
        };
        if !protocol::is_supported(value) {
            self.diagnostics.record(
                "PROTOCOL",
                format!(
                    "{kind} 协议版本不受支持（{}），来自 {}",
                    protocol::version_of(value)
                        .map(|version| format!("v{version}"))
                        .unwrap_or_else(|| "未声明".into()),
                    remote.ip()
                ),
            );
            return;
        }
        if !matches!(
            kind,
            "FRIEND_REQUEST"
                | "FRIEND_ACCEPT"
                | "FRIEND_REJECT"
                | "FRIEND_REMOVE"
                | "CHAT_MESSAGE"
                | "CHAT_ACK"
        ) {
            return;
        }
        let Some(from) = protocol::string_field(value, "from") else {
            return;
        };
        if !valid_id(from) || protocol::string_field(value, "to") != Some(self.device_id.as_str()) {
            return;
        }
        let known = self
            .peers
            .read()
            .await
            .get(from)
            .is_some_and(|peer| peer.ip == remote.ip().to_string());
        if !known {
            return;
        }
        if kind == "FRIEND_REMOVE" {
            if let Err(error) = self.remove_friend_data(from).await {
                self.diagnostics
                    .record("CHAT", format!("Ignored {kind}: {error:#}"));
            }
            return;
        }
        let mut ack_id: Option<String> = None;
        let mut repeat_accept = false;
        let result: Result<()> = (|| {
            let db = self.db()?;
            match kind {
                "FRIEND_REQUEST" => {
                    let name = protocol::string_field(value, "device_name").unwrap_or("局域网设备");
                    if name.len() > 200 {
                        bail!("设备名过长");
                    }
                    db.execute("INSERT INTO friends VALUES(?1,?2,'pending_in')
                        ON CONFLICT(device_id) DO UPDATE SET device_name=excluded.device_name,
                        status=CASE WHEN friends.status='accepted' THEN 'accepted' ELSE 'pending_in' END",
                        params![from,name])?;
                    repeat_accept = self.status(from)?.as_deref() == Some("accepted");
                }
                "FRIEND_ACCEPT" | "FRIEND_REJECT" => {
                    if self.status(from)?.as_deref() != Some("pending_out") {
                        return Ok(());
                    }
                    if kind == "FRIEND_ACCEPT" {
                        db.execute(
                            "UPDATE friends SET status='accepted' WHERE device_id=?1",
                            [from],
                        )?;
                    } else {
                        db.execute("DELETE FROM friends WHERE device_id=?1", [from])?;
                    }
                }
                "CHAT_MESSAGE" => {
                    if self.status(from)?.as_deref() != Some("accepted") {
                        return Ok(());
                    }
                    let raw = protocol::field(value, "message").context("缺少消息")?;
                    let message: ChatMessage = serde_json::from_value(raw.clone())?;
                    if !valid_id(&message.message_id)
                        || message.sender_id != from
                        || message.peer_id != self.device_id
                        || message.content.len() > 2000
                        || message
                            .file_name
                            .as_ref()
                            .is_some_and(|name| name.len() > 255)
                        || !matches!(message.kind.as_str(), "text" | "file" | "folder")
                        || (message.kind != "text"
                            && (!message.token.as_ref().is_some_and(|token| valid_id(token))
                                || !message
                                    .file_name
                                    .as_ref()
                                    .is_some_and(|name| safe_name(name))))
                    {
                        bail!("消息字段无效");
                    }
                    let message = ChatMessage {
                        peer_id: from.into(),
                        download_status: "pending".into(),
                        delivery_status: "delivered".into(),
                        ..message
                    };
                    self.store_message(&message)?;
                    ack_id = Some(message.message_id);
                }
                "CHAT_ACK" => {
                    let id = value
                        .get("message_id")
                        .and_then(Value::as_str)
                        .context("缺少消息 ID")?;
                    if valid_id(id) {
                        db.execute("UPDATE messages SET delivery_status='delivered' WHERE message_id=?1 AND peer_id=?2 AND sender_id=?3",
                            params![id,from,self.device_id])?;
                    }
                }
                _ => {}
            }
            Ok(())
        })();
        if let Err(error) = result {
            self.diagnostics
                .record("CHAT", format!("Ignored {kind}: {error:#}"));
        } else {
            if let Some(id) = ack_id {
                if let Ok(packet) = serde_json::to_vec(&chat_ack(&self.device_id, from, &id)) {
                    let _ = self.network.send_to(&self.socket, &packet, remote).await;
                }
            }
            if repeat_accept {
                let _ = self.send(from, &json!({"type":"FRIEND_ACCEPT","from":self.device_id,"to":from,
                    "device_name":self.device_name,"message_id":format!("{:032x}",rand::random::<u128>())})).await;
            }
            let _ = self.app.emit("messenger-changed", ());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn chat_ack_has_the_required_protocol_envelope() {
        let id = format!("{:032x}", 7);
        let ack = chat_ack("sender", "recipient", &id);
        assert!(protocol::is_supported(&ack));
        assert_eq!(protocol::message_type(&ack), Some("CHAT_ACK"));
        assert_eq!(
            protocol::string_field(&ack, "message_id"),
            Some(id.as_str())
        );
        assert_eq!(
            protocol::string_field(&ack, "sender_device_id"),
            Some("sender")
        );
    }

    #[test]
    fn messages_page_starts_with_newest_and_reaches_older_rows() -> Result<()> {
        let mut db = Connection::open_in_memory()?;
        db.execute_batch(
            "CREATE TABLE messages(message_id TEXT PRIMARY KEY, peer_id TEXT,
            sender_id TEXT, kind TEXT, content TEXT, file_name TEXT, file_size INTEGER,
            is_folder INTEGER, token TEXT, created_at INTEGER, download_status TEXT,
            delivery_status TEXT);",
        )?;
        let peer_id = format!("{:032x}", 999);
        let transaction = db.transaction()?;
        for index in 1..=501 {
            transaction.execute("INSERT INTO messages VALUES(?1,?2,?3,'text','hello',NULL,NULL,0,NULL,1,'pending','delivered')",
                params![format!("{index:032x}"), peer_id, peer_id])?;
        }
        transaction.commit()?;
        let recent = messages_page(&db, &peer_id, None)?;
        assert_eq!(recent.len(), 500);
        assert_eq!(recent.first().unwrap().message_id, format!("{:032x}", 2));
        assert_eq!(recent.last().unwrap().message_id, format!("{:032x}", 501));
        let older = messages_page(&db, &peer_id, Some(&recent[0].message_id))?;
        assert_eq!(older.len(), 1);
        assert_eq!(older[0].message_id, format!("{:032x}", 1));
        Ok(())
    }
    #[test]
    fn media_paths_are_local_and_preserve_legacy_sent_images() -> Result<()> {
        let db = Connection::open_in_memory()?;
        db.execute_batch("CREATE TABLE messages(message_id TEXT,sender_id TEXT,kind TEXT,is_folder INTEGER,token TEXT);
          CREATE TABLE message_media(message_id TEXT PRIMARY KEY,local_path TEXT);
          CREATE TABLE resources(token TEXT,file_path TEXT);
          INSERT INTO messages VALUES('sent','me','file',0,'token'),('received','peer','file',0,'token'),('folder','me','folder',1,'token');
          INSERT INTO resources VALUES('token','sent.png');")?;
        assert_eq!(
            message_image_path(&db, "sent", "me")?,
            Some("sent.png".into())
        );
        assert_eq!(message_image_path(&db, "received", "me")?, None);
        db.execute(
            "INSERT INTO message_media VALUES('received','saved.png')",
            [],
        )?;
        assert_eq!(
            message_image_path(&db, "received", "me")?,
            Some("saved.png".into())
        );
        assert_eq!(message_image_path(&db, "folder", "me")?, None);
        db.execute("DELETE FROM messages WHERE message_id='received'", [])?;
        assert_eq!(message_image_path(&db, "received", "me")?, None);
        Ok(())
    }
}
