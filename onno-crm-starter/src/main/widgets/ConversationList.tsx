import type { ReactNode } from "react";
import { Button, type ListRendererProps } from "@onno/widget-sdk";

export type ConversationPagination = Pick<ListRendererProps, "hasMore" | "loadingMore" | "loadMoreFailed" | "loadMore">;

/** Both root and folder panes page the same host-owned, filtered conversation feed. */
export function ConversationList({ children, active = true, hasMore, loadingMore, loadMoreFailed, loadMore }:
  ConversationPagination & { children: ReactNode; active?: boolean }) {
  return <div aria-label="Conversation list" aria-busy={!!loadingMore}
    className="min-h-0 min-w-0 w-full max-w-full flex-1 space-y-0.5 overflow-x-hidden overflow-y-auto overscroll-contain p-1.5"
    onScroll={event => {
      const pane = event.currentTarget;
      if (active && hasMore && !loadingMore && !loadMoreFailed && pane.scrollTop + pane.clientHeight >= pane.scrollHeight - 160) loadMore?.();
    }}>
    {children}
    {active && hasMore && loadMore && <div className="px-3 py-2 text-center">
      {loadMoreFailed && <p role="alert" className="mb-2 text-xs text-destructive">Could not load more conversations.</p>}
      <Button type="button" variant="ghost" size="sm" disabled={loadingMore} onClick={loadMore}>
        {loadingMore ? "Loading conversations…" : loadMoreFailed ? "Retry loading conversations" : "Load more conversations"}
      </Button>
    </div>}
  </div>;
}
