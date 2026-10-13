import { useLayoutEffect, useState, type RefObject } from "react";

/** Nearest ancestor that can scroll vertically — the DivKit page wrapper on route surfaces. */
export function nearestScrollAncestor(el: HTMLElement): HTMLElement | null {
  for (let p = el.parentElement; p; p = p.parentElement) {
    const o = getComputedStyle(p).overflowY;
    if (o === "auto" || o === "scroll") return p;
  }
  return null;
}

/** Bottom padding contributed by layout wrappers between an island and its page scroller. */
export function trailingPaddingTo(el: HTMLElement, boundary: HTMLElement): number {
  let padding = 0;
  for (let p = el.parentElement; p && p !== boundary; p = p.parentElement) {
    padding += Number.parseFloat(getComputedStyle(p).paddingBottom) || 0;
  }
  return padding;
}

/**
 * Height that makes `ref` reach exactly the bottom of its page scroller, so the page itself never
 * scrolls and the element scrolls internally — the list surface's fill contract, shared with
 * dashboard widgets that opt in with {@code .fill()}. Measured in the scroller's content
 * coordinates (scroll-invariant) and re-measured on any shell reflow, so it converges to zero page
 * scroll. Null while disabled or unmeasured.
 */
export function useFillHeight(ref: RefObject<HTMLElement | null>, enabled: boolean, min = 320): number | null {
  const [height, setHeight] = useState<number | null>(null);
  useLayoutEffect(() => {
    if (!enabled) {
      setHeight(null);
      return;
    }
    const el = ref.current;
    if (!el) return;
    const measure = () => {
      const top = el.getBoundingClientRect().top;
      const sc = nearestScrollAncestor(el);
      if (sc) {
        const offsetInContent = top - sc.getBoundingClientRect().top - sc.clientTop + sc.scrollTop;
        setHeight(Math.max(min, Math.floor(sc.clientHeight - offsetInContent - trailingPaddingTo(el, sc))));
      } else {
        setHeight(Math.max(min, Math.floor(window.innerHeight - top)));
      }
    };
    measure();
    const raf = requestAnimationFrame(measure);
    window.addEventListener("resize", measure);
    const ro = new ResizeObserver(measure);
    ro.observe(document.body);
    return () => {
      cancelAnimationFrame(raf);
      window.removeEventListener("resize", measure);
      ro.disconnect();
    };
  }, [ref, enabled, min]);
  return height;
}
