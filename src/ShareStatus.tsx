import { useEffect, useRef, useState } from "react";

export interface ShareFile { name: string; size: number }
export interface ShareReceiver {
  id: string;
  name: string;
  ip: string;
  transferred: number;
  total: number;
  speed?: number;
  status: string;
}
interface Props {
  files: ShareFile[];
  receivers: ShareReceiver[];
  code?: string;
  url?: string;
  qrSvg?: string;
  remaining?: string;
  visitors: number;
  downloads: number;
  stopMode: string;
  busy: boolean;
  copied?: boolean;
  onCopy: () => void;
  onStop: () => void;
}

function bytes(size: number) {
  if (size < 1024) return `${size} B`;
  if (size < 1048576) return `${(size / 1024).toFixed(1)} KB`;
  if (size < 1073741824) return `${(size / 1048576).toFixed(1)} MB`;
  return `${(size / 1073741824).toFixed(1)} GB`;
}

/** DOM 顺序和类名直接对应 docs/电脑端.html 的两个分享弹窗。 */
export default function ShareStatus({ files, receivers, code, url, qrSvg, remaining,
  visitors, downloads, stopMode, busy, copied, onCopy, onStop }: Props) {
  const [popover, setPopover] = useState<"files" | "devices" | null>(null);
  const row = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!popover) return;
    const outside = (event: PointerEvent) => {
      if (!row.current?.contains(event.target as Node)) setPopover(null);
    };
    document.addEventListener("pointerdown", outside, true);
    return () => document.removeEventListener("pointerdown", outside, true);
  }, [popover]);
  const fileCount = files.length >= 500 ? "500+" : String(files.length);
  return <div className="reference-share-content">
    <div className="float-trigger-row" ref={row}>
      <button type="button" className="float-trigger" aria-expanded={popover === "files"}
        onClick={() => setPopover(popover === "files" ? null : "files")}>
        <span aria-hidden="true">📁</span> 分享内容 <span className="badge">{fileCount} 个文件</span>
        <span className={popover === "files" ? "arrow open" : "arrow"} aria-hidden="true" />
      </button>
      <button type="button" className="float-trigger" aria-expanded={popover === "devices"}
        onClick={() => setPopover(popover === "devices" ? null : "devices")}>
        <span aria-hidden="true">🖥</span> 接收设备 <span className="badge">{visitors}</span>
        {downloads > 0 && <span className="dot" aria-hidden="true" />}
        <span className={popover === "devices" ? "arrow open" : "arrow"} aria-hidden="true" />
      </button>
      <div className={`float-popover ${popover === "files" ? "show" : ""}`} aria-hidden={popover !== "files"}>
        <div className="po-header">分享内容 <span>{fileCount} 个文件</span></div>
        <div className="po-body">{files.map((file, index) => <div className="po-row" key={file.name + index}>
          <div className="po-left"><span className="po-icon" aria-hidden="true">📄</span><span className="po-name" title={file.name}>{file.name}</span></div>
          <span className="po-size">{bytes(file.size)}</span>
        </div>)}</div>
        <div className="po-note">{files.length >= 500 ? "仅显示前 500 项" : files.length > 1 ? "支持一次选中多个文件" : "单文件分享"}</div>
      </div>
      <div className={`float-popover receiver-popover ${popover === "devices" ? "show" : ""}`} aria-hidden={popover !== "devices"}>
        <div className="po-header">接收设备 <span>{downloads} 下载中</span></div>
        <div className="po-body">
          {!receivers.length && <div className="po-note">等待设备连接</div>}
          {receivers.map((receiver) => {
            const completed = ["completed", "已完成", "完成"].includes(receiver.status);
            const active = ["sending", "transferring", "downloading", "下载中"].includes(receiver.status);
            const percent = receiver.total ? Math.min(100, Math.round(receiver.transferred / receiver.total * 100)) : completed ? 100 : 0;
            const label = completed ? "已完成" : active ? "下载中" : ["error", "stopped", "中断", "已中断", "失败", "下载失败", "已停止"].includes(receiver.status) ? "已中断" : "等待连接";
            return <div className="po-dev-row" key={receiver.id}>
              <div className="po-dev-hdr"><span className={`po-dev-icon ${completed ? "done" : ""}`} aria-hidden="true">🖥</span>
                <span className="po-dev-name" title={receiver.ip}>{receiver.name}</span>
                <span className={`po-dev-speed ${completed ? "done" : ""}`}>{completed ? "✓ 完成" : receiver.speed !== undefined && active ? `${receiver.speed.toFixed(1)} MB/s` : label}</span>
              </div>
              <div className="po-bar-bg"><div className={`po-bar ${completed ? "done" : ""}`} style={{ width: `${percent}%` }} /></div>
              <div className="po-pct"><span>{label}</span><span>{percent}%</span></div>
            </div>;
          })}
        </div>
      </div>
    </div>
    {code ? <>
      <div className="ripple-wrap">
        {Array.from({ length: 4 }, (_, index) => <div className="ripple-ring" aria-hidden="true" key={index} />)}
        <button type="button" className="ripple-core" onClick={onCopy} aria-label={`复制分享码 ${code}`} title="点击分享码复制">
          <span className="code-label">{copied ? "已复制" : "分享码"}</span><span className="code-digits">{code}</span>
        </button>
      </div>
      <div className="modal-time">⏱ {remaining}</div>
    </> : <>
      <div className="qr-wrap"><div className="qr-box"><img alt="下载二维码" src={"data:image/svg+xml," + encodeURIComponent(qrSvg ?? "")} /></div>
        <div className="qr-label">扫描二维码快速下载</div>
      </div>
      <div className="link-box"><div className="link-txt">{url}</div></div>
    </>}
    <div className="modal-btns">
      {!code && <button className="m-btn copy" type="button" onClick={onCopy}>📋 复制链接</button>}
      <button className="m-btn stop" type="button" disabled={busy} onClick={onStop}>{busy ? "正在结束…" : "⏹ 停止分享"}</button>
    </div>
    <div className="modal-stats"><span>访问 {visitors} 台</span>·<span>下载中 {downloads} 个</span>·<span>{stopMode}</span></div>
  </div>;
}
