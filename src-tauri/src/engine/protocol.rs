//! 信令协议封装与版本协商（V0.9 协议收敛、V1.0 协议冻结）。
//!
//! 所有 UDP 信令消息使用同一个扁平封装，保留字段固定为：
//! - `protocol_version`：协议版本号，当前为 `PROTOCOL_VERSION`
//! - `message_id`：本条消息的 32 位十六进制编号
//! - `type`：消息类型（历史上就叫 `type`，为兼容保留该字段名）
//! - `sender_device_id`：发送方持久设备 ID
//! - `payload`：消息自身的业务字段。当前版本仍平铺在顶层，接收端用 [`field`] 同时识别
//!   顶层与 `payload` 两种布局，便于后续把业务字段整体收进 `payload` 而不破坏兼容。
//!
//! 协议已冻结：只有显式声明 `protocol_version = PROTOCOL_VERSION` 的报文才会被解析。
//! 测试期用过的 v1 旧格式（不带版本号）与 UI4 的明文查询分支已全部移除。

use serde_json::{json, Value};

/// 当前协议版本。V0.9 起所有新报文都带该版本号。
pub const PROTOCOL_VERSION: u64 = 2;

pub fn random_message_id() -> String {
    format!("{:032x}", rand::random::<u128>())
}

pub fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
        .unwrap_or(0)
}

pub fn valid_message_id(id: &str) -> bool {
    id.len() == 32 && id.bytes().all(|byte| byte.is_ascii_hexdigit())
}

/// 给一条待发送的信令消息补上统一的协议封装字段。
pub fn stamp(value: &mut Value, message_type: &str, sender_device_id: &str) {
    let Some(object) = value.as_object_mut() else {
        return;
    };
    object.insert("protocol_version".into(), json!(PROTOCOL_VERSION));
    let has_message_id = object
        .get("message_id")
        .and_then(Value::as_str)
        .is_some_and(valid_message_id);
    if !has_message_id {
        object.insert("message_id".into(), json!(random_message_id()));
    }
    object.insert("type".into(), json!(message_type));
    object.insert("sender_device_id".into(), json!(sender_device_id));
    object.insert("timestamp_ms".into(), json!(now_ms()));
}

/// 读取报文声明的协议版本；缺失或格式不正确返回 `None`。
pub fn version_of(value: &Value) -> Option<u64> {
    value.get("protocol_version").and_then(Value::as_u64)
}

/// 是否为本版本能够解析的报文：必须显式声明当前协议版本。
pub fn is_supported(value: &Value) -> bool {
    version_of(value) == Some(PROTOCOL_VERSION)
}

pub fn message_type(value: &Value) -> Option<&str> {
    value
        .get("type")
        .and_then(Value::as_str)
        .or_else(|| value.get("message_type").and_then(Value::as_str))
}

/// 读取业务字段：优先顶层，其次嵌套的 `payload`，便于两种布局共存。
pub fn field<'a>(value: &'a Value, key: &str) -> Option<&'a Value> {
    value.get(key).filter(|item| !item.is_null()).or_else(|| {
        value
            .get("payload")
            .and_then(|payload| payload.get(key))
            .filter(|item| !item.is_null())
    })
}

pub fn string_field<'a>(value: &'a Value, key: &str) -> Option<&'a str> {
    field(value, key).and_then(Value::as_str)
}

pub fn u64_field(value: &Value, key: &str) -> Option<u64> {
    field(value, key).and_then(Value::as_u64)
}

pub fn bool_field(value: &Value, key: &str) -> Option<bool> {
    field(value, key).and_then(Value::as_bool)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stamp_adds_reserved_fields_and_keeps_existing_message_id() {
        let mut value = json!({ "code": "0427" });
        stamp(&mut value, "CODE_QUERY", "device-a");
        assert_eq!(version_of(&value), Some(PROTOCOL_VERSION));
        assert!(is_supported(&value));
        assert_eq!(message_type(&value), Some("CODE_QUERY"));
        assert_eq!(string_field(&value, "sender_device_id"), Some("device-a"));
        assert!(valid_message_id(
            string_field(&value, "message_id").unwrap()
        ));

        let first = string_field(&value, "message_id").unwrap().to_string();
        stamp(&mut value, "CODE_QUERY", "device-a");
        assert_eq!(string_field(&value, "message_id"), Some(first.as_str()));
    }

    #[test]
    fn messages_without_the_current_version_are_rejected() {
        let legacy = json!({ "type": "HEARTBEAT", "node_id": "peer" });
        assert_eq!(version_of(&legacy), None);
        assert!(!is_supported(&legacy));

        let older = json!({ "type": "HEARTBEAT", "protocol_version": 1 });
        assert_eq!(version_of(&older), Some(1));
        assert!(!is_supported(&older));

        let future = json!({ "type": "HEARTBEAT", "protocol_version": 99 });
        assert!(!is_supported(&future));

        let current = json!({ "type": "HEARTBEAT", "protocol_version": PROTOCOL_VERSION });
        assert!(is_supported(&current));
    }

    #[test]
    fn field_reads_top_level_and_nested_payload() {
        let flat = json!({ "type": "CODE_QUERY", "code": "0427" });
        assert_eq!(string_field(&flat, "code"), Some("0427"));

        let nested = json!({ "type": "CODE_QUERY", "payload": { "code": "1234" } });
        assert_eq!(string_field(&nested, "code"), Some("1234"));

        let both = json!({ "code": "0000", "payload": { "code": "1234" } });
        assert_eq!(string_field(&both, "code"), Some("0000"));
    }
}
