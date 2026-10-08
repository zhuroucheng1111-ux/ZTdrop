import { useEffect, useRef, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";

interface Props {
  open: boolean;
  title: string;
  onClose: () => void;
  children: ReactNode;
}

/** 收回仅关闭展示；分享的生命周期始终由调用者控制。 */
export default function BottomSheet({ open, title, onClose, children }: Props) {
  const [present, setPresent] = useState(open);
  const content = useRef(children);
  const close = useRef(onClose);
  const panel = useRef<HTMLDivElement>(null);
  close.current = onClose;
  if (open) content.current = children;

  useEffect(() => {
    if (open) { setPresent(true); return; }
    // Keep the last content mounted until the downward exit animation completes.
    const timer = setTimeout(() => setPresent(false), 350);
    return () => clearTimeout(timer);
  }, [open]);

  useEffect(() => {
    if (!present) return;
    const previous = document.activeElement as HTMLElement | null;
    const overflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    panel.current?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") { event.preventDefault(); event.stopPropagation(); close.current(); }
      if (event.key !== "Tab") return;
      const controls = Array.from(panel.current?.querySelectorAll<HTMLElement>(
        'button:not(:disabled), input:not(:disabled), a[href], [tabindex="0"]',
      ) ?? []).filter((element) => element.getClientRects().length > 0);
      const first = controls[0];
      const last = controls[controls.length - 1];
      if (!first) { event.preventDefault(); panel.current?.focus(); }
      else if (event.shiftKey && (document.activeElement === first || document.activeElement === panel.current)) {
        event.preventDefault(); last.focus();
      } else if (!event.shiftKey && (document.activeElement === last || document.activeElement === panel.current)) {
        event.preventDefault(); first.focus();
      }
    };
    document.addEventListener("keydown", onKey, true);
    return () => {
      document.removeEventListener("keydown", onKey, true);
      document.body.style.overflow = overflow;
      if (previous?.isConnected) previous.focus();
    };
  }, [present]);

  if (!present) return null;
  return createPortal(
    <div className={`share-sheet-overlay ${open ? "is-open" : "is-closing"}`}
      onClick={(event) => { if (event.target === event.currentTarget) onClose(); }}>
      <div ref={panel} className="share-sheet modal-card" role="dialog" aria-modal="true" aria-label={title} tabIndex={-1}>
        <button type="button" className="sheet-handle drag-bar" onClick={onClose} aria-label="收回分享弹窗" title="收回弹窗，分享继续" />
        <div className="sheet-body">{content.current}</div>
      </div>
    </div>, document.body,
  );
}
