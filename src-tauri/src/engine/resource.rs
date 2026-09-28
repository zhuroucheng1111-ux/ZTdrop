//! 统一资源与会话模型（规划 §19 / §48 / §60）。
//!
//! 三种分享模式（四位码 / 设备直连 / 浏览器）各自保留自己的本地存储：
//! - 四位码与设备直连：`code_share::CodeRegistry`
//! - 浏览器：`web_share::WebShareServer`
//!
//! 但它们对上层暴露同一份模型 [`ShareResource`]：同一套状态、同一套停止语义、
//! 同一份会话快照。状态的权威数据来自各模式自己的标志位与传输计数，
//! [`ShareState::of`] 负责把它们归一成统一状态。

use serde::Serialize;

#[derive(Clone, Copy, PartialEq, Eq, Debug, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum ShareKind {
    /// 四位分享码
    Code,
    /// 设备直连文件消息
    Direct,
    /// 浏览器单独分享
    Web,
}

impl ShareKind {
    pub fn label(&self) -> &'static str {
        match self {
            ShareKind::Code => "code",
            ShareKind::Direct => "direct",
            ShareKind::Web => "web",
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum ShareState {
    /// 已建立、等待连接
    Active,
    /// 正在传输
    Transferring,
    /// 传完再停：不再接受新连接，等待当前传输结束
    Draining,
    Stopped,
    Expired,
}

impl ShareState {
    /// 把各模式的状态标志归一成统一状态。
    pub fn of(stopped: bool, draining: bool, expired: bool, transfers: usize) -> Self {
        if stopped {
            ShareState::Stopped
        } else if expired {
            ShareState::Expired
        } else if draining {
            ShareState::Draining
        } else if transfers > 0 {
            ShareState::Transferring
        } else {
            ShareState::Active
        }
    }
}

/// 统一会话视图：诊断页、停止分享和“停止全部”都用它。
#[derive(Clone, Serialize)]
pub struct ShareResource {
    /// `CodeRegistry` 里的键（四位码或 `direct:<id>`）或浏览器分享令牌
    pub resource_id: String,
    pub share_id: String,
    pub kind: ShareKind,
    pub state: ShareState,
    pub name: String,
    pub size: u64,
    pub is_folder: bool,
    /// `None` 表示没有自动过期时间（设备直连或手动结束的浏览器分享）
    pub remaining_seconds: Option<u64>,
    pub active_transfers: usize,
}

impl ShareResource {
    pub fn stops_when_drained(&self) -> bool {
        self.state == ShareState::Draining && self.active_transfers == 0
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn state_prefers_stop_then_expiry_then_draining_then_transfer() {
        assert_eq!(ShareState::of(true, true, true, 3), ShareState::Stopped);
        assert_eq!(ShareState::of(false, true, true, 3), ShareState::Expired);
        assert_eq!(ShareState::of(false, true, false, 3), ShareState::Draining);
        assert_eq!(
            ShareState::of(false, false, false, 2),
            ShareState::Transferring
        );
        assert_eq!(ShareState::of(false, false, false, 0), ShareState::Active);
    }

    #[test]
    fn drained_resource_is_ready_for_final_teardown() {
        let resource = ShareResource {
            resource_id: "1234".into(),
            share_id: "a".repeat(32),
            kind: ShareKind::Code,
            state: ShareState::Draining,
            name: "sample.bin".into(),
            size: 10,
            is_folder: false,
            remaining_seconds: Some(120),
            active_transfers: 0,
        };
        assert!(resource.stops_when_drained());
    }
}
