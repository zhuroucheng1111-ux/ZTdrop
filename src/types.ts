export interface PeerDevice {
  node_id: string;
  device_name: string;
  ip: string;
  port: number;
  os?: string;
  protocol_version?: number | null;
}

export interface CodeShareItem {
  code: string;
  share_id: string;
  claim_id: string;
  file_name: string;
  file_size: number;
  is_folder: boolean;
  token: string;
  expire_seconds: number;
}

export interface SenderProgress {
  transfer_id: string;
  share_id: string;
  token: string;
  peer_name: string;
  peer_ip: string;
  file_name: string;
  transferred: number;
  total: number;
  speed_mb: number;
  status: "sending" | "completed" | "stopped" | "error";
  error_msg?: string;
}

export interface ResolvedShare {
  token: string;
  file_name: string;
  file_size: number;
  is_folder: boolean;
  address: string;
}

export interface TransferProgress {
  task_id: string;
  token: string;
  file_name: string;
  transferred: number;
  total: number;
  speed_mb: number;
  status: "idle" | "connecting" | "transferring" | "saving" | "completed" | "error";
  error_msg?: string;
}

export interface LocalNodeInfo {
  node_id: string;
  device_name: string;
  ip: string;
  port: number;
}

export interface NetworkSnapshot {
  protocol_version: number;
  device_id: string;
  device_name: string;
  local_ip: string;
  interface: string;
  udp_port: number;
  tcp_port: number;
  web_port: number;
  max_concurrent_transfers: number;
  active_transfers: number;
  port_health: { udp: boolean; tcp: boolean; http: boolean };
  log_categories: string[];
  log_path: string | null;
  data_dir: string;
  peers: PeerDevice[];
  leases: { code: string; device_id: string; local: boolean; remaining_seconds: number }[];
  sessions: SessionView[];
  logs: { timestamp_ms: number; category: string; message: string }[];
}

/** 三种分享模式（四位码 / 设备直连 / 浏览器）的统一资源与会话视图。 */
export interface SessionView {
  resource_id: string;
  share_id: string;
  kind: "code" | "direct" | "web";
  state: "active" | "transferring" | "draining" | "stopped" | "expired";
  name: string;
  size: number;
  is_folder: boolean;
  remaining_seconds: number | null;
  active_transfers: number;
}

export interface FriendDevice {
  device_id: string;
  device_name: string;
  status: "pending_in" | "pending_out" | "accepted";
  online: boolean;
}

export interface ChatMessage {
  message_id: string;
  peer_id: string;
  sender_id: string;
  kind: "text" | "file" | "folder";
  content: string;
  file_name?: string;
  file_size?: number;
  is_folder: boolean;
  token?: string;
  created_at: number;
  download_status: "pending" | "completed" | "error";
  delivery_status: "pending" | "delivered" | "cancelled";
}

export interface WebShareView {
  share_id: string;
  token: string;
  file_name: string;
  file_size: number;
  url: string;
  qr_svg: string;
  expires_in?: number;
  visitors: number;
  downloads: number;
  sessions: { browser: string; ip: string; transferred: number; total: number; status: string }[];
}
