import { useEffect, useRef, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { getCurrentWebview } from "@tauri-apps/api/webview";
import { join } from "@tauri-apps/api/path";
import { ask, open, save } from "@tauri-apps/plugin-dialog";
import type { ChatMessage, CodeShareItem, FriendDevice, LocalNodeInfo, NetworkSnapshot, PeerDevice, ResolvedShare, SenderProgress, SessionView, TransferProgress, WebShareView } from "./types";
import DirectPanel from "./DirectPanel";
import WebSharePanel from "./WebSharePanel";

type MainTab = "transfer" | "direct" | "web";
type TransferTab = "send" | "receive";
type SettingsSection = "download" | "share" | "network" | "diagnostics" | "about";
type IncomingOffer = ResolvedShare & { offer_id: string; from_device_name: string };

function formatBytes(bytes: number): string {
  if (!bytes) return "0 B";
  const units = ["B", "KB", "MB", "GB", "TB"];
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
  return (bytes / 1024 ** index).toFixed(index === 0 ? 0 : 1) + " " + units[index];
}

function UploadIcon() {
  return (
    <svg width="52" height="52" viewBox="0 0 52 52" fill="none" aria-hidden="true">
      <path d="M26 33V8m0 0L16 18M26 8l10 10M9 33v7a5 5 0 0 0 5 5h24a5 5 0 0 0 5-5v-7" stroke="currentColor" strokeWidth="2.8" strokeLinecap="round" strokeLinejoin="round"/>
    </svg>
  );
}

function SettingsIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path d="M10 2h4l.6 2.2c.5.2 1 .5 1.4.8l2.2-.6 2 3.5-1.6 1.6c.1.5.2 1 .2 1.5s-.1 1-.2 1.5l1.6 1.6-2 3.5-2.2-.6c-.4.3-.9.6-1.4.8L14 22h-4l-.6-2.2c-.5-.2-1-.5-1.4-.8l-2.2.6-2-3.5 1.6-1.6c-.1-.5-.2-1-.2-1.5s.1-1 .2-1.5L3.8 7.9l2-3.5L8 5c.4-.3.9-.6 1.4-.8L10 2Z" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round"/>
      <circle cx="12" cy="12" r="3" stroke="currentColor" strokeWidth="1.6"/>
    </svg>
  );
}

const DOWNLOAD_DIR_KEY = "ztdrop.download-directory";
/** 0.9.7 之前用的存储键，读取时做一次兼容。 */
const LEGACY_DOWNLOAD_DIR_KEY = "ztbeam.download-directory";
/** 结束分享的方式：false = 立即停止，true = 传完再停。设置 →「分享」里切换。 */
const STOP_MODE_KEY = "ztdrop.drain-on-stop";
const LEGACY_STOP_MODE_KEY = "ztbeam.drain-on-stop";
/** 与 package.json / Cargo.toml / tauri.conf.json 保持一致的候选版本号。 */
const APP_VERSION = "0.9.20";
/** 项目仓库与更新页面：只在“关于”面板里交给系统浏览器打开，应用本身不访问公网。 */
const PROJECT_REPO_URL = "https://github.com/zhuroucheng1111-ux/ZTdrop";
const PROJECT_RELEASES_URL = `${PROJECT_REPO_URL}/releases`;
const SESSION_STATE_LABEL: Record<SessionView["state"], string> = {
  active: "等待连接",
  transferring: "传输中",
  draining: "传完再停",
  stopped: "已停止",
  expired: "已过期",
};

