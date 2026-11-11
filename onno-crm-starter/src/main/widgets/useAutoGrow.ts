import { useLayoutEffect, type RefObject } from "react";

/**
 * Grows a textarea with its content up to the CSS `max-height` it is given, then lets it scroll. The
 * height follows the text on every change, so a short reply stays one compact box and a long one
 * gets room without the reader dragging a handle.
 */
export function useAutoGrow(ref: RefObject<HTMLTextAreaElement | null>, value: string, enabled = true) {
  useLayoutEffect(() => {
    const element = ref.current;
    if (!element) return;
    if (!enabled) { element.style.height = ""; element.style.overflowY = ""; return; }
    element.style.height = "auto";
    const style = getComputedStyle(element);
    const max = parseFloat(style.maxHeight);
    const borders = element.offsetHeight - element.clientHeight;
    const wanted = element.scrollHeight + Math.max(0, borders);
    // Not laid out (hidden, or no layout engine): leave the stylesheet's height alone.
    if (!wanted) { element.style.height = ""; return; }
    const limit = Number.isFinite(max) && max > 0 ? max : Infinity;
    element.style.height = `${Math.min(wanted, limit)}px`;
    element.style.overflowY = wanted > limit ? "auto" : "hidden";
  }, [ref, value, enabled]);
}
