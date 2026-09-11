import { useEffect, useRef, type ReactNode } from "react";
import { Button, type ListRendererProps } from "@onno/widget-sdk";

export type ConversationPagination = Pick<ListRendererProps, "hasMore" | "loadingMore" | "loadMoreFailed" | "loadMore">;

/** Both root and folder panes page the same host-owned, filtered conversation feed. */
export function ConversationList({ children, active = true, hasMore, loadingMore, loadMoreFailed, loadMore }:
  ConversationPagination & { children: ReactNode; active?: boolean }) {
  const pane = useRef<HTMLDivElement>(null);
  const sentinel = useRef<HTMLDivElement>(null);
  // Paging is the scroll's job, not the reader's. A sentinel below the last row pulls the next
  // window in while it is still off-screen, so a long inbox keeps unrolling as you scroll instead
  // of stopping at a button. A scroll handler cannot do this on its own: a pane that is shorter
  // than its content, or one that grows without a scroll event, would simply never ask for more.
  const ready = active && !!hasMore && !!loadMore && !loadingMore && !loadMoreFailed;
  useEffect(() => {
    const target = sentinel.current;
    // Where the observer is missing (jsdom, very old engines) the scroll handler below still pages.
    if (!ready || !target || typeof IntersectionObserver === "undefined") return;
    const observer = new IntersectionObserver(
      entries => { if (entries.some(entry => entry.isIntersecting)) loadMore?.(); },
      // Start the fetch a screenful early so the rows are there by the time they are scrolled to.
      { root: pane.current, rootMargin: "240px" }
    );
    observer.observe(target);
    return () => observer.disconnect();
    // `loadingMore` flipping back to false re-arms this: if the sentinel is still in view the next
    // window follows immediately, which is what makes a fast flick through 250 chats continuous.
  }, [ready, loadMore]);

  return <div ref={pane} aria-label="Conversation list" aria-busy={!!loadingMore}
    className="min-h-0 min-w-0 w-full max-w-full flex-1 space-y-0.5 overflow-x-hidden overflow-y-auto overscroll-contain p-1.5"
    onScroll={event => {
      // Belt and braces to the sentinel: a pane that scrolls without ever moving the sentinel into
      // view (and any engine without IntersectionObserver) still pulls the next window.
      const target = event.currentTarget;
      if (ready && target.scrollTop + target.clientHeight >= target.scrollHeight - 160) loadMore?.();
    }}>
    {children}
    {active && hasMore && loadMore && <div className="px-3 py-2 text-center">
      {/* Only a failure earns a control: there is nothing to press while paging is working. */}
      {loadMoreFailed ? <>
        <p role="alert" className="mb-2 text-xs text-destructive">Could not load more conversations.</p>
        <Button type="button" variant="ghost" size="sm" onClick={loadMore}>Retry loading conversations</Button>
      </> : <p role="status" className="text-xs text-muted-foreground">
        {loadingMore ? "Loading more conversations…" : " "}
      </p>}
      <div ref={sentinel} aria-hidden="true" className="h-px w-full" />
    </div>}
  </div>;
}

/** An unknown server total must never masquerade as the total matching the current query. */
export function conversationCount(total: number | null | undefined, loaded: number): string {
  return total == null ? `${loaded} loaded chats` : `${total} chats`;
}
