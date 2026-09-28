//! 结构化日志与落盘文件（规划 §53）。
//!
//! - 内存里保留最近 [`MAX_ENTRIES`] 条，供诊断页快速展示与筛选；
//! - 同时按 JSON Lines 追加到 `logs/ztdrop.log`，单文件超过 [`MAX_FILE_BYTES`]
//!   后轮转为带时间戳的归档，最多保留 [`MAX_ARCHIVES`] 份。

use serde::Serialize;
use std::collections::VecDeque;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

const MAX_ENTRIES: usize = 400;
const MAX_FILE_BYTES: u64 = 2 * 1024 * 1024;
const MAX_ARCHIVES: usize = 5;

/// 结构化字段：能定位到具体设备、对端、分享或传输时填上，其余留空。
#[derive(Clone, Default, Serialize)]
pub struct LogFields {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub device_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub peer_ip: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub interface: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub share_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub transfer_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub message_id: Option<String>,
}

#[derive(Clone, Serialize)]
pub struct LogEntry {
    pub timestamp_ms: u64,
    /// DISCOVERY / CODE / FRIEND / CHAT / TRANSFER / WEB_SHARE / SESSION / PROTOCOL / DIAG / APP
    pub category: String,
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub peer_ip: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub device_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub interface: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub share_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub transfer_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub message_id: Option<String>,
}

#[derive(Default)]
pub struct Diagnostics {
    entries: Mutex<VecDeque<LogEntry>>,
    path: Option<PathBuf>,
    file: Mutex<Option<std::fs::File>>,
}

impl Diagnostics {
    /// 带落盘文件的诊断日志；目录不存在时自动创建。
    pub fn with_file(path: PathBuf) -> Self {
        if let Some(parent) = path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        Self {
            entries: Mutex::new(VecDeque::new()),
            path: Some(path),
            file: Mutex::new(None),
        }
    }

    pub fn record(&self, category: &str, message: impl Into<String>) {
        self.record_event(category, message, LogFields::default())
    }

    pub fn record_event(&self, category: &str, message: impl Into<String>, fields: LogFields) {
        let timestamp_ms = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|duration| duration.as_millis() as u64)
            .unwrap_or(0);
        let entry = LogEntry {
            timestamp_ms,
            category: category.into(),
            message: message.into(),
            peer_ip: fields.peer_ip,
            device_id: fields.device_id,
            interface: fields.interface,
            share_id: fields.share_id,
            transfer_id: fields.transfer_id,
            message_id: fields.message_id,
        };
        if let Ok(mut entries) = self.entries.lock() {
            if entries.len() == MAX_ENTRIES {
                entries.pop_front();
            }
            entries.push_back(entry.clone());
        }
        self.append_to_file(&entry);
    }

    pub fn recent(&self) -> Vec<LogEntry> {
        self.entries
            .lock()
            .map(|entries| entries.iter().rev().cloned().collect())
            .unwrap_or_default()
    }

    /// 当前内存日志里出现过的类别，供前端生成筛选按钮。
    pub fn categories(&self) -> Vec<String> {
        let mut list: Vec<String> = Vec::new();
        for entry in self.recent() {
            if !list.contains(&entry.category) {
                list.push(entry.category);
            }
        }
        list
    }

    pub fn log_path(&self) -> Option<&Path> {
        self.path.as_deref()
    }

    fn append_to_file(&self, entry: &LogEntry) {
        let Some(path) = self.path.as_ref() else {
            return;
        };
        let Ok(mut guard) = self.file.lock() else {
            return;
        };
        if guard.is_none() {
            *guard = std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open(path)
                .ok();
        }
        let Some(file) = guard.as_mut() else {
            return;
        };
        if serde_json::to_writer(&mut *file, entry).is_ok() {
            let _ = file.write_all(b"\n");
            let _ = file.flush();
        }
        let oversized = file
            .metadata()
            .map(|metadata| metadata.len() > MAX_FILE_BYTES)
            .unwrap_or(false);
        if oversized {
            *guard = None;
            rotate(path);
        }
    }
}

/// 把过大的日志文件改名归档，并只保留最近 [`MAX_ARCHIVES`] 份。
fn rotate(path: &Path) {
    let stamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis())
        .unwrap_or(0);
    let archive = path.with_file_name(format!("ztdrop-{stamp}.log"));
    if std::fs::rename(path, &archive).is_err() {
        return;
    }
    let Some(directory) = path.parent() else {
        return;
    };
    let Ok(entries) = std::fs::read_dir(directory) else {
        return;
    };
    let mut archives: Vec<PathBuf> = entries
        .flatten()
        .map(|entry| entry.path())
        .filter(|candidate| {
            candidate
                .file_name()
                .is_some_and(|name| name.to_string_lossy().starts_with("ztdrop-"))
        })
        .collect();
    archives.sort();
    while archives.len() > MAX_ARCHIVES {
        let oldest = archives.remove(0);
        let _ = std::fs::remove_file(oldest);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keeps_structured_fields_and_writes_json_lines() {
        let path = std::env::temp_dir().join(format!(
            "ztdrop-log-{:032x}/ztdrop.log",
            rand::random::<u128>()
        ));
        let diagnostics = Diagnostics::with_file(path.clone());
        diagnostics.record_event(
            "TRANSFER",
            "TCP connection from 192.168.1.5",
            LogFields {
                peer_ip: Some("192.168.1.5".into()),
                share_id: Some("a".repeat(32)),
                ..LogFields::default()
            },
        );
        diagnostics.record("DISCOVERY", "peer seen");

        let entries = diagnostics.recent();
        assert_eq!(entries.len(), 2);
        assert_eq!(entries[0].category, "DISCOVERY");
        assert_eq!(entries[1].peer_ip.as_deref(), Some("192.168.1.5"));
        assert_eq!(
            diagnostics.categories(),
            vec!["DISCOVERY".to_string(), "TRANSFER".to_string()]
        );
        assert_eq!(diagnostics.log_path(), Some(path.as_path()));

        let text = std::fs::read_to_string(&path).unwrap();
        assert_eq!(text.lines().count(), 2);
        assert!(text.contains("\"category\":\"TRANSFER\""));
        let _ = std::fs::remove_dir_all(path.parent().unwrap());
    }
}
