import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { toast } from "sonner";
import { Send, Trash2, MessageSquare, AtSign } from "lucide-react";
import { api, ApiError, type CommentView, type CommentMention, type MentionSuggestion } from "@/lib/api";
import type { UiEvent } from "@/lib/types";
import { cn } from "@/lib/utils";
import { linkify } from "@/lib/linkify";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";

/** The entity a thread hangs off — the same triple the detail route uses. */
export type CommentTarget = { kind: "catalogs" | "documents"; name: string; id: string };

/**
 * A stored mention token: `@[Display](kind/name/id)`. Mirrors the server's `Mentions` syntax so the
 * body round-trips. The `name` segment is the snake_case route, so the token is also a navigable
 * `onec://` route on click.
 */
const MENTION_TOKEN =
  /@\[([^\]]+)\]\((catalogs|documents)\/([^/)\s]+)\/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\)/g;

// A pending `@`-mention pick in the compose box, recorded the moment a suggestion is chosen. The
// compose textarea holds clean `@Display` text; at submit time each pick's `@Display` run is rewritten
// to its full token (see toTokenBody), so the body sent to the server carries the route triple.
type Pick = { display: string; kind: "catalogs" | "documents"; name: string; id: string };

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

// Route to a mentioned record through the same host event other widgets use to open a row/detail.
function openMention(m: { kind: string; name: string; id: string }) {
  window.dispatchEvent(new CustomEvent("onec:action", { detail: `onec://${m.kind}/${m.name}/${m.id}` }));
}

/** One mention inside a rendered body: a clickable chip when readable, plain `@text` otherwise. */
function MentionChip({ label, mention }: { label: string; mention: CommentMention | undefined }) {
  const text = mention?.display ?? label;
  // Clickable only when the viewer can actually open it; an unreadable/deleted mention degrades to
  // plain (muted) text rather than a link that would 404/403.
  if (!mention || !mention.readable || !mention.display) {
    return <span className="font-medium text-muted-foreground">@{text}</span>;
  }
  return (
    <button
      type="button"
      onClick={() => openMention(mention)}
      title={mention.entity ? `${mention.entity}: ${text}` : text}
      className={cn(
        "mx-0.5 inline-flex max-w-[16rem] translate-y-[1px] items-center gap-1 rounded-full align-baseline",
        "bg-primary/10 px-1.5 py-px text-sm font-medium text-primary",
        "transition-colors hover:bg-primary/20 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
      )}
    >
      {mention.avatarUrl ? (
        <img src={mention.avatarUrl} alt="" className="size-3.5 shrink-0 rounded-full object-cover" />
      ) : (
        <AtSign className="size-3 shrink-0 opacity-70" aria-hidden="true" />
      )}
      <span className="truncate">{text}</span>
    </button>
  );
}

/**
 * Tokenize a stored body into text + mention chips, resolving each token against the live mentions.
 * The plain-text runs between mentions are passed through {@link linkify}, so pasted http(s) URLs
 * stay clickable (#134) right alongside mention chips.
 */
function renderBody(body: string, mentions: CommentMention[]) {
  const byId = new Map(mentions.map((m) => [m.id, m]));
  const nodes: React.ReactNode[] = [];
  const re = new RegExp(MENTION_TOKEN);
  let last = 0;
  let key = 0;
  let m: RegExpExecArray | null;
  while ((m = re.exec(body)) !== null) {
    if (m.index > last) nodes.push(<span key={key++}>{linkify(body.slice(last, m.index))}</span>);
    nodes.push(<MentionChip key={key++} label={m[1]} mention={byId.get(m[4])} />);
    last = m.index + m[0].length;
  }
  if (last < body.length) nodes.push(<span key={key++}>{linkify(body.slice(last))}</span>);
  return nodes;
}

