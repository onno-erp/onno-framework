import { useLayoutEffect, useRef, type RefObject } from "react";

/** Direct children marked data-slide-page keep their resting layout; only navigation animates.
 * Keep the outgoing child mounted until onFinish. Initial renders and breakpoint changes snap. */
export function usePageSlide(
  root: RefObject<HTMLElement | null>, key: string, enabled = true,
  onFinish?: () => void,
) {
  const previous = useRef({ key, enabled });
  const finish = useRef(onFinish);
  finish.current = onFinish;
  useLayoutEffect(() => {
    const before = previous.current;
    previous.current = { key, enabled };
    const pages = Array.from(root.current?.children ?? []).filter((node): node is HTMLElement => node instanceof HTMLElement && node.hasAttribute("data-slide-page"));
    const incoming = pages.find(page => page.dataset.slidePage === key);
    const outgoing = pages.find(page => page.dataset.slidePage === before.key);
    if (!enabled || !before.enabled || before.key === key || !incoming || !outgoing ||
        !incoming.animate || window.matchMedia?.("(prefers-reduced-motion: reduce)").matches) {
      finish.current?.();
      return;
    }
    const direction = pages.indexOf(incoming) > pages.indexOf(outgoing) ? 1 : -1;
    const styles = getComputedStyle(root.current!);
    const durationValue = styles.getPropertyValue("--page-slide-dur").trim() || "300ms";
    const duration = parseFloat(durationValue) * (durationValue.endsWith("ms") ? 1 : 1000);
    const easing = styles.getPropertyValue("--page-slide-ease").trim() || "cubic-bezier(0.2,0.7,0.2,1)";
    const animations = [
      incoming.animate([{ transform: `translateX(${direction * 100}%)` }, { transform: "translateX(0)" }], { duration, easing }),
      outgoing.animate([{ transform: "translateX(0)" }, { transform: `translateX(${-direction * 100}%)` }], { duration, easing }),
    ];
    let cancelled = false;
    Promise.all(animations.map(animation => animation.finished)).then(() => {
      if (!cancelled) finish.current?.();
    }).catch(() => {});
    return () => { cancelled = true; animations.forEach(animation => animation.cancel()); };
  }, [root, key, enabled]);
}
