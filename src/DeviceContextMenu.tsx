import { useLayoutEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";

interface CommonProps {
  x: number; y: number; name: string; disabled: boolean;
  onClose: () => void; onReturnFocus: () => void;
}
type Props = CommonProps & (
  { onClear: () => void; onRemove: () => void; onCopy?: never; onDelete?: never } |
  { onCopy?: () => void; onDelete: () => void; onClear?: never; onRemove?: never }
);

/** 独立门户避免侧栏裁切；操作绑定右键的设备或消息，不依赖当前会话。 */
export default function DeviceContextMenu({ x, y, name, disabled, onClose, onReturnFocus, onClear, onRemove, onCopy, onDelete }: Props) {
  const menu = useRef<HTMLDivElement>(null);
  const [position, setPosition] = useState({ left: x, top: y });
  const callbacks = useRef({ onClose, onReturnFocus });
  callbacks.current = { onClose, onReturnFocus };
  useLayoutEffect(() => {
    const element = menu.current;
    if (!element) return;
    const bounds = element.getBoundingClientRect();
    setPosition({ left: Math.max(8, Math.min(x, window.innerWidth - bounds.width - 8)),
      top: Math.max(8, Math.min(y, window.innerHeight - bounds.height - 8)) });
    element.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus();
    const outside = (event: PointerEvent) => {
      if (!element.contains(event.target as Node)) callbacks.current.onClose();
    };
    const dismiss = () => callbacks.current.onClose();
    document.addEventListener("pointerdown", outside, true);
    window.addEventListener("scroll", dismiss, true);
    window.addEventListener("resize", dismiss);
    window.addEventListener("blur", dismiss);
    return () => {
      document.removeEventListener("pointerdown", outside, true);
      window.removeEventListener("scroll", dismiss, true);
      window.removeEventListener("resize", dismiss);
      window.removeEventListener("blur", dismiss);
    };
  }, [x, y]);
  return createPortal(<div ref={menu} className="device-context-menu" role="menu" aria-label={onDelete ? "消息操作" : `${name}的设备操作`}
    style={position} onKeyDown={(event) => {
      if (event.key === "Escape") { event.preventDefault(); onClose(); onReturnFocus(); return; }
      if (event.key === "Tab") { onClose(); return; }
      if (!["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) return;
      event.preventDefault();
      const items = Array.from(menu.current?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? []);
      if (!items.length) return;
      const current = items.indexOf(document.activeElement as HTMLButtonElement);
      const next = event.key === "Home" ? 0 : event.key === "End" ? items.length - 1 :
        (current + (event.key === "ArrowDown" ? 1 : -1) + items.length) % items.length;
      items[next].focus();
    }}>
    <div className="device-context-title">{name}</div>
    {onDelete ? <>
      {onCopy && <button type="button" role="menuitem" aria-label="复制消息" disabled={disabled} onClick={onCopy}>复制</button>}
      <button type="button" role="menuitem" className="danger" aria-label="删除这条本机记录" disabled={disabled} onClick={onDelete}>删除</button>
    </> : <>
      <button type="button" role="menuitem" disabled={disabled} onClick={onClear}>清空记录</button>
      <button type="button" role="menuitem" className="danger" disabled={disabled} onClick={onRemove}>删除好友</button>
    </>}
  </div>, document.body);
}
