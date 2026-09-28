import { useEffect, useRef, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { ask } from "@tauri-apps/plugin-dialog";
import type { ChatMessage, FriendDevice, LocalNodeInfo, PeerDevice, ResolvedShare, SenderProgress, TransferProgress } from "./types";

type IncomingOffer = ResolvedShare & { offer_id: string; from_device_name: string };

interface Props {
  localInfo: LocalNodeInfo | null;
  peers: PeerDevice[];
  messageProgress: Record<string, TransferProgress>;
  senderProgress: Record<string, SenderProgress>;
  standaloneTransfer: TransferProgress | null;
  onClearTransfer: () => void;
  attachment: { id: number; paths: string[] } | null;
  onClearAttachment: () => void;
  isDragging: boolean;
  incomingOffer: IncomingOffer | null;
  /// `files` = 多选文件，`folder` = 选择单个文件夹（原生对话框无法二合一）。
  onPick: (mode: "files" | "folder") => void;
  onAcceptOffer: () => void;
  onRejectOffer: () => void;
  onDownload: (message: ChatMessage, peer: FriendDevice) => Promise<void>;
  showToast: (message: string) => void;
}

function sizeText(size: number) { return size < 1024 ? `${size} B` : size < 1048576 ? `${(size / 1024).toFixed(1)} KB` : `${(size / 1048576).toFixed(1)} MB`; }

export default function DirectPanel({ localInfo, peers, messageProgress, senderProgress, standaloneTransfer, onClearTransfer,
  attachment, onClearAttachment, isDragging, incomingOffer, onPick, onAcceptOffer, onRejectOffer, onDownload, showToast }: Props) {
  const [friends, setFriends] = useState<FriendDevice[]>([]);
  const [selectedId, setSelectedId] = useState("");
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [hasOlderMessages, setHasOlderMessages] = useState(false);
  const [loadingOlderMessages, setLoadingOlderMessages] = useState(false);
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);
  const [attachmentMenuOpen, setAttachmentMenuOpen] = useState(false);
  const sentAttachmentId = useRef(0);
  const loadedOlderMessages = useRef(false);
  const selectedIdRef = useRef(selectedId);
  selectedIdRef.current = selectedId;
  const selected = friends.find((friend) => friend.device_id === selectedId);

  function replaceMessages(list: ChatMessage[]) {
    loadedOlderMessages.current = false;
    setMessages(list);
    setHasOlderMessages(list.length === 500);
  }

  useEffect(() => {
    let active = true;
    const refresh = async () => {
      try {
        const list = await invoke<FriendDevice[]>("get_friends");
        if (active) setFriends(list);
      } catch (error) { if (active) showToast("读取好友失败：" + String(error)); }
    };
    void refresh();
    const timer = setInterval(refresh, 5000);
    const listener = listen("messenger-changed", refresh);
    return () => { active = false; clearInterval(timer); listener.then((unlisten) => unlisten()); };
  }, []);

  useEffect(() => {
    setMessages([]);
    setHasOlderMessages(false);
    loadedOlderMessages.current = false;
    if (!selectedId) return;
    let active = true;
    const refresh = async () => {
      try {
        const list = await invoke<ChatMessage[]>("get_messages", { peerId: selectedId });
        if (active) {
          setMessages((current) => {
            if (current.length <= 500) return list;
            const recentIds = new Set(list.map((message) => message.message_id));
            return [...current.filter((message) => !recentIds.has(message.message_id)), ...list];
          });
          setHasOlderMessages((current) => loadedOlderMessages.current ? current : list.length === 500);
        }
      } catch (error) { if (active) showToast("读取消息失败：" + String(error)); }
    };
    void refresh();
    const listener = listen("messenger-changed", refresh);
    return () => { active = false; listener.then((unlisten) => unlisten()); };
  }, [selectedId]);

  async function loadOlderMessages() {
    if (!selectedId || !messages.length || loadingOlderMessages) return;
    setLoadingOlderMessages(true);
    try {
      const older = await invoke<ChatMessage[]>("get_messages", {
        peerId: selectedId, beforeId: messages[0].message_id,
      });
      if (selectedIdRef.current !== selectedId) return;
      loadedOlderMessages.current = true;
      setMessages((current) => {
        const known = new Set(current.map((message) => message.message_id));
        return [...older.filter((message) => !known.has(message.message_id)), ...current];
      });
      setHasOlderMessages(older.length === 500);
    } catch (error) { showToast("读取更早消息失败：" + String(error)); }
    finally { setLoadingOlderMessages(false); }
  }

  async function add(peer: PeerDevice) {
    setBusy(true);
    try {
      await invoke("request_friend", { peerId: peer.node_id });
      showToast("已向 " + peer.device_name + " 发送好友请求");
      setFriends(await invoke("get_friends"));
    } catch (error) { showToast("好友请求失败：" + String(error)); }
    finally { setBusy(false); }
  }

  async function answer(peerId: string, accept: boolean) {
    setBusy(true);
    try {
      await invoke("answer_friend", { peerId, accept });
      setFriends(await invoke("get_friends"));
      if (accept) setSelectedId(peerId);
    } catch (error) { showToast("处理好友请求失败：" + String(error)); }
    finally { setBusy(false); }
  }

  async function removeFriend(friend: FriendDevice) {
    if (busy) return;
    const confirmed = await ask(`删除好友“${friend.device_name}”？本机聊天记录会保留，已发送的文件消息将停止分享。`, {
      title: "删除设备好友", kind: "warning", okLabel: "删除好友", cancelLabel: "取消",
    });
    if (!confirmed) return;
    setBusy(true);
    try {
      await invoke("remove_friend", { peerId: friend.device_id });
      setSelectedId("");
      setFriends(await invoke("get_friends"));
      showToast("已删除好友");
    } catch (error) { showToast("删除好友失败：" + String(error)); }
    finally { setBusy(false); }
  }

  /// 删除单条本机聊天记录（规划 §34：第一阶段只删除本机记录，不做跨设备撤回）。
  async function deleteMessage(message: ChatMessage) {
    try {
      await invoke("delete_message", { messageId: message.message_id });
      const list = await invoke<ChatMessage[]>("get_messages", { peerId: message.peer_id });
      if (selectedIdRef.current === message.peer_id) replaceMessages(list);
    } catch (error) { showToast("删除记录失败：" + String(error)); }
  }

  async function clearConversation(friend: FriendDevice) {
    const confirmed = await ask(`清空与“${friend.device_name}”的本机聊天记录？对方设备上的记录不受影响。`, {
      title: "清空聊天记录", kind: "warning", okLabel: "清空", cancelLabel: "取消",
    });
    if (!confirmed) return;
    try {
      await invoke<number>("clear_conversation", { peerId: friend.device_id });
      const list = await invoke<ChatMessage[]>("get_messages", { peerId: friend.device_id });
      if (selectedIdRef.current === friend.device_id) replaceMessages(list);
      showToast("已清空本机聊天记录");
    } catch (error) { showToast("清空聊天记录失败：" + String(error)); }
  }

  async function sendText() {
    if (!selected || !draft.trim() || busy) return;
    setBusy(true);
    try {
      await invoke("send_text_message", { peerId: selected.device_id, content: draft });
      setDraft("");
      const list = await invoke<ChatMessage[]>("get_messages", { peerId: selected.device_id });
      if (selectedIdRef.current === selected.device_id) replaceMessages(list);
    } catch (error) { showToast("发送消息失败：" + String(error)); }
    finally { setBusy(false); }
  }

  async function sendFile(paths: string[]) {
    if (!selected || paths.length === 0 || busy) return;
    setBusy(true);
    try {
      await invoke("send_file_message", { peerId: selected.device_id, filePaths: paths });
      const list = await invoke<ChatMessage[]>("get_messages", { peerId: selected.device_id });
      if (selectedIdRef.current === selected.device_id) replaceMessages(list);
      onClearAttachment();
    } catch (error) { showToast("发送文件消息失败：" + String(error)); }
    finally { setBusy(false); }
  }

  // 选好文件或文件夹后直接作为一条聊天记录发出去：界面上不再保留“待发送”提示和发送按钮，
  // 发送结果只看聊天记录里的文件气泡。
  useEffect(() => {
    if (!attachment || attachment.id === sentAttachmentId.current) return;
    if (!selected) { sentAttachmentId.current = attachment.id; onClearAttachment(); showToast("请先选择一台好友设备"); return; }
    if (!selected.online) { sentAttachmentId.current = attachment.id; onClearAttachment(); showToast("设备已离线，无法发送文件"); return; }
    if (busy) return;
    sentAttachmentId.current = attachment.id;
    void sendFile(attachment.paths);
  }, [attachment, busy, selected]);

  // 独立接收完成后自动收起浮层，避免长期占用窗口。
  useEffect(() => {
    if (!standaloneTransfer || standaloneTransfer.status !== "completed") return;
    const timer = setTimeout(onClearTransfer, 5000);
    return () => clearTimeout(timer);
  }, [standaloneTransfer?.status, standaloneTransfer?.task_id]);

  const nearby = peers.filter((peer) => !friends.some((friend) => friend.device_id === peer.node_id));
  const senderSessions = Object.values(senderProgress);
  return <section className="work-card messenger-card">
    <div className="direct-toolbar"><span className="device-count">{peers.length} 台设备在线</span></div>
    {standaloneTransfer && standaloneTransfer.status !== "idle" && <div className="standalone-transfer" role="status">
      <strong title={standaloneTransfer.file_name}>{standaloneTransfer.file_name}</strong>
      <span>{standaloneTransfer.status === "completed" ? "接收完成" : standaloneTransfer.status === "error" ? (standaloneTransfer.error_msg || "接收失败") : standaloneTransfer.total ? `正在接收 ${Math.round(standaloneTransfer.transferred / standaloneTransfer.total * 100)}%` : "正在连接或保存"}</span>
      <div className="progress-track"><div className="progress-fill" style={{ width: `${standaloneTransfer.total ? Math.min(100, Math.round(standaloneTransfer.transferred / standaloneTransfer.total * 100)) : standaloneTransfer.status === "completed" ? 100 : 0}%` }} /></div>
      {["completed", "error"].includes(standaloneTransfer.status) && <button onClick={onClearTransfer} aria-label="关闭接收状态">×</button>}
    </div>}
    {incomingOffer && <div className="incoming-card"><div className="incoming-copy"><strong>{incomingOffer.from_device_name} 发来文件</strong><span>{incomingOffer.file_name}</span></div><div className="incoming-actions"><button className="small-button dark" onClick={onAcceptOffer}>接收</button><button className="small-button light" onClick={onRejectOffer}>拒绝</button></div></div>}
    <div className="messenger-layout">
      <aside className="friend-sidebar">
        <strong className="sidebar-title">我的设备</strong>
        {friends.filter((friend) => friend.status === "accepted").map((friend) => <button key={friend.device_id} className={selectedId === friend.device_id ? "friend-item active" : "friend-item"} onClick={() => { setSelectedId(friend.device_id); onClearAttachment(); }}><span className={friend.online ? "online-dot" : "offline-dot"}/><span>{friend.device_name}</span></button>)}
        {friends.filter((friend) => friend.status === "pending_in").map((friend) => <div className="friend-request" key={friend.device_id}><strong>{friend.device_name} 请求添加</strong><div><button disabled={busy} onClick={() => answer(friend.device_id, true)}>接受</button><button disabled={busy} onClick={() => answer(friend.device_id, false)}>拒绝</button></div></div>)}
        {friends.filter((friend) => friend.status === "pending_out").map((friend) => <div className="friend-request" key={friend.device_id}>{friend.device_name} · 等待接受</div>)}
        <strong className="sidebar-title nearby-title">添加附近设备</strong>
        {nearby.map((peer) => <div className="nearby-item" key={peer.node_id}><span>{peer.device_name}</span><button disabled={busy} onClick={() => add(peer)}>添加</button></div>)}
        {!friends.length && !nearby.length && <p className="subtext">暂无设备</p>}
      </aside>
      <div className="conversation">
        {selected && selected.status === "accepted" ? <>
          <div className="conversation-heading"><strong>{selected.device_name}</strong><div className="conversation-actions"><span>{selected.online ? "● 在线" : "○ 离线"}</span><button disabled={busy || !messages.length} onClick={() => void clearConversation(selected)}>清空记录</button><button disabled={busy} onClick={() => void removeFriend(selected)}>删除好友</button></div></div>
          <div className="message-list">
            {hasOlderMessages && <button className="older-messages" disabled={loadingOlderMessages} onClick={() => void loadOlderMessages()}>{loadingOlderMessages ? "正在加载…" : "加载更早消息"}</button>}
            {messages.map((message) => {
              const mine = message.sender_id === localInfo?.node_id;
              const sent = mine && message.token ? senderSessions.filter((session) => session.token === message.token).slice(-1)[0] : undefined;
              const received = !mine && message.token ? messageProgress[message.token] : undefined;
              const transfer = sent || received;
              const ratio = transfer?.total ? Math.min(100, Math.round(transfer.transferred / transfer.total * 100)) : transfer?.status === "completed" ? 100 : 0;
              const speed = transfer?.speed_mb ?? 0;
              const transferLabel = sent
                ? sent.status === "sending" ? "正在发送" : sent.status === "completed" ? "发送完成" : sent.status === "stopped" ? "发送已停止" : "发送失败"
                : received ? received.status === "completed" ? "接收完成" : received.status === "error" ? "接收失败" : received.status === "saving" ? "正在保存" : received.status === "connecting" ? "正在连接" : "正在接收" : "";
              const active = !!transfer && !["completed", "error", "stopped"].includes(transfer.status);
              const finished = transfer?.status === "completed" || (!mine && message.download_status === "completed");
              return <div key={message.message_id} className={`message-row ${mine ? "mine" : "theirs"} ${message.kind !== "text" ? "file-row" : ""} ${active ? "morph-active" : ""} ${finished ? "morph-finished" : ""}`}>
                <div className="message-bubble" title={transfer?.error_msg || undefined}>
                  {message.kind === "text" ? <span>{message.content}</span> : <>
                    <div className="file-bubble-heading"><span className="file-bubble-icon" aria-hidden="true">{message.is_folder ? "▣" : "▤"}</span><strong>{message.file_name}</strong></div>
                    <div className="file-bubble-main"><strong>{active ? `${ratio}%` : finished ? "100%" : message.is_folder ? "文件夹" : sizeText(message.file_size || 0)}</strong><span>{active ? transferLabel : finished ? mine ? "发送完成" : "已下载" : message.is_folder ? "文件夹" : "文件"}</span>{finished && !active && <span className="file-bubble-check" aria-hidden="true">✓</span>}</div>
                    <div className="file-bubble-extra" aria-hidden={!active}>
                      <div className="file-bubble-extra-inner">
                        <span>{sizeText(transfer?.transferred || 0)} / {sizeText(transfer?.total || 0)}{active && ` · ${speed.toFixed(1)} MB/s`}</span>
                        {!message.is_folder && <div className="progress-track"><div className="progress-fill" style={{ width: `${ratio}%` }} /></div>}
                      </div>
                    </div>
                    {!active && transfer && !finished && <span className="file-bubble-error">{transferLabel}</span>}
                    {!mine && !active && !finished && <div className="file-message-action"><button disabled={!selected.online || busy} onClick={() => void onDownload(message, selected)}>{selected.online ? "下载" : "发送设备当前离线"}</button></div>}
                  </>}
                  <time>{new Date(message.created_at * 1000).toLocaleString()}{mine && ` · ${message.delivery_status === "delivered" ? "已送达" : message.delivery_status === "cancelled" ? "已取消" : "待送达"}`}</time>
                  <button className="message-delete" aria-label="删除这条本机记录" title="删除这条本机记录" onClick={() => void deleteMessage(message)}>×</button>
                </div>
              </div>;
            })}
            {!messages.length && <p className="empty-conversation">还没有消息</p>}
          </div>
          <div className="composer"><div className="attachment-picker"><button className="attachment-plus" aria-label="添加附件" title={selected.online ? "发送文件或文件夹" : "设备离线"} aria-expanded={attachmentMenuOpen} disabled={!selected.online} onClick={() => setAttachmentMenuOpen((open) => !open)}><svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="M12 5v14M5 12h14" fill="none" stroke="currentColor" strokeWidth="2.8" strokeLinecap="round" /></svg></button>{attachmentMenuOpen && <div className="attachment-menu"><button onClick={() => { setAttachmentMenuOpen(false); onPick("files"); }}>选择文件（可多选）</button><button onClick={() => { setAttachmentMenuOpen(false); onPick("folder"); }}>选择文件夹</button></div>}</div><input value={draft} onChange={(event) => setDraft(event.target.value)} onKeyDown={(event) => { if (event.key === "Enter") void sendText(); if (event.key === "Escape") setAttachmentMenuOpen(false); }} placeholder={selected.online ? "输入消息…" : "设备离线"} disabled={!selected.online}/><button disabled={!selected.online || !draft.trim() || busy} onClick={sendText}>发送</button></div>
        </> : <div className="conversation-empty"><strong>选择一台好友设备</strong><span>添加附近设备后即可聊天并发送文件消息</span></div>}
      </div>
    </div>
    {isDragging && <div className="drop-hint">松开后直接发送给对方</div>}
  </section>;
}
