import { useEffect } from "react";

export type Keybinding = {
  key: string;
  mod?: boolean;
  shift?: boolean;
  alt?: boolean;
  ctrl?: boolean;
  meta?: boolean;
  enabled?: boolean;
  allowInEditable?: boolean;
  preventDefault?: boolean;
  run: (event: KeyboardEvent) => void;
};

export function isEditableTarget(target: EventTarget | null): boolean {
  const el = target as HTMLElement | null;
  if (!el) return false;
  return (
    el.isContentEditable ||
    el.tagName === "INPUT" ||
    el.tagName === "TEXTAREA" ||
    el.tagName === "SELECT" ||
    el.closest?.("[contenteditable=true]") != null
  );
}

function isVisibleElement(el: Element): boolean {
  const node = el as HTMLElement;
  return node.offsetParent !== null || node.getClientRects().length > 0 || getComputedStyle(node).position === "fixed";
}

export function isInteractiveLayerOpen(): boolean {
  if (typeof document === "undefined") return false;
  return Array.from(
    document.querySelectorAll(
      [
        '[role="dialog"][aria-modal="true"]',
        '[role="alertdialog"][aria-modal="true"]',
        '[role="menu"]',
        '[data-vaul-drawer][data-state="open"]',
        '[data-radix-popper-content-wrapper]',
      ].join(",")
    )
  ).some(isVisibleElement);
}

function matches(event: KeyboardEvent, binding: Keybinding): boolean {
  if (binding.enabled === false) return false;
  if (!binding.allowInEditable && isEditableTarget(event.target)) return false;
  if (event.key.toLowerCase() !== binding.key.toLowerCase()) return false;

  const wantsMod = binding.mod === true;
  const modPressed = event.metaKey || event.ctrlKey;
  if (wantsMod !== modPressed && binding.mod !== undefined) return false;
  if (binding.shift !== undefined && binding.shift !== event.shiftKey) return false;
  if (binding.alt !== undefined && binding.alt !== event.altKey) return false;
  if (binding.ctrl !== undefined && binding.ctrl !== event.ctrlKey) return false;
  if (binding.meta !== undefined && binding.meta !== event.metaKey) return false;

  if (!wantsMod) {
    if (binding.ctrl === undefined && event.ctrlKey) return false;
    if (binding.meta === undefined && event.metaKey) return false;
  }
  if (binding.alt === undefined && event.altKey) return false;
  if (binding.shift === undefined && event.shiftKey) return false;

  return true;
}

export function useGlobalKeybindings(bindings: Keybinding[]) {
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      for (const binding of bindings) {
        if (!matches(event, binding)) continue;
        if (binding.preventDefault !== false) event.preventDefault();
        binding.run(event);
        return;
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [bindings]);
}

export function shortcutLabel(binding: Pick<Keybinding, "key" | "mod" | "shift" | "alt" | "ctrl" | "meta">): string {
  const isMac =
    typeof navigator !== "undefined" &&
    (/Mac|iPhone|iPad|iPod/.test(navigator.platform) || navigator.userAgent.includes("Mac OS"));
  const parts: string[] = [];
  if (binding.mod) parts.push(isMac ? "⌘" : "Ctrl");
  if (binding.ctrl) parts.push(isMac ? "⌃" : "Ctrl");
  if (binding.meta) parts.push(isMac ? "⌘" : "Meta");
  if (binding.alt) parts.push(isMac ? "⌥" : "Alt");
  if (binding.shift) parts.push(isMac ? "⇧" : "Shift");
  parts.push(labelKey(binding.key));
  return isMac ? parts.join("") : parts.join("+");
}

function labelKey(key: string): string {
  if (key === " ") return "Space";
  if (key.length === 1) return key.toUpperCase();
  return key;
}
