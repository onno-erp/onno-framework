import { text as t, useTranslate } from "@onno/widget-sdk";
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

  return <div ref={pane} aria-label={t("crm.inbox.list")} aria-busy={!!loadingMore}
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
        <p role="alert" className="mb-2 text-xs text-destructive">{t("crm.inbox.loadMoreError")}</p>
        <Button type="button" variant="ghost" size="sm" onClick={loadMore}>{t("crm.inbox.loadMoreRetry")}</Button>
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

/** Compact row anatomy; optional metadata and badges do not reserve empty rows. */
export function ConversationRowContent({avatar, title, timestamp, preview, metadata, badges, trailing, previewLines = 1}: {
  avatar?: ReactNode; title: ReactNode; timestamp?: ReactNode; preview?: ReactNode;
  metadata?: ReactNode; badges?: ReactNode; trailing?: ReactNode; previewLines?: 1 | 2;
}) {
  return <div className="flex min-w-0 items-center gap-3">
    {avatar}
    <div className="min-w-0 flex-1">
      <div className="flex min-w-0 items-baseline gap-2">
        <span className="min-w-0 flex-1 truncate text-sm font-medium text-foreground">{title}</span>
        {timestamp && <span className="shrink-0 text-xs tabular-nums text-muted-foreground">{timestamp}</span>}
      </div>
      {metadata && <div className="mt-0.5 truncate text-xs text-muted-foreground">{metadata}</div>}
      <div className="mt-1 flex min-w-0 items-center gap-2">
        <span className={`min-w-0 flex-1 text-[13px] leading-5 text-muted-foreground ${previewLines === 2 ? "line-clamp-2" : "truncate"}`}>{preview}</span>
        {trailing}
      </div>
      {badges && <div className="mt-1 flex flex-wrap gap-1">{badges}</div>}
    </div>
  </div>;
}
export type ConversationRowOptions = {showAccount?: boolean; showChannelBadge?: boolean; previewLines?: 1 | 2; badges?: {field:string; equals:string; label:string}[]};
export function conversationRowOptions(value?: string): ConversationRowOptions {
  try {
    const parsed = JSON.parse(value ?? "{}");
    return {badges:Array.isArray(parsed?.badges) ? parsed.badges.filter((b:any)=>b && typeof b.field === "string" && typeof b.equals === "string" && typeof b.label === "string").slice(0,30) : [], showAccount:parsed?.showAccount === true, showChannelBadge:parsed?.showChannelBadge !== false, previewLines:parsed?.previewLines === 2 ? 2 : 1};
  } catch {return {};}
}
