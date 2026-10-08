import { useEffect, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import type { WebShareView } from "./types";
import BottomSheet from "./BottomSheet";
import ShareStatus from "./ShareStatus";

/** 有效期选项：列表和触发器共用同一份定义，保证收起/展开样式与文案一致。 */
const LIFETIME_OPTIONS = [
  { value: "manual", label: "直到手动结束" },
  { value: "900", label: "15 分钟" },
  { value: "3600", label: "1 小时" },
  { value: "28800", label: "8 小时" },
  { value: "86400", label: "24 小时" },
] as const;

interface Props {
  selectedFilePath: string;
  isDragging: boolean;
  onPick: () => void;
  reportStatus: (message: string) => void;
  share: WebShareView | null;
  setShare: (share: WebShareView | null) => void;
  /** 设置 →「分享」里的结束方式：true = 传完再停。 */
  drainOnStop: boolean;
  visible: boolean;
}

export default function WebSharePanel({ selectedFilePath, isDragging, onPick, reportStatus, share, setShare, drainOnStop, visible }: Props) {
  const [sheetOpen, setSheetOpen] = useState(false);
  useEffect(() => { setSheetOpen(Boolean(share)); }, [share?.token]);
  // 默认手动结束：不设自动过期，由用户点“结束共享”收尾。
  const [lifetime, setLifetime] = useState("manual");
  const [busy, setBusy] = useState(false);
  const [lifetimeOpen, setLifetimeOpen] = useState(false);
  const lifetimeLabel = LIFETIME_OPTIONS.find((option) => option.value === lifetime)?.label ?? "直到手动结束";

  // 点击下拉以外的地方收起菜单。
  useEffect(() => {
    if (!lifetimeOpen) return;
    const onPointerDown = (event: PointerEvent) => {
      const target = event.target as HTMLElement | null;
      if (target?.closest(".select-wrap")) return;
      setLifetimeOpen(false);
    };
    document.addEventListener("pointerdown", onPointerDown, true);
    return () => document.removeEventListener("pointerdown", onPointerDown, true);
  }, [lifetimeOpen]);

  useEffect(() => {
    if (!share) return;
    const refresh = () => {
      invoke<WebShareView>("get_web_share", { token: share.token }).then(setShare).catch(() => setShare(null));
    };
    refresh();
    const timer = setInterval(refresh, 3000);
    return () => clearInterval(timer);
  }, [share?.token]);

  // 后端把“传完再停”的浏览器分享收尾后，前端同步撤掉分享卡片。
  useEffect(() => {
    const listener = listen<{ id: string; reason: string }>("share-ended", (event) => {
      if (share?.token === event.payload.id) {
        setShare(null);
        reportStatus("浏览器分享已结束");
      }
    });
    return () => { listener.then((unlisten) => unlisten()); };
  }, [share?.token]);

  async function create() {
    if (!selectedFilePath || busy) return;
    setBusy(true);
    try {
      setShare(await invoke<WebShareView>("create_web_share", {
        filePath: selectedFilePath,
        lifetimeSeconds: lifetime === "manual" ? null : Number(lifetime),
      }));
    } catch (error) { reportStatus("创建浏览器分享失败：" + String(error)); }
    finally { setBusy(false); }
  }

  async function stop() {
    if (!share || busy) return;
    setBusy(true);
    try {
      if (drainOnStop) {
        await invoke("stop_session", { id: share.token, shareId: share.share_id, drain: true });
        reportStatus("已设为传完再停：不再接受新访问，当前下载结束会自动收尾");
        return;
      }
      await invoke("stop_web_share", { token: share.token, shareId: share.share_id });
      setShare(null);
      reportStatus("浏览器分享已结束");
    } catch (error) { reportStatus("结束分享失败：" + String(error)); }
    finally { setBusy(false); }
  }

  async function copy() {
    if (!share) return;
    try { await navigator.clipboard.writeText(share.url); reportStatus("链接已复制"); }
    catch (error) { reportStatus("复制失败：" + String(error)); }
  }

  return <section className="work-card web-card">
    {!share ? <div className="web-create">
      <div className={isDragging ? "drop-zone dragging" : "drop-zone"}><strong>{selectedFilePath ? selectedFilePath.split(/[\\/]/).pop() : "拖入一个文件"}</strong><span>浏览器分享支持单个文件</span></div>
      <button className="small-button dark" onClick={onPick}>浏览文件</button>
      <label>有效期
        <span className="select-wrap">
          <button type="button" className={lifetimeOpen ? "select-trigger open" : "select-trigger"} aria-haspopup="listbox" aria-expanded={lifetimeOpen} onClick={() => setLifetimeOpen((open) => !open)}>
            <span>{lifetimeLabel}</span>
            <span className="select-arrow" aria-hidden="true">▾</span>
          </button>
          {lifetimeOpen && <div className="select-menu" role="listbox">
            {LIFETIME_OPTIONS.map((option) => <button key={option.value} type="button" role="option" aria-selected={lifetime === option.value} className={lifetime === option.value ? "select-option active" : "select-option"} onClick={() => { setLifetime(option.value); setLifetimeOpen(false); }}>{option.label}</button>)}
          </div>}
        </span>
      </label>
      <button className="primary-button" disabled={!selectedFilePath || busy} onClick={create}>{busy ? "正在创建…" : "创建分享"}</button>
    </div> : <div className="web-active web-summary">
      <span className="sharing-indicator"><span className="share-pulse" aria-hidden="true" />分享正在进行</span>
      <strong className="drop-file-name">{share.file_name}</strong>
      <span>访问 {share.visitors} 台 · 下载中 {share.downloads} 个</span>
      <button className="primary-button" onClick={() => setSheetOpen(true)}>查看分享</button>
      <button className="small-button outline-red" disabled={busy} onClick={stop}>停止分享</button>
    </div>}
    <BottomSheet open={sheetOpen && Boolean(share) && visible} title="直链分享" onClose={() => setSheetOpen(false)}>
      {share && <ShareStatus files={[{ name: share.file_name, size: share.file_size }]}
        receivers={share.sessions.map((session, index) => ({ id: session.ip + index, name: session.browser, ip: session.ip,
          transferred: session.transferred, total: session.total, status: session.status }))}
        url={share.url} qrSvg={share.qr_svg} visitors={share.visitors} downloads={share.downloads}
        stopMode={drainOnStop ? "传完再停" : share.expires_in != null ? `剩余 ${Math.ceil(share.expires_in / 60)} 分钟` : "手动结束"}
        busy={busy} onCopy={() => void copy()} onStop={() => void stop()} />}

    </BottomSheet>
  </section>;
}