export default function App() {
  const [mainTab, setMainTab] = useState<MainTab>("transfer");
  const [transferTab, setTransferTab] = useState<TransferTab>("send");
  const [localInfo, setLocalInfo] = useState<LocalNodeInfo | null>(null);
  const [peers, setPeers] = useState<PeerDevice[]>([]);
  const [selectedPaths, setSelectedPaths] = useState<string[]>([]);
  const [directAttachment, setDirectAttachment] = useState<{ id: number; paths: string[] } | null>(null);
  const [activeShare, setActiveShare] = useState<CodeShareItem | null>(null);
  const [shareDeadline, setShareDeadline] = useState<number | null>(null);
  const [clock, setClock] = useState(() => Date.now());
  const [webShare, setWebShare] = useState<WebShareView | null>(null);
  const [isPreparing, setIsPreparing] = useState(false);
  const [isStopping, setIsStopping] = useState(false);
  const [senderTransfers, setSenderTransfers] = useState<Record<string, SenderProgress>>({});
  const [isDragging, setIsDragging] = useState(false);
  const [inputCode, setInputCode] = useState("");
  const codeInputRef = useRef<HTMLInputElement | null>(null);
  const [isResolving, setIsResolving] = useState(false);
  const [incomingOffer, setIncomingOffer] = useState<IncomingOffer | null>(null);
  const [activeTransfer, setActiveTransfer] = useState<TransferProgress | null>(null);
  const [messageProgress, setMessageProgress] = useState<Record<string, TransferProgress>>({});
  const [toastMessage, setToastMessage] = useState("");
  const [copySuccess, setCopySuccess] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [settingsExpanded, setSettingsExpanded] = useState(false);
  const settingsPinnedRef = useRef(false);
  const settingsPanelRef = useRef<HTMLElement | null>(null);
  const settingsTriggerRef = useRef<HTMLButtonElement | null>(null);
  const [settingsSection, setSettingsSection] = useState<SettingsSection | null>(null);
  const [settingsPreview, setSettingsPreview] = useState<SettingsSection | null>(null);
  const [physicalLanOnly, setPhysicalLanOnly] = useState(true);
  const [networkSettingBusy, setNetworkSettingBusy] = useState(false);
  const [diagnosticsOpen, setDiagnosticsOpen] = useState(false);
  const [logFilter, setLogFilter] = useState("");
  /** 发送状态列表默认展开，可收起，列表本身限高滚动，不再挤压界面。 */
  const [senderListOpen, setSenderListOpen] = useState(true);
  /** 分享内容明细：默认收起，点击后在原位悬浮展开（不挤压界面）。 */
  const [shareDetailsOpen, setShareDetailsOpen] = useState(false);
  const [shareFiles, setShareFiles] = useState<{ relative: string; size: number }[]>([]);
  const shareContentRef = useRef<HTMLDivElement | null>(null);
  const [networkSnapshot, setNetworkSnapshot] = useState<NetworkSnapshot | null>(null);
  const [downloadDir, setDownloadDir] = useState(() => {
    try { return localStorage.getItem(DOWNLOAD_DIR_KEY) || localStorage.getItem(LEGACY_DOWNLOAD_DIR_KEY) || ""; }
    catch { return ""; }
  });
  const [drainOnStop, setDrainOnStop] = useState(() => {
    try { return (localStorage.getItem(STOP_MODE_KEY) ?? localStorage.getItem(LEGACY_STOP_MODE_KEY)) === "true"; }
    catch { return false; }
  });
  const selectionSeq = useRef(0);
  const attachmentSeq = useRef(0);
  /** 关闭窗口时用于判断是否有任务在进行（每次渲染刷新）。 */
  const runningTasksRef = useRef<string[]>([]);
  const activeShareRef = useRef<CodeShareItem | null>(null);
  const invalidatedShares = useRef(new Set<string>());
  const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const messageTokens = useRef(new Map<string, string>());
  activeShareRef.current = activeShare;

  function showToast(message: string) {
    setToastMessage(message);
    if (toastTimer.current) clearTimeout(toastTimer.current);
    toastTimer.current = setTimeout(() => setToastMessage(""), 4000);
  }

  async function openProjectPage(url: string) {
    try {
      await invoke("open_external_url", { url });
    } catch {
      showToast("无法打开系统浏览器，请手动访问项目页面");
    }
  }

  async function useSource(paths: string[], tab: MainTab) {
    if (paths.length === 0) return;
    if (tab === "direct") { setDirectAttachment({ id: ++attachmentSeq.current, paths }); return; }
    if (activeShare || isPreparing || isStopping) {
      showToast("请先结束当前分享");
      return;
    }
    if (tab === "web" && paths.length > 1) {
      showToast("浏览器分享一次只能分享一个文件");
      return;
    }
    const seq = ++selectionSeq.current;
    setSelectedPaths(paths);
    if (tab === "web") {
      setIsPreparing(false);
      return;
    }
    setMainTab("transfer");
    setTransferTab("send");
    setIsPreparing(true);
    try {
      const share = await invoke<CodeShareItem>("create_code_share", { filePaths: paths });
      if (seq === selectionSeq.current) {
        if (invalidatedShares.current.delete(share.share_id)) {
          setSelectedPaths([]);
          showToast("分享码已失效，请重新选择文件");
        } else {
          activeShareRef.current = share;
          setSenderTransfers({});
          setActiveShare(share);
          setShareDeadline(Date.now() + share.expire_seconds * 1000);
        }
      }
    } catch (error) {
      if (seq === selectionSeq.current) showToast("分享失败：" + String(error));
    } finally {
      if (seq === selectionSeq.current) setIsPreparing(false);
    }
  }

  function clearShareCard() {
    ++selectionSeq.current;
    activeShareRef.current = null;
    setActiveShare(null);
    setShareDeadline(null);
      setSelectedPaths([]);
    setSenderTransfers({});
  }

  function updateStopMode(drain: boolean) {
    setDrainOnStop(drain);
    try { localStorage.setItem(STOP_MODE_KEY, drain ? "true" : "false"); } catch { /* 忽略存储失败 */ }
  }

  /** 按设置里的“结束分享方式”停止当前分享：立即中断，或传完再停。 */
  async function stopShare() {
    if (!activeShare || isStopping) return;
    setIsStopping(true);
    const share = activeShare;
    try {
      if (drainOnStop) {
        await invoke("stop_session", { id: share.code, shareId: share.share_id, drain: true });
        showToast("已设为传完再停：不再接受新连接，当前下载结束会自动收尾");
        return;
      }
      await invoke("stop_code_share", { code: share.code, shareId: share.share_id });
      clearShareCard();
      showToast("分享已结束，当前下载已中断");
    } catch (error) {
      const message = String(error);
      // 传完再停的分享可能已经被后端收尾，这时前端只要撤掉分享卡片。
      if (message.includes("分享已结束或不存在")) {
        clearShareCard();
        showToast("分享已结束");
      } else {
        showToast("结束分享失败：" + message);
      }
    } finally {
      setIsStopping(false);
    }
  }

  /// 统一的来源选择入口：多选文件，或选择单个文件夹。
  /// Windows 原生对话框无法在同一个框里既选文件又选文件夹，所以保留两种模式，
  /// 但只走这一条实现，不再有各自重复的代码。
  async function pickSource(mode: "files" | "folder") {
    try {
      const selected = await open({
        directory: mode === "folder",
        multiple: mode === "files",
        title: mode === "folder" ? "选择文件夹" : "选择文件（可多选）",
      }) as string | string[] | null;
      if (typeof selected === "string") await useSource([selected], mainTab);
      else if (Array.isArray(selected) && selected.length > 0) await useSource(selected, mainTab);
    } catch (error) {
      showToast("无法选择文件：" + String(error));
    }
  }

  useEffect(() => {
    invoke<LocalNodeInfo>("get_local_node_info").then(setLocalInfo).catch(console.error);
    invoke<boolean>("get_network_preference").then(setPhysicalLanOnly).catch(console.error);
    const refreshPeers = () => {
      invoke<PeerDevice[]>("get_nearby_devices")
        .then((list) => setPeers(list.sort((a, b) => a.device_name.localeCompare(b.device_name))))
        .catch(console.error);
    };
    refreshPeers();
    const timer = setInterval(refreshPeers, 2500);
    const progressListener = listen<TransferProgress>("transfer-progress", (event) => {
      setActiveTransfer(event.payload);
      const messageId = messageTokens.current.get(event.payload.token);
      if (messageId) {
        setMessageProgress((current) => ({ ...current, [event.payload.token]: event.payload }));
      }
      if (messageId && ["completed", "error"].includes(event.payload.status)) {
        messageTokens.current.delete(event.payload.token);
        void invoke("set_message_download_status", { messageId, status: event.payload.status }).catch(console.error);
      }
      if (event.payload.status === "error") showToast("传输失败：" + (event.payload.error_msg || "未知错误"));
    });
    const senderListener = listen<SenderProgress>("sender-progress", (event) => {
      setSenderTransfers((current) => ({ ...current, [event.payload.transfer_id]: event.payload }));
    });
    const invalidatedListener = listen<{ code: string; share_id: string; reason: string }>("share-invalidated", (event) => {
      invalidatedShares.current.add(event.payload.share_id);
      if (activeShareRef.current?.share_id === event.payload.share_id) {
        activeShareRef.current = null;
        setActiveShare(null);
        setShareDeadline(null);
        setSelectedPaths([]);
        showToast(event.payload.reason);
      }
    });
    const offerListener = listen<IncomingOffer>("direct-offer", (event) => {
      setIncomingOffer(event.payload);
      setMainTab("direct");
      showToast(event.payload.from_device_name + " 向你发送文件");
    });
    // 后端把“传完再停”的分享收尾后，前端同步撤掉分享卡片。
    const shareEndedListener = listen<{ id: string; reason: string }>("share-ended", (event) => {
      if (activeShareRef.current?.code === event.payload.id) {
        clearShareCard();
        showToast("分享已结束");
      }
    });
    return () => {
      clearInterval(timer);
      progressListener.then((unlisten) => unlisten());
      senderListener.then((unlisten) => unlisten());
      invalidatedListener.then((unlisten) => unlisten());
      offerListener.then((unlisten) => unlisten());
      shareEndedListener.then((unlisten) => unlisten());
      if (toastTimer.current) clearTimeout(toastTimer.current);
    };
  }, []);

  useEffect(() => {
    let disposed = false;
    let unlisten: (() => void) | undefined;
    getCurrentWebview().onDragDropEvent((event) => {
      if (event.payload.type === "enter" || event.payload.type === "over") {
        setIsDragging(!activeShare && !isPreparing && !isStopping);
      } else if (event.payload.type === "leave") {
        setIsDragging(false);
      } else if (event.payload.type === "drop") {
        setIsDragging(false);
        const paths = event.payload.paths;
        if (paths.length === 0) return;
        void useSource(paths, mainTab);
      }
    }).then((cleanup) => {
      if (disposed) cleanup();
      else unlisten = cleanup;
    }).catch((error) => showToast("拖放功能不可用：" + String(error)));
    return () => {
      disposed = true;
      unlisten?.();
    };
  }, [mainTab, activeShare, isPreparing, isStopping]);

  async function copyCode() {
    if (!activeShare) return;
    try {
      await navigator.clipboard.writeText(activeShare.code);
      setCopySuccess(true);
      setTimeout(() => setCopySuccess(false), 2000);
    } catch (error) {
      showToast("复制失败：" + String(error));
    }
  }

  async function chooseDownloadDir() {
    try {
      const selected = await open({ directory: true, multiple: false, title: "选择默认下载目录" });
      if (typeof selected !== "string") return;
      if (!await invoke<boolean>("directory_exists", { path: selected })) {
        showToast("所选目录不可用");
        return;
      }
      localStorage.setItem(DOWNLOAD_DIR_KEY, selected);
      setDownloadDir(selected);
      showToast("已设置默认下载目录");
    } catch (error) {
      showToast("设置下载目录失败：" + String(error));
    }
  }

  function clearDownloadDir() {
    try {
      localStorage.removeItem(DOWNLOAD_DIR_KEY);
      setDownloadDir("");
      showToast("已恢复每次选择保存位置");
    } catch (error) {
      showToast("清除设置失败：" + String(error));
    }
  }

  async function updateNetworkPreference(enabled: boolean) {
    setNetworkSettingBusy(true);
    try {
      await invoke("set_network_preference", { physicalLanOnly: enabled });
      setPhysicalLanOnly(enabled);
      setLocalInfo(await invoke<LocalNodeInfo>("get_local_node_info"));
      if (diagnosticsOpen) await refreshDiagnostics();
      showToast(enabled ? "已仅使用物理局域网" : "已使用系统网络路由");
    } catch (error) {
      showToast("更新网卡设置失败：" + String(error));
    } finally {
      setNetworkSettingBusy(false);
    }
  }

  async function refreshDiagnostics() {
    try {
      setNetworkSnapshot(await invoke<NetworkSnapshot>("get_network_diagnostics"));
    } catch (error) {
      showToast("读取网络诊断失败：" + String(error));
    }
  }

  async function exportDiagnostics() {
    try {
      const stamp = new Date().toISOString().slice(0, 19).replace(/[:T]/g, "-");
      const path = await save({
        title: "导出诊断报告",
      defaultPath: `ztdrop-diagnostics-${stamp}.json`,
        filters: [{ name: "JSON", extensions: ["json"] }],
      });
      if (typeof path !== "string") return;
      await invoke("export_diagnostics", { path });
      showToast("诊断报告已导出");
    } catch (error) {
      showToast("导出诊断报告失败：" + String(error));
    }
  }

  async function stopSession(session: SessionView) {
    // 已经在“传完再停”的会话，再点一次就是立即停止。
    const drain = session.state === "draining" ? false : drainOnStop;
    try {
      await invoke("stop_session", { id: session.resource_id, shareId: session.share_id, drain });
      showToast(drain ? "已设为传完再停，当前传输结束会自动收尾" : "已停止该分享");
      await refreshDiagnostics();
    } catch (error) {
      showToast("停止分享失败：" + String(error));
    }
  }

  async function stopAllSessions() {
    const confirmed = await ask(
      drainOnStop
        ? "把全部分享设为传完再停？会立即拒绝新连接，等当前传输结束后自动收尾。"
        : "立即停止全部分享会话？正在传输的连接会立刻中断。",
      {
      title: "停止全部分享",
      kind: "warning",
      },
    );
    if (!confirmed) return;
    try {
      const count = await invoke<number>("stop_all_sessions", { drain: drainOnStop });
      showToast(drainOnStop ? `已设为传完再停：${count} 个会话` : `已停止 ${count} 个分享会话`);
      await refreshDiagnostics();
    } catch (error) {
      showToast("停止全部分享失败：" + String(error));
    }
  }

  useEffect(() => {
    // 设置面板打开时就刷新一次快照：诊断页和“关于”都要用（版本、协议、数据目录）。
    if (!settingsOpen) return;
    void refreshDiagnostics();
    const timer = setInterval(() => void refreshDiagnostics(), 3000);
    return () => clearInterval(timer);
  }, [settingsOpen]);

  // 点设置面板以外的地方就自动收起（因此不再需要关闭按钮）。
  useEffect(() => {
    if (!settingsOpen) return;
    const onPointerDown = (event: PointerEvent) => {
      const target = event.target as Node | null;
      if (!target) return;
      if (settingsPanelRef.current?.contains(target) || settingsTriggerRef.current?.contains(target)) return;
      settingsPinnedRef.current = false;
      setSettingsExpanded(false);
      setSettingsPreview(null);
      setSettingsOpen(false);
    };
    document.addEventListener("pointerdown", onPointerDown, true);
    return () => document.removeEventListener("pointerdown", onPointerDown, true);
  }, [settingsOpen]);

  // 关闭按钮：有任务在跑时由用户决定“终止任务并完全退出”还是“最小化到托盘”。
  useEffect(() => {
    const listener = listen("window-close-requested", () => {
      void (async () => {
        const tasks = runningTasksRef.current;
        const terminate = await ask(
          tasks.length
            ? `当前有正在进行的任务：${tasks.join("、")}。\n\n要终止任务并完全退出，还是最小化到托盘继续后台传输？`
            : "要完全退出 ZTDrop，还是最小化到托盘继续在后台运行？",
          {
            title: "关闭 ZTDrop",
            kind: "warning",
            okLabel: tasks.length ? "终止任务并退出" : "完全退出",
            cancelLabel: "最小化到托盘",
          },
        );
        try {
          await invoke(terminate ? "quit_app" : "hide_to_tray");
        } catch (error) {
          showToast("操作失败：" + String(error));
        }
      })();
    });
    return () => { listener.then((unlisten) => unlisten()); };
  }, []);

  // 分享期间每秒刷新一次，用于显示剩余有效时间倒计时（计划 §21）。
  useEffect(() => {
    if (!shareDeadline) return;
    const timer = setInterval(() => setClock(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [shareDeadline]);

  // 切到「接收」时自动把光标放进分享码输入框，不用再用鼠标点一下。
  useEffect(() => {
    if (transferTab !== "receive" || mainTab !== "transfer") return;
    const timer = setTimeout(() => codeInputRef.current?.focus(), 80);
    return () => clearTimeout(timer);
  }, [transferTab, mainTab]);

  // 分享开始时读取内容清单，供"点击展开详情"使用。
  useEffect(() => {
    if (!activeShare) { setShareFiles([]); setShareDetailsOpen(false); return; }
    invoke<{ relative: string; size: number }[]>("get_share_files", { code: activeShare.code })
      .then(setShareFiles)
      .catch(() => setShareFiles([]));
  }, [activeShare?.share_id]);

  // 点击明细之外的地方收起悬浮层。
  useEffect(() => {
    if (!shareDetailsOpen) return;
    const onPointerDown = (event: PointerEvent) => {
      const target = event.target as Node | null;
      if (target && shareContentRef.current?.contains(target)) return;
      setShareDetailsOpen(false);
    };
    document.addEventListener("pointerdown", onPointerDown, true);
    return () => document.removeEventListener("pointerdown", onPointerDown, true);
  }, [shareDetailsOpen]);

  // 接收完成后 5 秒自动收起状态卡片，避免长期占用窗口（错误状态保留给用户查看）。
  useEffect(() => {
    if (!activeTransfer || activeTransfer.status !== "completed") return;
    const timer = setTimeout(() => setActiveTransfer(null), 5000);
    return () => clearTimeout(timer);
  }, [activeTransfer?.status, activeTransfer?.task_id]);

  async function receiveShare(share: ResolvedShare): Promise<string | null> {
    let savePath: string | null;
    if (downloadDir) {
      if (!await invoke<boolean>("directory_exists", { path: downloadDir })) {
        setSettingsOpen(true);
        showToast("默认下载目录不可用，请重新选择");
        return null;
      }
      savePath = await join(downloadDir, share.file_name);
    } else if (share.is_folder) {
      const parent = await open({ directory: true, title: "选择保存位置" });
      savePath = typeof parent === "string" ? await join(parent, share.file_name) : null;
    } else {
      savePath = await save({ title: "保存文件", defaultPath: share.file_name });
    }
    if (!savePath) return null;
    const exists = await invoke<boolean>("target_exists", { path: savePath });
    const overwrite = exists
      ? await ask(
          share.is_folder ? "目标文件夹已存在，继续接收并覆盖同名文件？" : "文件已存在，接收完成后覆盖？",
          { title: "确认覆盖", kind: "warning" },
        )
      : false;
    if (exists && !overwrite) return null;
    const taskId = await invoke<string>("start_download", { share, savePath, overwrite });
    showToast("开始接收：" + share.file_name);
    return taskId;
  }

  async function fetchByCode() {
    if (inputCode.length !== 4 || isResolving) return;
    setIsResolving(true);
    try {
      const share = await invoke<ResolvedShare>("resolve_code", { code: inputCode });
      if (await receiveShare(share)) setInputCode("");
    } catch (error) {
      showToast("接收失败：" + String(error));
    } finally {
      setIsResolving(false);
    }
  }

  async function acceptOffer() {
    if (!incomingOffer) return;
    const offer = incomingOffer;
    try {
      if (await receiveShare(offer)) setIncomingOffer(null);
    } catch (error) {
      showToast("接收失败：" + String(error));
    }
  }

  async function downloadMessage(message: ChatMessage, friend: FriendDevice) {
    const peer = peers.find((item) => item.node_id === friend.device_id);
    if (!peer || !message.token || !message.file_name) { showToast("发送设备当前离线"); return; }
    messageTokens.current.set(message.token, message.message_id);
    try {
      const taskId = await receiveShare({ token: message.token, file_name: message.file_name,
        file_size: message.file_size || 0, is_folder: message.is_folder, address: `${peer.ip}:${peer.port}` });
      if (!taskId) messageTokens.current.delete(message.token);
    } catch (error) { messageTokens.current.delete(message.token); showToast("下载失败：" + String(error)); }
  }

  const transferBusy = activeTransfer && !["completed", "error", "idle"].includes(activeTransfer.status);
  const percent = activeTransfer && activeTransfer.total
    ? Math.min(100, Math.round(activeTransfer.transferred / activeTransfer.total * 100))
    : 0;
  const currentSenders = Object.values(senderTransfers).filter((transfer) => transfer.share_id === activeShare?.share_id);
  const activeSettingsSection: SettingsSection | null = settingsSection ?? settingsPreview;
  function runningTasks(): string[] {
    const items: string[] = [];
    const sending = Object.values(senderTransfers).filter((transfer) => transfer.status === "sending").length;
    if (sending) items.push(`${sending} 个正在发送的传输`);
    if (activeTransfer && !["completed", "error", "idle"].includes(activeTransfer.status)) items.push("1 个正在接收的传输");
    if (activeShare) items.push("1 个四位码分享");
    if (webShare) items.push("1 个浏览器分享");
    return items;
  }
  runningTasksRef.current = runningTasks();
  const shareRemainingSeconds = shareDeadline ? Math.max(0, Math.ceil((shareDeadline - clock) / 1000)) : 0;
  const shareRemainingText = shareDeadline
    ? `剩余有效时间 ${String(Math.floor(shareRemainingSeconds / 60)).padStart(2, "0")}:${String(shareRemainingSeconds % 60).padStart(2, "0")}`
    : "15 分钟内有效";

  return (
    <div className="app-shell">
      <main className="main-content">
        <div className="top-row">
          <nav className="main-tabs" role="tablist" aria-label="主要功能">
            <span className="segmented-thumb" aria-hidden="true" style={{ transform: `translateX(${["transfer", "direct", "web"].indexOf(mainTab) * 100}%)` }} />
            <button className={mainTab === "transfer" ? "main-tab active" : "main-tab"} onClick={() => {
              setMainTab("transfer");
            }} role="tab" aria-selected={mainTab === "transfer"}><span className="tab-word">码连</span></button>
            <button className={mainTab === "direct" ? "main-tab active" : "main-tab"} role="tab" aria-selected={mainTab === "direct"} onClick={() => setMainTab("direct")}><span className="tab-word">快投</span>{incomingOffer && <span className="tab-notice" />}</button>
            <button className={mainTab === "web" ? "main-tab active" : "main-tab"} role="tab" aria-selected={mainTab === "web"} onClick={() => setMainTab("web")}><span className="tab-word">直链</span>{webShare && <span className="tab-notice" />}</button>
          </nav>
          <span className="local-device" title={localInfo ? localInfo.ip : ""}>
            <span className="online-dot" />
            {localInfo ? localInfo.device_name : "连接中"}
          </span>
        </div>

        <div className="panel-viewport"><div className="panel-track" style={{ transform: `translateX(-${["transfer", "direct", "web"].indexOf(mainTab) * 100}%)` }}>
        <div className="panel-slide" aria-hidden={mainTab !== "transfer"}>
          <section className="work-card">
            <div className="mode-switch" role="tablist" aria-label="收发切换">
              <span className="segmented-thumb" aria-hidden="true" style={{ transform: `translateX(${transferTab === "send" ? 0 : 100}%)` }} />
              <button role="tab" aria-selected={transferTab === "send"} className={transferTab === "send" ? "mode active" : "mode"} onClick={() => setTransferTab("send")}>发送</button>
              <button role="tab" aria-selected={transferTab === "receive"} className={transferTab === "receive" ? "mode active" : "mode"} onClick={() => setTransferTab("receive")}>接收</button>
            </div>

            {transferTab === "send" ? (
              <div className="send-content">
                <div className={["drop-zone", isDragging && !activeShare ? "dragging" : "", activeShare ? "sharing" : ""].filter(Boolean).join(" ")}>
                  {activeShare ? <>
                    <span className="sharing-indicator"><span className="share-pulse" aria-hidden="true" />正在分享</span>
                    <div className="share-content-wrap" ref={shareContentRef}>
                      <button className={shareDetailsOpen ? "share-content open" : "share-content"} onClick={() => setShareDetailsOpen((open) => !open)} aria-expanded={shareDetailsOpen} title="点击查看分享内容明细">
                        <span className="file-bubble-icon" aria-hidden="true">{activeShare.is_folder ? "▣" : "▤"}</span>
                        <span className="share-content-name">{activeShare.file_name}</span>
                        <span className="share-content-meta">{activeShare.file_size > 0 ? formatBytes(activeShare.file_size) : "文件夹"}{shareFiles.length > 1 ? ` · ${shareFiles.length} 项` : ""}</span>
                        <span className="share-content-chevron" aria-hidden="true">{shareDetailsOpen ? "▴" : "▾"}</span>
                      </button>
                      {shareDetailsOpen && <div className="share-details" role="dialog" aria-label="分享内容明细">
                        <div className="share-details-head"><strong>分享内容</strong><span>{shareFiles.length ? `${shareFiles.length} 个文件` : "读取中…"}</span></div>
                        <div className="share-details-list">
                          {shareFiles.length
                            ? shareFiles.map((file, index) => <div className="share-details-row" key={file.relative + index}><span title={file.relative}>{file.relative}</span><span>{formatBytes(file.size)}</span></div>)
                            : <div className="share-details-empty">正在读取清单…</div>}
                          {shareFiles.length >= 500 && <div className="share-details-empty">仅显示前 500 项</div>}
                        </div>
                        <div className="share-details-foot">{activeShare.is_folder ? "接收方会保存为一个文件夹" : "单文件分享"}</div>
                      </div>}
                    </div>
                    <div className="share-code-row">
                      <strong className="share-code">{activeShare.code}</strong>
                      <button className="small-button outline-green" onClick={copyCode}>{copySuccess ? "已复制" : "复制"}</button>
                    </div>
                    <span className="expiry">{shareRemainingText}</span>
                    <div className="share-activity" aria-hidden="true"><span /></div>
                    <p className="share-waiting">{currentSenders.length === 0 ? "等待设备连接……" : `正在发送给 ${currentSenders.length} 台设备`}</p>
                  </> : <>
                    <UploadIcon />
                    <strong>{isDragging ? "松开即可分享" : "拖放文件或文件夹"}</strong>
                    <span>{isPreparing ? "正在生成分享码…" : "拖到这里即可分享，可一次选中多个"}</span>
                  </>}
                </div>
                <div className="browse-actions">
                  {activeShare ? (
                    <button className="small-button outline-red stop-share-button" disabled={isStopping} onClick={() => void stopShare()}>{isStopping ? "正在结束…" : "结束分享"}</button>
                  ) : <button className="small-button dark" onClick={() => pickSource("files")}>分享文件</button>}
                </div>
                {!activeShare && <p className="browse-hint">支持一次选多个文件；文件夹直接拖进窗口即可分享</p>}
                {activeShare && currentSenders.length > 0 && (
                  <div className="sender-progress-list">
                    <button className="section-title sender-list-toggle" onClick={() => setSenderListOpen((open) => !open)} aria-expanded={senderListOpen}>
                      <span>发送状态（{currentSenders.length}）</span>
                      <span className="toggle-hint">{senderListOpen ? "收起" : "展开"}</span>
                    </button>
                    {senderListOpen && <div className="sender-progress-scroll">
                      {currentSenders.map((transfer) => {
                        const ratio = transfer.total ? Math.min(100, Math.round(transfer.transferred / transfer.total * 100)) : 100;
                        return <div className="sender-progress" key={transfer.transfer_id}>
                          <div className="sender-progress-heading"><strong>{transfer.peer_name}</strong><span>{transfer.status === "sending" ? `${ratio}% · ${transfer.speed_mb.toFixed(1)} MB/s` : transfer.status === "completed" ? "发送完成" : transfer.status === "stopped" ? "已停止" : "发送失败"}</span></div>
                          <div className="sender-progress-detail" title={transfer.peer_ip}>{transfer.file_name} · {formatBytes(transfer.transferred)} / {formatBytes(transfer.total)}</div>
                          <div className="progress-track"><div className="progress-fill" style={{ width: ratio + "%", background: transfer.status === "error" || transfer.status === "stopped" ? "#e35d5d" : transfer.status === "completed" ? "#36a674" : undefined }} /></div>
                        </div>;
                      })}
                    </div>}
                  </div>
                )}
              </div>
            ) : (
              <div className="receive-content">
                <h1>接收</h1>
                <p className="subtext">输入对方的 4 位分享码</p>
                <input
                  ref={codeInputRef}
                  className="code-input"
                  value={inputCode}
                  onChange={(event) => setInputCode(event.target.value.replace(/\D/g, "").slice(0, 4))}
                  onKeyDown={(event) => { if (event.key === "Enter") void fetchByCode(); }}
                  inputMode="numeric"
                  maxLength={4}
                  placeholder="0000"
                  aria-label="4 位分享码"
                />
                <button className="primary-button" disabled={inputCode.length !== 4 || isResolving} onClick={fetchByCode}>{isResolving ? "正在查找…" : "接收文件"}</button>
              </div>
            )}
          </section>
        </div>
        <div className="panel-slide" aria-hidden={mainTab !== "direct"}>
          <DirectPanel localInfo={localInfo} peers={peers} attachment={directAttachment}
            messageProgress={messageProgress} senderProgress={senderTransfers}
            standaloneTransfer={activeTransfer && !messageProgress[activeTransfer.token] ? activeTransfer : null}
            onClearTransfer={() => setActiveTransfer(null)}
            isDragging={isDragging} incomingOffer={incomingOffer}
            onPick={(mode) => void pickSource(mode)} onClearAttachment={() => setDirectAttachment(null)} onAcceptOffer={() => void acceptOffer()}
            onRejectOffer={() => setIncomingOffer(null)} onDownload={downloadMessage} showToast={showToast} />
        </div>
        <div className="panel-slide" aria-hidden={mainTab !== "web"}>
          <WebSharePanel selectedFilePath={selectedPaths[0] ?? ""} isDragging={isDragging}
            onPick={() => void pickSource("files")} showToast={showToast} share={webShare} setShare={setWebShare} drainOnStop={drainOnStop} />
        </div></div></div>
        {mainTab !== "direct" && activeTransfer && !messageProgress[activeTransfer.token] && activeTransfer.status !== "idle" && (
          <div className="transfer-status">
            <div className="transfer-line"><strong>{activeTransfer.file_name}</strong><span>{activeTransfer.status === "completed" ? "接收完成" : activeTransfer.status === "error" ? (activeTransfer.error_msg || "传输失败") : activeTransfer.status === "connecting" ? "正在连接…" : activeTransfer.status === "saving" ? "正在保存…" : formatBytes(activeTransfer.transferred) + " / " + formatBytes(activeTransfer.total) + " · " + activeTransfer.speed_mb.toFixed(1) + " MB/s"}</span></div>
            <div className="progress-track"><div className="progress-fill" style={{ width: activeTransfer.status === "completed" || activeTransfer.status === "saving" ? "100%" : percent + "%", background: activeTransfer.status === "error" ? "#e35d5d" : undefined }} /></div>
            {!transferBusy && <button className="status-close" onClick={() => setActiveTransfer(null)} aria-label="关闭状态">×</button>}
          </div>
        )}
      </main>
      <button ref={settingsTriggerRef} className="settings-trigger" aria-label="设置" title="设置" aria-expanded={settingsOpen} onClick={() => {
        if (settingsOpen) { setSettingsOpen(false); return; }
        settingsPinnedRef.current = false;
        setSettingsExpanded(false);
        setSettingsSection(null);
        setSettingsPreview(null);
        setDiagnosticsOpen(false);
        setSettingsOpen(true);
      }}>
        <SettingsIcon />
      </button>
      {settingsOpen && (
        <section ref={settingsPanelRef} className={settingsExpanded ? "settings-panel expanded" : "settings-panel"} role="dialog" aria-label="设置与网络诊断" onMouseLeave={() => { if (!settingsPinnedRef.current) { setSettingsExpanded(false); setSettingsPreview(null); } }}>
          <div className="settings-rail">
            <div className="settings-heading"><strong>设置</strong></div>
            {([ ["download", "保存目录"], ["share", "分享"], ["network", "网络"], ["diagnostics", "诊断"], ["about", "关于"] ] as const).map(([section, label]) => <button key={section}
              className={settingsSection === section ? "settings-rail-item active" : "settings-rail-item"}
              onMouseEnter={() => { if (!settingsPinnedRef.current) { setSettingsPreview(section); setSettingsExpanded(true); } }}
              onFocus={() => { if (!settingsPinnedRef.current) { setSettingsPreview(section); setSettingsExpanded(true); } }}
              onClick={() => { settingsPinnedRef.current = true; setSettingsSection(section); setSettingsPreview(section); setSettingsExpanded(true); setDiagnosticsOpen(section === "diagnostics"); }}>{label}<span>›</span></button>)}
          </div>
          <div className="settings-detail" aria-hidden={!settingsExpanded}>
          <div className="settings-detail-heading"><strong>{activeSettingsSection === "download" ? "默认下载目录" : activeSettingsSection === "share" ? "分享设置" : activeSettingsSection === "network" ? "网络设置" : activeSettingsSection === "diagnostics" ? "网络诊断" : activeSettingsSection === "about" ? "关于" : "设置"}</strong><button className="settings-collapse" onClick={() => { settingsPinnedRef.current = false; setSettingsExpanded(false); setSettingsPreview(null); }} aria-label="收起设置">‹</button></div>
          {activeSettingsSection === "download" && <><div className="settings-caption">接收文件保存到</div>
          <div className="settings-path" title={downloadDir || "每次接收时选择保存位置"}>{downloadDir || "每次接收时选择保存位置"}</div>
          <div className="settings-actions">
            <button className="small-button dark" onClick={chooseDownloadDir}>选择目录</button>
            {downloadDir && <button className="small-button light" onClick={clearDownloadDir}>恢复每次询问</button>}
          </div></>}
          {activeSettingsSection === "share" && <>
            <div className="settings-caption">结束分享时</div>
            <label className="settings-switch"><span><strong>传完再停</strong><small>开启后，点“结束分享”会先拒绝新连接，等正在传输的连接跑完再自动结束分享；关闭则立即中断所有下载。该设置对四位码、设备直连、浏览器三种分享都生效。</small></span><input type="checkbox" checked={drainOnStop} onChange={(event) => updateStopMode(event.target.checked)} /></label>
          </>}
          {activeSettingsSection === "about" && <>
            <div className="about-title">ZTDrop 局域网文件传输</div>
            <div className="about-list">
              <div className="about-row"><strong>版本</strong><span>v{APP_VERSION}</span></div>
              <div className="about-row"><strong>作者</strong><span>zhuroucheng</span></div>
              <div className="about-row"><strong>协议版本</strong><span>v{networkSnapshot?.protocol_version ?? 2}</span></div>
              <div className="about-row"><strong>数据目录</strong><span title={networkSnapshot?.data_dir}>{networkSnapshot?.data_dir ?? "读取中…"}</span></div>
            </div>
            <div className="about-actions">
              <button type="button" className="about-action" title="在系统浏览器中打开 GitHub 仓库" onClick={() => void openProjectPage(PROJECT_REPO_URL)}>
                <svg className="about-action-icon" viewBox="0 0 16 16" aria-hidden="true"><path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27s1.36.09 2 .27c1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 8.01 0 0 0 16 8c0-4.42-3.58-8-8-8Z" /></svg>
                <span>GitHub 仓库</span>
              </button>
              <button type="button" className="about-action" title="在系统浏览器中打开更新与下载页面" onClick={() => void openProjectPage(PROJECT_RELEASES_URL)}>
                <span>更新与下载</span>
              </button>
            </div>
            <div className="about-note">纯局域网直连，不经过公网中继、不做端口映射；四位分享码、设备直连聊天与浏览器分享共用同一套分享生命周期。</div>
          </>}
          {activeSettingsSection === "network" && <label className="settings-switch"><span><strong>仅使用物理局域网</strong><small>默认开启。按 Windows 的硬件网卡属性选择已连接的以太网或 Wi-Fi 及其局域网 IP，设备发现、消息和传输均指定该网卡；关闭后使用系统网络路由，也可能经过 VPN。</small></span><input type="checkbox" checked={physicalLanOnly} disabled={networkSettingBusy} onChange={(event) => void updateNetworkPreference(event.target.checked)} /></label>}
          {activeSettingsSection === "diagnostics" && <div className="diagnostics-content">
            {networkSnapshot ? <>
              <div className="diagnostics-line"><strong>本机</strong><span>{networkSnapshot.device_name} · {networkSnapshot.local_ip}</span></div>
              <div className="diagnostics-id" title={networkSnapshot.device_id}>设备 ID：{networkSnapshot.device_id}</div>
              <div className="diagnostics-line"><strong>版本</strong><span>ZTDrop v{APP_VERSION} · 协议 v{networkSnapshot.protocol_version}</span></div>
              <div className="diagnostics-line"><strong>当前网络接口</strong><span>{networkSnapshot.interface}</span></div>
              <div className="diagnostics-line"><strong>监听</strong><span>UDP {networkSnapshot.udp_port}：{networkSnapshot.port_health.udp ? "正常" : "异常"} · TCP {networkSnapshot.tcp_port}：{networkSnapshot.port_health.tcp ? "正常" : "异常"} · HTTP {networkSnapshot.web_port}：{networkSnapshot.port_health.http ? "正常" : "异常"}</span></div>
              <div className="diagnostics-line"><strong>并发传输</strong><span>{networkSnapshot.active_transfers} / {networkSnapshot.max_concurrent_transfers}</span></div>
              <div className="diagnostics-line"><strong>在线设备</strong><span>{networkSnapshot.peers.length} 台</span></div>
              {networkSnapshot.peers.length > 0 && <div className="diagnostics-peers">{networkSnapshot.peers.map((peer) => <div key={peer.node_id}><strong>{peer.device_name}</strong><span>{peer.ip} · 协议 v{peer.protocol_version ?? "未知"}</span></div>)}</div>}
              <div className="diagnostics-heading">当前分享码租约</div>
              {networkSnapshot.leases.length ? networkSnapshot.leases.map((lease) => <div className="diagnostics-line" key={lease.code}><strong>{lease.code}</strong><span>{lease.local ? "本机" : "远端"} · {lease.remaining_seconds} 秒</span></div>) : <div className="diagnostics-empty">暂无租约</div>}
              <div className="diagnostics-heading">活跃分享会话 <button onClick={refreshDiagnostics}>刷新</button></div>
              {networkSnapshot.sessions.length ? networkSnapshot.sessions.map((session) => <div className="diagnostics-session" key={session.kind + session.resource_id}>
                <div><strong>{session.kind === "code" ? "分享码" : session.kind === "direct" ? "设备直连" : "浏览器"} · {SESSION_STATE_LABEL[session.state]}</strong><span title={session.name}>{session.name}{session.is_folder ? " · 文件夹" : ""}{session.active_transfers ? ` · ${session.active_transfers} 条传输` : ""}{session.remaining_seconds !== null ? ` · 剩余 ${session.remaining_seconds} 秒` : ""}</span></div>
                <div className="diagnostics-session-actions">
                  <button onClick={() => void stopSession(session)}>{session.state === "draining" ? "立即停止" : "停止"}</button>
                </div>
              </div>) : <div className="diagnostics-empty">暂无活跃分享</div>}
              <div className="settings-actions">
                <button className="small-button dark" onClick={() => void exportDiagnostics()}>导出诊断报告</button>
                {networkSnapshot.sessions.length > 0 && <button className="small-button light" onClick={() => void stopAllSessions()}>停止全部分享</button>}
              </div>
              <div className="diagnostics-heading">最近网络事件 <button onClick={refreshDiagnostics}>刷新</button></div>
              <div className="diagnostics-filters">
                <button className={logFilter === "" ? "diag-chip active" : "diag-chip"} onClick={() => setLogFilter("")}>全部</button>
                {networkSnapshot.log_categories.map((category) => <button key={category} className={logFilter === category ? "diag-chip active" : "diag-chip"} onClick={() => setLogFilter(category)}>{category}</button>)}
              </div>
              <div className="diagnostics-logs">{networkSnapshot.logs.filter((entry) => !logFilter || entry.category === logFilter).slice(0, 60).map((entry, index) => <div key={entry.timestamp_ms + "-" + index}><time>{new Date(entry.timestamp_ms).toLocaleTimeString()}</time> <strong>[{entry.category}]</strong> {entry.message}</div>)}</div>
              {networkSnapshot.log_path && <div className="diagnostics-path" title={networkSnapshot.log_path}>日志文件：{networkSnapshot.log_path}</div>}
              <div className="diagnostics-path" title={networkSnapshot.data_dir}>数据目录：{networkSnapshot.data_dir}</div>
            </> : <div className="diagnostics-empty">正在读取网络状态…</div>}
          </div>}
          </div>
        </section>
      )}
      {isDragging && <div className="drop-overlay" aria-hidden="true">松开以添加文件</div>}
      {toastMessage && <div className="toast" role="status">{toastMessage}</div>}
    </div>
  );
}
