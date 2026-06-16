import { useCallback, useEffect, useRef, useState } from "react";
import { toast } from "sonner";
import { Send, Trash2, MessageSquare } from "lucide-react";
import { api, ApiError, type CommentView } from "@/lib/api";
import { cn } from "@/lib/utils";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";

/** The entity a thread hangs off — the same triple the detail route uses. */
export type CommentTarget = { kind: "catalogs" | "documents"; name: string; id: string };

// Up to two initials from a display name, for the author avatar.
function initials(name: string | null): string {
  if (!name) return "?";
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return "?";
  return (parts[0][0] + (parts.length > 1 ? parts[parts.length - 1][0] : "")).toUpperCase();
}

// A compact "time ago" for recent comments, falling back to an absolute date for older ones.
// createdAt is a server LocalDateTime (no zone), parsed in the viewer's local time.
function timeAgo(iso: string | null): string {
  if (!iso) return "";
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return "";
  const secs = Math.round((Date.now() - then) / 1000);
  if (secs < 45) return "just now";
  if (secs < 90) return "a minute ago";
  const mins = Math.round(secs / 60);
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.round(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.round(hours / 24);
  if (days < 7) return `${days}d ago`;
  return new Date(iso).toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });
}

function absolute(iso: string | null): string | undefined {
  if (!iso) return undefined;
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? undefined : d.toLocaleString();
}

/**
 * The discussion thread panel rendered on every catalog/document detail surface (the
 * {@code onec-comments} div-custom). Loads the thread from {@code /api/comments/...}, posts new
 * comments (author stamped server-side), and deletes one's own. Self-contained: it re-fetches
 * after each write rather than relying on the surface's SSE refresh, so it never touches the
 * shared content-pane machinery.
 */
export function EntityCommentsWidget({ target }: { target: CommentTarget }) {
  const { kind, name, id } = target;
  const [comments, setComments] = useState<CommentView[] | null>(null);
  const [body, setBody] = useState("");
  const [busy, setBusy] = useState(false);
  const taRef = useRef<HTMLTextAreaElement | null>(null);

  const load = useCallback(() => {
    let cancelled = false;
    api
      .listComments(kind, name, id)
      .then((rows) => {
        if (!cancelled) setComments(rows);
      })
      .catch(() => {
        if (!cancelled) setComments([]);
      });
    return () => {
      cancelled = true;
    };
  }, [kind, name, id]);

  useEffect(() => load(), [load]);

  const submit = async () => {
    const text = body.trim();
    if (!text || busy) return;
    setBusy(true);
    try {
      const saved = await api.addComment(kind, name, id, text);
      setComments((prev) => [...(prev ?? []), saved]);
      setBody("");
      taRef.current?.focus();
    } catch (e) {
      // 422 (empty/too long) and other failures already surface a toast from the api layer;
      // re-toast only if it somehow didn't carry a message.
      if (!(e instanceof ApiError)) toast.error("Couldn't post comment");
    } finally {
      setBusy(false);
    }
  };

  const remove = async (commentId: string) => {
    // Optimistic: drop it immediately, restore on failure.
    const prev = comments;
    setComments((cur) => (cur ?? []).filter((c) => c.id !== commentId));
    try {
      await api.deleteComment(commentId);
    } catch {
      setComments(prev ?? null);
    }
  };

  const onKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    // Cmd/Ctrl+Enter sends, matching the chat-style affordance users expect from a compose box.
    if ((e.metaKey || e.ctrlKey) && e.key === "Enter") {
      e.preventDefault();
      void submit();
    }
  };

  const count = comments?.length ?? 0;

  return (
    <div className="mt-4 rounded-2xl border border-border bg-card p-4 sm:p-5">
      <div className="mb-3 flex items-center gap-2">
        <MessageSquare className="size-4 text-muted-foreground" aria-hidden="true" />
        <h2 className="text-sm font-semibold text-foreground">Comments</h2>
        {count > 0 ? <span className="text-sm text-muted-foreground">{count}</span> : null}
      </div>

      {comments === null ? (
        <p className="text-sm text-muted-foreground">Loading…</p>
      ) : comments.length === 0 ? (
        <p className="text-sm text-muted-foreground">No comments yet. Start the conversation below.</p>
      ) : (
        <ul className="space-y-3">
          {comments.map((c) => (
            <li key={c.id} className="group flex gap-3">
              <Avatar className="mt-0.5 size-7 shrink-0">
                {c.authorAvatarUrl ? (
                  <AvatarImage src={c.authorAvatarUrl} alt={c.authorName ?? ""} />
                ) : null}
                <AvatarFallback className="text-[11px]">{initials(c.authorName)}</AvatarFallback>
              </Avatar>
              <div className="min-w-0 flex-1">
                <div className="flex items-baseline gap-2">
                  <span className="text-sm font-medium text-foreground">
                    {c.authorName || "Unknown"}
                  </span>
                  <span className="text-xs text-muted-foreground" title={absolute(c.createdAt)}>
                    {timeAgo(c.createdAt)}
                    {c.editedAt ? " · edited" : ""}
                  </span>
                  {c.canDelete ? (
                    <button
                      type="button"
                      onClick={() => remove(c.id)}
                      aria-label="Delete comment"
                      title="Delete comment"
                      className="ml-auto grid size-6 shrink-0 place-items-center rounded-md text-muted-foreground opacity-0 transition-colors hover:bg-accent hover:text-destructive focus-visible:opacity-100 group-hover:opacity-100"
                    >
                      <Trash2 className="size-3.5" aria-hidden="true" />
                    </button>
                  ) : null}
                </div>
                <p className="whitespace-pre-wrap break-words text-sm text-foreground">{c.body}</p>
              </div>
            </li>
          ))}
        </ul>
      )}

      <div className="mt-4 flex items-end gap-2">
        <textarea
          ref={taRef}
          value={body}
          onChange={(e) => setBody(e.target.value)}
          onKeyDown={onKeyDown}
          rows={2}
          placeholder="Write a comment…"
          className={cn(
            "flex min-h-[2.5rem] w-full resize-y rounded-md border border-input bg-background px-3 py-2 text-sm",
            "ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none",
            "focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:opacity-50"
          )}
          disabled={busy}
        />
        <button
          type="button"
          onClick={submit}
          disabled={busy || body.trim() === ""}
          className={cn(
            "inline-flex h-10 items-center gap-1.5 rounded-md bg-primary px-3.5 text-sm font-medium",
            "text-primary-foreground transition-colors hover:bg-primary/90 disabled:opacity-50"
          )}
        >
          <Send className="size-4" aria-hidden="true" />
          {busy ? "Posting…" : "Send"}
        </button>
      </div>
    </div>
  );
}