// Serialize the compose box (clean `@Display` text) to a token body. Each pick rewrites the first
// remaining literal occurrence of its `@Display` run; already-rewritten tokens start with `@[`, so
// they're never matched again. A pick whose text the user deleted is silently dropped.
function toTokenBody(text: string, picks: Pick[]): string {
  let out = text;
  for (const p of picks) {
    const needle = `@${p.display}`;
    const at = out.indexOf(needle);
    if (at < 0) continue;
    const token = `@[${p.display.replace(/]/g, "")}](${p.kind}/${p.name}/${p.id})`;
    out = out.slice(0, at) + token + out.slice(at + needle.length);
  }
  return out;
}

// The `@query` immediately before the caret (query may be empty right after typing `@`), or null when
// the caret isn't in a mention context. A mention starts at line-start or after whitespace and runs to
// the caret with no further whitespace/`@`.
function activeMentionQuery(text: string, caret: number): { query: string; start: number } | null {
  const before = text.slice(0, caret);
  const match = /(?:^|\s)@([^\s@]*)$/.exec(before);
  if (!match) return null;
  return { query: match[1], start: caret - match[1].length - 1 };
}

/**
 * The discussion thread panel rendered on every catalog/document detail surface (the
 * `onec-comments` div-custom). Loads the thread from `/api/comments/...`, posts new comments (author
 * stamped server-side), deletes one's own, and supports `@`-mentioning any readable catalog/document
 * (users included) via the `/api/mentions` typeahead. Live-syncs: besides re-fetching after the
 * viewer's own writes, it refetches when the server signals a comment change on this thread — so
 * other people's posts and deletes appear without a reload. It listens to the shared `onec:dataevent`
 * fan-out (divkit-view's single SSE stream) the list islands use, so it adds no second connection and
 * never touches the content-pane machinery.
 */
