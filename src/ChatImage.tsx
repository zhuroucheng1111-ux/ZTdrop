import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { invoke } from "@tauri-apps/api/core";
export const isChatImage = (name: string) => /\.(png|jpe?g|gif|webp|bmp)$/i.test(name);
export default function ChatImage({ id, name, ready }: { id: string; name: string; ready: boolean }) {
  const box = useRef<HTMLDivElement>(null);
  const [visible, setVisible] = useState(false);
  const [src, setSrc] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const previousFocus = useRef<HTMLElement | null>(null);
  useEffect(() => {
    const observer = new IntersectionObserver(entries => { if (entries.some(e => e.isIntersecting)) setVisible(true); }, { rootMargin: "160px" });
    if (box.current) observer.observe(box.current);
    return () => observer.disconnect();
  }, []);
  useEffect(() => {
    if (!ready || !visible) return;
    let active = true; setFailed(false);
    invoke<string | null>("get_message_image", { messageId: id }).then(value => {
      if (active) { setSrc(value); setFailed(!value); }
    }).catch(() => { if (active) setFailed(true); });
    return () => { active = false; };
  }, [id, ready, visible]);
  useEffect(() => {
    if (!expanded) return;
    const overflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    const close = (event: KeyboardEvent) => { if (event.key === "Escape") setExpanded(false); if (event.key === "Tab") event.preventDefault(); };
    document.addEventListener("keydown", close);
    return () => { document.body.style.overflow = overflow; document.removeEventListener("keydown", close); previousFocus.current?.focus(); };
  }, [expanded]);
  return <div ref={box} className="chat-image">
    {src && !failed ? <button type="button" className="chat-image-open" aria-label={`查看图片 ${name}`} onClick={() => { previousFocus.current = document.activeElement as HTMLElement; setExpanded(true); }}>
      <img src={src} alt={name} loading="lazy" onError={() => setFailed(true)} />
    </button> : <span className="chat-image-placeholder">{!ready ? "图片 · 接收后可预览" : failed ? "预览不可用，原文件仍保留" : "加载图片…"}</span>}
    {expanded && src && createPortal(<div className="chat-image-viewer" role="dialog" aria-modal="true" aria-label={`图片预览 ${name}`} onClick={() => setExpanded(false)}>
      <button autoFocus type="button" aria-label="关闭图片预览">×</button><img src={src} alt={name} />
    </div>, document.body)}
  </div>;
}
