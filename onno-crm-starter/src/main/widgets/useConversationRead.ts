import { useEffect, useRef, type RefObject } from "react";

/** Acknowledge loaded messages only while their conversation is actually visible. */
export function useConversationRead(
  viewport: RefObject<HTMLElement | null>,
  revision: string | null,
  acknowledge: () => Promise<unknown>,
) {
  const completed = useRef<string | null>(null);
  const pending = useRef(new Set<string>());
  useEffect(() => {
    const element = viewport.current;
    if (!element || !revision) return;
    let active = true;
    const read = () => {
      if (!active || document.visibilityState !== "visible" || !element.getClientRects().length
          || completed.current === revision || pending.current.has(revision)) return;
      pending.current.add(revision);
      void acknowledge().then(() => {
        if (active) completed.current = revision;
      }).catch(() => {
        // Keep unread state on failure; retry on the next visibility/focus/live update.
      }).finally(() => pending.current.delete(revision));
    };
    read();
    const observer = new IntersectionObserver(entries => {
      if (entries.some(entry => entry.isIntersecting)) read();
    });
    observer.observe(element);
    document.addEventListener("visibilitychange", read);
    window.addEventListener("focus", read);
    return () => {
      active = false;
      observer.disconnect();
      document.removeEventListener("visibilitychange", read);
      window.removeEventListener("focus", read);
    };
  }, [viewport, revision, acknowledge]);
}