export function EntityCommentsWidget({ target }: { target: CommentTarget }) {
  const { kind, name, id } = target;
  const [comments, setComments] = useState<CommentView[] | null>(null);
  const [body, setBody] = useState("");
  const [picks, setPicks] = useState<Pick[]>([]);
  const [busy, setBusy] = useState(false);
  const taRef = useRef<HTMLTextAreaElement | null>(null);

  // --- @-mention typeahead state ---
  const [mq, setMq] = useState<{ query: string; start: number } | null>(null);
  const [suggestions, setSuggestions] = useState<MentionSuggestion[]>([]);
  const [active, setActive] = useState(0);
  const reqSeq = useRef(0);
  const caretToRestore = useRef<number | null>(null);

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

  // Live-sync: refetch when the server signals a comment changed on this thread. We reuse the shared
  // `onec:dataevent` fan-out (divkit-view's one SSE stream) the list islands consume rather than
  // opening our own stream. The target id is globally unique, so it alone scopes the thread; the name
  // is matched too as a cheap guard. The viewer's own post already shows optimistically — this just
  // reconciles it — and it's how *other* viewers' posts and deletes appear without a reload.
  useEffect(() => {
    const onData = (e: Event) => {
      const ev = (e as CustomEvent<UiEvent>).detail;
      if (ev?.entityType === "comment" && ev.id === id && ev.entityName === name) load();
    };
    window.addEventListener("onec:dataevent", onData);
    return () => window.removeEventListener("onec:dataevent", onData);
  }, [load, id, name]);

  // Debounced typeahead fetch whenever the active mention query changes. A stale response (a slower
  // earlier request resolving last) is ignored via the request sequence guard.
  useEffect(() => {
    if (mq === null) {
      setSuggestions([]);
      return;
    }
    const seq = ++reqSeq.current;
    const t = setTimeout(() => {
      api
        .searchMentions(mq.query)
        .then((rows) => {
          if (seq === reqSeq.current) {
            setSuggestions(rows);
            setActive(0);
          }
        })
        // Mentions disabled (404) or any failure → no suggestions; the box still posts plain text.
        .catch(() => {
          if (seq === reqSeq.current) setSuggestions([]);
        });
    }, 140);
    return () => clearTimeout(t);
  }, [mq]);

  // Re-place the caret after a programmatic insert (React resets it to the end otherwise).
  useEffect(() => {
    if (caretToRestore.current !== null && taRef.current) {
      const pos = caretToRestore.current;
      caretToRestore.current = null;
      taRef.current.setSelectionRange(pos, pos);
    }
  }, [body]);

  const onChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    const value = e.target.value;
    setBody(value);
    setMq(activeMentionQuery(value, e.target.selectionStart ?? value.length));
  };

  const choose = useCallback(
    (s: MentionSuggestion) => {
      setBody((prev) => {
        const ctx = mq ?? activeMentionQuery(prev, taRef.current?.selectionStart ?? prev.length);
        const start = ctx ? ctx.start : (taRef.current?.selectionStart ?? prev.length);
        const end = taRef.current?.selectionStart ?? prev.length;
        const inserted = `@${s.display} `;
        caretToRestore.current = start + inserted.length;
        return prev.slice(0, start) + inserted + prev.slice(end);
      });
      setPicks((prev) => [...prev, { display: s.display, kind: s.kind, name: s.name, id: s.id }]);
      setMq(null);
      setSuggestions([]);
      taRef.current?.focus();
    },
    [mq]
  );

  const submit = async () => {
    const text = body.trim();
    if (!text || busy) return;
    setBusy(true);
    try {
      const saved = await api.addComment(kind, name, id, toTokenBody(text, picks));
      setComments((prev) => [...(prev ?? []), saved]);
      setBody("");
      setPicks([]);
      setMq(null);
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

  const pickerOpen = mq !== null && suggestions.length > 0;

  const onKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (pickerOpen) {
      // Drive the suggestion list from the keyboard; these keys never reach the textarea.
      if (e.key === "ArrowDown") {
        e.preventDefault();
        setActive((i) => (i + 1) % suggestions.length);
        return;
      }
      if (e.key === "ArrowUp") {
        e.preventDefault();
        setActive((i) => (i - 1 + suggestions.length) % suggestions.length);
        return;
      }
      if (e.key === "Enter" || e.key === "Tab") {
        e.preventDefault();
        choose(suggestions[active]);
        return;
      }
      if (e.key === "Escape") {
        e.preventDefault();
        setMq(null);
        return;
      }
    }
    // Cmd/Ctrl+Enter sends, matching the chat-style affordance users expect from a compose box.
    if ((e.metaKey || e.ctrlKey) && e.key === "Enter") {
      e.preventDefault();
      void submit();
    }
  };

  const count = comments?.length ?? 0;
  const canSend = body.trim() !== "" && !busy;

  const rendered = useMemo(
    () => (comments ?? []).map((c) => ({ comment: c, nodes: renderBody(c.body, c.mentions ?? []) })),
    [comments]
  );

  return (
    // pointer-events-auto: this widget is portaled into a DivKit div-custom whose container blocks
    // are rendered pointer-events:none; opt the panel back in so the textarea and Send button are
    // focusable/clickable (matches entity-list-widget, page-actions-bar, constants-editor, login).
    <div className="pointer-events-auto mt-4 rounded-2xl border border-border bg-card p-4 sm:p-5">
      <div className="mb-4 flex items-center gap-2">
        <MessageSquare className="size-4 text-muted-foreground" aria-hidden="true" />
        <h2 className="text-sm font-semibold text-foreground">Comments</h2>
        {count > 0 ? (
          <span className="grid min-w-5 place-items-center rounded-full bg-muted px-1.5 text-xs font-medium tabular-nums text-muted-foreground">
            {count}
          </span>
        ) : null}
      </div>

      {comments === null ? (
        <p className="text-sm text-muted-foreground">Loading…</p>
      ) : comments.length === 0 ? (
        <div className="rounded-xl border border-dashed border-border px-4 py-6 text-center">
          <MessageSquare className="mx-auto mb-2 size-5 text-muted-foreground/60" aria-hidden="true" />
          <p className="text-sm text-muted-foreground">No comments yet. Start the conversation below.</p>
        </div>
      ) : (
        <ul className="space-y-1">
          {rendered.map(({ comment: c, nodes }) => (
            <li key={c.id} className="group flex gap-3 rounded-lg px-2 py-2 -mx-2 transition-colors hover:bg-muted/40">
              <Avatar className="mt-0.5 size-7 shrink-0">
                {c.authorAvatarUrl ? (
                  <AvatarImage src={c.authorAvatarUrl} alt={c.authorName ?? ""} />
                ) : null}
                <AvatarFallback className="text-[11px]">{initials(c.authorName)}</AvatarFallback>
              </Avatar>
              <div className="min-w-0 flex-1">
                <div className="flex items-baseline gap-2">
                  <span className="text-sm font-medium text-foreground">{c.authorName || "Unknown"}</span>
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
                <p className="mt-0.5 whitespace-pre-wrap break-words text-sm leading-relaxed text-foreground">
                  {nodes}
                </p>
              </div>
            </li>
          ))}
        </ul>
      )}

      <div className="relative mt-4">
        {pickerOpen ? (
          <ul
            // Opens upward (bottom-full) so it never clips at the bottom of a scrolling detail pane.
            className="absolute bottom-full left-0 z-20 mb-1 max-h-64 w-full overflow-auto rounded-xl border border-border bg-popover p-1 shadow-lg"
            role="listbox"
          >
            {suggestions.map((s, i) => (
              <li key={`${s.kind}/${s.name}/${s.id}`}>
                <button
                  type="button"
                  // onMouseDown (not onClick) so the textarea doesn't blur before the pick lands.
                  onMouseDown={(e) => {
                    e.preventDefault();
                    choose(s);
                  }}
                  onMouseEnter={() => setActive(i)}
                  className={cn(
                    "flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-left text-sm transition-colors",
                    i === active ? "bg-accent text-accent-foreground" : "hover:bg-accent/60"
                  )}
                  role="option"
                  aria-selected={i === active}
                >
                  <Avatar className="size-6 shrink-0">
                    {s.avatarUrl ? <AvatarImage src={s.avatarUrl} alt="" /> : null}
                    <AvatarFallback className="text-[10px]">{initials(s.display)}</AvatarFallback>
                  </Avatar>
                  <span className="min-w-0 flex-1 truncate font-medium text-foreground">{s.display}</span>
                  <span className="shrink-0 text-xs text-muted-foreground">{s.entity}</span>
                </button>
              </li>
            ))}
          </ul>
        ) : null}

        <div className="flex items-end gap-2">
          <textarea
            ref={taRef}
            value={body}
            onChange={onChange}
            onKeyDown={onKeyDown}
            onBlur={() => setMq(null)}
            rows={2}
            placeholder="Write a comment…  Type @ to mention"
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
            disabled={!canSend}
            className={cn(
              // shrink-0 + whitespace-nowrap: the textarea is w-full, so without these the button is
              // squeezed below its content width in a narrow pane and the label wraps ("Sen d").
              "inline-flex h-10 shrink-0 items-center gap-1.5 whitespace-nowrap rounded-md bg-primary px-3.5 text-sm font-medium",
              "text-primary-foreground transition-colors hover:bg-primary/90 disabled:opacity-50"
            )}
          >
            <Send className="size-4" aria-hidden="true" />
            {busy ? "Posting…" : "Send"}
          </button>
        </div>
        <p className="mt-1.5 flex items-center gap-1 text-xs text-muted-foreground">
          <AtSign className="size-3" aria-hidden="true" />
          Mention any record with @ · <kbd className="font-sans">⌘</kbd>
          <kbd className="font-sans">↵</kbd> to send
        </p>
      </div>
    </div>
  );
}
