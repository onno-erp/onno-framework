import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { toast } from "sonner";
import { Send, Trash2, MessageSquare, AtSign, Hash, Reply, X, Plus, SmilePlus } from "lucide-react";
import {
  api,
  ApiError,
  type CommentView,
  type CommentMention,
  type CommentReaction,
  type MentionSuggestion,
} from "@/lib/api";
import type { UiEvent } from "@/lib/types";
import { cn } from "@/lib/utils";
import { linkify } from "@/lib/linkify";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { notionistsAvatar, tint } from "@/components/presence-avatars";

/** The entity a thread hangs off — the same triple the detail route uses. */
export type CommentTarget = { kind: "catalogs" | "documents"; name: string; id: string };

/**
 * A stored comment link token: `@[Display](kind/name/id)` for mentions and `#[Display](kind/name/id)`
 * for document references. Mirrors the server's `Mentions` syntax so the
 * body round-trips. The `name` segment is the snake_case route, so the token is also a navigable
 * `onno://` route on click.
 */
const MENTION_TOKEN =
  /([@#])\[([^\]]+)\]\((catalogs|documents)\/([^/)\s]+)\/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\)/g;

// A pending `@`/`#` pick in the compose box, recorded the moment a suggestion is chosen. The
// compose textarea holds clean `@Display`/`#Display` text; at submit time each pick's display run is rewritten
// to its full token (see toTokenBody), so the body sent to the server carries the route triple.
type Pick = { marker: "@" | "#"; display: string; kind: "catalogs" | "documents"; name: string; id: string };

const QUICK_REACTIONS = ["👍", "❤️", "🎉", "👀", "✅"];

// Up to two initials from a display name, for the author avatar.
function initials(name: string | null): string {
  if (!name) return "?";
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return "?";
  return (parts[0][0] + (parts.length > 1 ? parts[parts.length - 1][0] : "")).toUpperCase();
}

// A compact "time ago" for recent comments, falling back to an absolute date for older ones.
// createdAt is a zone-qualified instant ("…Z"), so new Date() reads it as the same point in time
// for every viewer and localizes it to their zone.
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
  window.dispatchEvent(new CustomEvent("onno:action", { detail: `onno://${m.kind}/${m.name}/${m.id}` }));
}

/** One mention/reference inside a rendered body: a clickable chip when readable, plain text otherwise. */
function MentionChip({
  marker,
  label,
  mention,
}: {
  marker: "@" | "#";
  label: string;
  mention: CommentMention | undefined;
}) {
  const text = mention?.display ?? label;
  // Clickable only when the viewer can actually open it; an unreadable/deleted mention degrades to
  // plain (muted) text rather than a link that would 404/403.
  if (!mention || !mention.readable || !mention.display) {
    return <span className="font-medium text-muted-foreground">{marker}{text}</span>;
  }
  return (
    <button
      type="button"
      onClick={() => openMention(mention)}
      title={mention.entity ? `${mention.entity}: ${text}` : text}
      className={cn(
        "mx-0.5 inline-flex max-w-[16rem] translate-y-[1px] items-center gap-1 rounded-control align-baseline",
        "bg-primary/10 px-1.5 py-px text-sm font-medium text-primary",
        "transition-colors hover:bg-primary/20 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
      )}
    >
      {mention.avatarUrl ? (
        <img src={mention.avatarUrl} alt="" className="size-3.5 shrink-0 rounded-full object-cover" />
      ) : marker === "#" ? (
        <Hash className="size-3 shrink-0 opacity-70" aria-hidden="true" />
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
    nodes.push(<MentionChip key={key++} marker={m[1] as "@" | "#"} label={m[2]} mention={byId.get(m[5])} />);
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
    const needle = `${p.marker}${p.display}`;
    const at = out.indexOf(needle);
    if (at < 0) continue;
    const token = `${p.marker}[${p.display.replace(/]/g, "")}](${p.kind}/${p.name}/${p.id})`;
    out = out.slice(0, at) + token + out.slice(at + needle.length);
  }
  return out;
}

// The `@query` or `#query` immediately before the caret, or null when the caret isn't in a picker context.
// A token starts at line-start or after whitespace and runs to the caret with no further whitespace/marker.
function activeMentionQuery(text: string, caret: number): { marker: "@" | "#"; query: string; start: number } | null {
  const before = text.slice(0, caret);
  const match = /(?:^|\s)([@#])([^\s@#]*)$/.exec(before);
  if (!match) return null;
  return { marker: match[1] as "@" | "#", query: match[2], start: caret - match[2].length - 1 };
}

// An internal record link inside pasted text — the app's own detail route, absolute or root-relative.
// Pasting one swaps it for a mention of the record it points at (see onPaste).
const INTERNAL_LINK = /(?:https?:\/\/[^/\s]+)?\/ui\/(catalogs|documents)\/([A-Za-z0-9_]+)\/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(?=[\s)>,.;!?]|$)/g;

// A suggestion's secondary line: document dates localize, anything else (an email) shows as-is.
function formatHint(s: MentionSuggestion): string | null {
  if (!s.hint) return null;
  if (s.kind === "documents" && /^\d{4}-\d{2}-\d{2}$/.test(s.hint)) {
    const d = new Date(s.hint + "T00:00:00");
    if (!Number.isNaN(d.getTime())) {
      return d.toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });
    }
  }
  return s.hint;
}

function ReactionButton({
  reaction,
  onClick,
}: {
  reaction: CommentReaction;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={`${reaction.mine ? "Remove" : "Add"} ${reaction.emoji} reaction`}
      className={cn(
        "inline-flex h-6 items-center gap-1 rounded-full border px-1.5 text-xs shadow-sm transition-colors",
        "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
        reaction.mine
          ? "border-primary/30 bg-primary/15 text-primary hover:bg-primary/20"
          : "border-border bg-background/95 text-foreground hover:bg-accent"
      )}
    >
      <span aria-hidden="true">{reaction.emoji}</span>
      <span className="tabular-nums">{reaction.count}</span>
    </button>
  );
}

/**
 * The discussion thread panel rendered on every catalog/document detail surface (the
 * `onno-comments` div-custom). Loads the thread from `/api/comments/...`, posts new comments (author
 * stamped server-side), deletes one's own, and supports `@` mentions plus `#` document references
 * via the `/api/mentions` typeahead. Live-syncs: besides re-fetching after the
 * viewer's own writes, it refetches when the server signals a comment change on this thread — so
 * other people's posts and deletes appear without a reload. It listens to the shared `onno:dataevent`
 * fan-out (divkit-view's single SSE stream) the list islands use, so it adds no second connection and
 * never touches the content-pane machinery.
 */
export function EntityCommentsWidget({ target }: { target: CommentTarget }) {
  const { kind, name, id } = target;
  const [comments, setComments] = useState<CommentView[] | null>(null);
  const [body, setBody] = useState("");
  const [picks, setPicks] = useState<Pick[]>([]);
  const [replyTo, setReplyTo] = useState<CommentView | null>(null);
  const [openReactionFor, setOpenReactionFor] = useState<string | null>(null);
  const [insertMenuOpen, setInsertMenuOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const taRef = useRef<HTMLTextAreaElement | null>(null);

  // --- @-mention typeahead state ---
  const [mq, setMq] = useState<{ marker: "@" | "#"; query: string; start: number } | null>(null);
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
  // `onno:dataevent` fan-out (divkit-view's one SSE stream) the list islands consume rather than
  // opening our own stream. The target id is globally unique, so it alone scopes the thread; the name
  // is matched too as a cheap guard. The viewer's own post already shows optimistically — this just
  // reconciles it — and it's how *other* viewers' posts and deletes appear without a reload.
  useEffect(() => {
    const onData = (e: Event) => {
      const ev = (e as CustomEvent<UiEvent>).detail;
      if (ev?.entityType === "comment" && ev.id === id && ev.entityName === name) load();
    };
    window.addEventListener("onno:dataevent", onData);
    return () => window.removeEventListener("onno:dataevent", onData);
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
        // `@` suggests people (the identity catalog); `#` references any record — documents and
        // catalogs alike (a supplier, a book), searchable by name or code.
        .searchMentions(mq.query, mq.marker === "#" ? undefined : "people")
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
      const marker = mq?.marker ?? "@";
      setBody((prev) => {
        const ctx = mq ?? activeMentionQuery(prev, taRef.current?.selectionStart ?? prev.length);
        const start = ctx ? ctx.start : (taRef.current?.selectionStart ?? prev.length);
        const end = taRef.current?.selectionStart ?? prev.length;
        const inserted = `${ctx?.marker ?? marker}${s.display} `;
        caretToRestore.current = start + inserted.length;
        return prev.slice(0, start) + inserted + prev.slice(end);
      });
      setPicks((prev) => [...prev, { marker, display: s.display, kind: s.kind, name: s.name, id: s.id }]);
      setMq(null);
      setInsertMenuOpen(false);
      setSuggestions([]);
      taRef.current?.focus();
    },
    [mq]
  );

  // Pasting internal record links (the app's own /ui/... detail URLs) converts each readable link
  // to a mention of the record it points at — `@` for catalog records, `#` for documents. Links the
  // viewer can't read (or that no longer resolve) stay as plain pasted text.
  const onPaste = (e: React.ClipboardEvent<HTMLTextAreaElement>) => {
    const text = e.clipboardData.getData("text/plain");
    if (!text) return;
    const links = [...text.matchAll(new RegExp(INTERNAL_LINK))].filter(
      // Absolute URLs must be this app's own origin; root-relative /ui/... paths always count.
      (m) => !m[0].startsWith("http") || m[0].startsWith(window.location.origin + "/ui/")
    );
    if (links.length === 0) return;
    e.preventDefault();
    const start = e.currentTarget.selectionStart ?? body.length;
    const end = e.currentTarget.selectionEnd ?? start;
    void (async () => {
      let out = text;
      const newPicks: Pick[] = [];
      for (const m of links) {
        const kind = m[1] as "catalogs" | "documents";
        try {
          const r = await api.resolveMention(kind, m[2], m[3]);
          if (!r.readable || !r.display) continue;
          // `@` is reserved for people; any other record (a document, a supplier) reads as `#`.
          const marker = r.person ? "@" : "#";
          out = out.replace(m[0], `${marker}${r.display}`);
          newPicks.push({ marker, display: r.display, kind, name: m[2], id: m[3] });
        } catch {
          // Resolution failed (mentions off, record gone) — leave the URL text alone.
        }
      }
      setBody((prev) => {
        const s = Math.min(start, prev.length);
        const t = Math.min(end, prev.length);
        caretToRestore.current = s + out.length;
        return prev.slice(0, s) + out + prev.slice(t);
      });
      if (newPicks.length > 0) setPicks((prev) => [...prev, ...newPicks]);
      taRef.current?.focus();
    })();
  };

  const insertMarker = (marker: "@" | "#") => {
    setBody((prev) => {
      const start = taRef.current?.selectionStart ?? prev.length;
      const end = taRef.current?.selectionEnd ?? start;
      const prefix = start === 0 || /\s/.test(prev[start - 1] ?? "") ? "" : " ";
      const inserted = `${prefix}${marker}`;
      const markerStart = start + prefix.length;
      caretToRestore.current = markerStart + 1;
      setMq({ marker, query: "", start: markerStart });
      return prev.slice(0, start) + inserted + prev.slice(end);
    });
    setInsertMenuOpen(false);
    taRef.current?.focus();
  };

  const submit = async () => {
    const text = body.trim();
    if (!text || busy) return;
    setBusy(true);
    try {
      const saved = await api.addComment(kind, name, id, toTokenBody(text, picks), replyTo?.id ?? null);
      setComments((prev) => [...(prev ?? []), saved]);
      setBody("");
      setPicks([]);
      setReplyTo(null);
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

  const toggleReaction = async (commentId: string, emoji: string) => {
    const prev = comments;
    try {
      const reactions = await api.toggleCommentReaction(commentId, emoji);
      setComments((cur) => (cur ?? []).map((c) => (c.id === commentId ? { ...c, reactions } : c)));
      setOpenReactionFor(null);
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
  const topLevel = useMemo(() => {
    const ids = new Set((comments ?? []).map((c) => c.id));
    return (comments ?? []).filter((c) => !c.parentId || !ids.has(c.parentId));
  }, [comments]);
  const repliesByParent = useMemo(() => {
    const grouped = new Map<string, CommentView[]>();
    for (const c of comments ?? []) {
      if (!c.parentId) continue;
      grouped.set(c.parentId, [...(grouped.get(c.parentId) ?? []), c]);
    }
    return grouped;
  }, [comments]);

  const renderReactions = (c: CommentView) => {
    const existing = c.reactions ?? [];
    const reactionPickerOpen = openReactionFor === c.id;
    return (
      <div className="relative mt-2 flex flex-wrap items-center gap-1">
        {existing.map((reaction) => (
          <ReactionButton
            key={reaction.emoji}
            reaction={reaction}
            onClick={() => void toggleReaction(c.id, reaction.emoji)}
          />
        ))}
        <button
          type="button"
          onClick={() => setOpenReactionFor((open) => (open === c.id ? null : c.id))}
          aria-expanded={reactionPickerOpen}
          aria-label="Choose reaction"
          title="React"
          className="grid size-6 place-items-center rounded-full border border-border bg-background text-muted-foreground transition-colors hover:bg-accent hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          <SmilePlus className="size-3.5" aria-hidden="true" />
        </button>
        {reactionPickerOpen ? (
          <div className="absolute left-0 top-full z-20 mt-1 flex gap-1 rounded-full border border-border bg-popover p-1 shadow-lg">
            {QUICK_REACTIONS.map((emoji) => (
              <button
                key={emoji}
                type="button"
                onClick={() => void toggleReaction(c.id, emoji)}
                aria-label={`React with ${emoji}`}
                className="grid size-7 place-items-center rounded-full text-sm transition-colors hover:bg-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                <span aria-hidden="true">{emoji}</span>
              </button>
            ))}
          </div>
        ) : null}
      </div>
    );
  };
  const renderComment = (c: CommentView, nested = false) => (
    <div key={c.id} className={cn(nested && "ml-10 border-l border-border pl-3")}>
      <div className="group flex gap-3 rounded-card px-2 py-2 -mx-2 transition-colors hover:bg-muted/40">
        <Avatar className="mt-0.5 size-7 shrink-0 border border-border">
          <AvatarImage src={c.authorAvatarUrl || notionistsAvatar(c.authorName)} alt={c.authorName ?? ""} />
          <AvatarFallback
            className="text-[11px] text-white"
            style={{ backgroundColor: tint(c.authorName ?? "") }}
          >
            {initials(c.authorName)}
          </AvatarFallback>
        </Avatar>
        <div className="min-w-0 flex-1">
          <div className="flex items-baseline gap-2">
            <span className="text-sm font-medium text-foreground">{c.authorName || "Unknown"}</span>
            <span className="text-xs text-muted-foreground" title={absolute(c.createdAt)}>
              {timeAgo(c.createdAt)}
              {c.editedAt ? " · edited" : ""}
            </span>
            <button
              type="button"
              onClick={() => {
                setReplyTo(c.parentId ? comments?.find((parent) => parent.id === c.parentId) ?? c : c);
                taRef.current?.focus();
              }}
              aria-label="Reply"
              title="Reply"
              className="ml-auto grid size-6 shrink-0 place-items-center rounded-control text-muted-foreground opacity-0 transition-colors hover:bg-accent hover:text-foreground focus-visible:opacity-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring group-hover:opacity-100"
            >
              <Reply className="size-3.5" aria-hidden="true" />
            </button>
            {c.canDelete ? (
              <button
                type="button"
                onClick={() => remove(c.id)}
                aria-label="Delete comment"
                title="Delete comment"
                className="grid size-6 shrink-0 place-items-center rounded-control text-muted-foreground opacity-0 transition-colors hover:bg-accent hover:text-destructive focus-visible:opacity-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring group-hover:opacity-100"
              >
                <Trash2 className="size-3.5" aria-hidden="true" />
              </button>
            ) : null}
          </div>
          <p className="mt-0.5 whitespace-pre-wrap break-words text-sm leading-relaxed text-foreground">
            {rendered.get(c.id)}
          </p>
          {renderReactions(c)}
        </div>
      </div>
    </div>
  );

  const rendered = useMemo(() => {
    const byId = new Map<string, React.ReactNode[]>();
    for (const c of comments ?? []) {
      byId.set(c.id, renderBody(c.body, c.mentions ?? []));
    }
    return byId;
  }, [comments]);

  return (
    // pointer-events-auto: this widget is portaled into a DivKit div-custom whose container blocks
    // are rendered pointer-events:none; opt the panel back in so the textarea and Send button are
    // focusable/clickable (matches entity-list-widget, page-actions-bar, constants-editor, login).
    <div className="pointer-events-auto mt-4 rounded-card border border-border bg-card p-4 sm:p-5">
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
        <div className="rounded-card border border-dashed border-border px-4 py-6 text-center">
          <MessageSquare className="mx-auto mb-2 size-5 text-muted-foreground/60" aria-hidden="true" />
          <p className="text-sm text-muted-foreground">No comments yet. Start the conversation below.</p>
        </div>
      ) : (
        <ul className="space-y-1">
          {topLevel.map((c) => (
            <li key={c.id} className="space-y-1">
              {renderComment(c)}
              {(repliesByParent.get(c.id) ?? []).length > 0 ? (
                <ul className="space-y-1">
                  {(repliesByParent.get(c.id) ?? []).map((reply) => renderComment(reply, true))}
                </ul>
              ) : null}
            </li>
          ))}
        </ul>
      )}

      <div className="relative mt-4">
        {replyTo ? (
          <div className="mb-2 flex items-center gap-2 rounded-card border border-border bg-muted/40 px-3 py-2 text-xs text-muted-foreground">
            <Reply className="size-3.5 shrink-0" aria-hidden="true" />
            <span className="min-w-0 flex-1 truncate">
              Replying to <span className="font-medium text-foreground">{replyTo.authorName || "Unknown"}</span>
            </span>
            <button
              type="button"
              onClick={() => setReplyTo(null)}
              aria-label="Cancel reply"
              title="Cancel reply"
              className="grid size-5 shrink-0 place-items-center rounded-control text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
            >
              <X className="size-3.5" aria-hidden="true" />
            </button>
          </div>
        ) : null}

        <div className="relative">
          <div className="relative flex items-end gap-2 rounded-card border border-input bg-background p-2 shadow-sm focus-within:ring-2 focus-within:ring-ring">
            <button
              type="button"
              onClick={() => setInsertMenuOpen((open) => !open)}
              aria-label="Add mention or reference"
              aria-expanded={insertMenuOpen}
              className="grid size-9 shrink-0 place-items-center rounded-control border border-border text-muted-foreground transition-colors hover:bg-accent hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              disabled={busy}
            >
              <Plus className="size-4" aria-hidden="true" />
            </button>
            {insertMenuOpen ? (
              <div className="absolute bottom-full left-2 z-30 mb-1 w-48 rounded-card border border-border bg-popover p-1 shadow-lg">
                <button
                  type="button"
                  onMouseDown={(e) => {
                    e.preventDefault();
                    insertMarker("@");
                  }}
                  className="flex w-full items-center gap-2 rounded-control px-2 py-1.5 text-left text-sm transition-colors hover:bg-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                >
                  <AtSign className="size-4 text-muted-foreground" aria-hidden="true" />
                  Mention
                </button>
                <button
                  type="button"
                  onMouseDown={(e) => {
                    e.preventDefault();
                    insertMarker("#");
                  }}
                  className="flex w-full items-center gap-2 rounded-control px-2 py-1.5 text-left text-sm transition-colors hover:bg-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                >
                  <Hash className="size-4 text-muted-foreground" aria-hidden="true" />
                  Reference record
                </button>
              </div>
            ) : null}
            <textarea
              ref={taRef}
              value={body}
              onChange={onChange}
              onKeyDown={onKeyDown}
              onPaste={onPaste}
              onBlur={() => setMq(null)}
              rows={1}
              placeholder="Write a comment..."
              className="flex max-h-32 min-h-9 w-full resize-none border-0 bg-transparent px-1 py-2 text-sm placeholder:text-muted-foreground focus-visible:outline-none disabled:opacity-50"
              disabled={busy}
            />
            <button
              type="button"
              onClick={submit}
              disabled={!canSend}
              aria-label={busy ? "Posting" : "Send comment"}
              className="grid size-9 shrink-0 place-items-center rounded-control bg-primary text-primary-foreground transition-colors hover:bg-primary/90 disabled:opacity-50"
            >
              <Send className="size-4" aria-hidden="true" />
            </button>
          </div>
          {pickerOpen ? (
            <ul
              className="absolute bottom-full left-0 z-20 mb-1 max-h-64 w-full overflow-auto rounded-card border border-border bg-popover p-1 shadow-lg"
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
                      "flex w-full items-center gap-2 rounded-control px-2 py-1.5 text-left text-sm transition-colors",
                      i === active ? "bg-accent text-accent-foreground" : "hover:bg-accent/60"
                    )}
                    role="option"
                    aria-selected={i === active}
                  >
                    <Avatar className="size-6 shrink-0 border border-border">
                      <AvatarImage src={s.avatarUrl || notionistsAvatar(`${s.kind}/${s.name}/${s.id}`)} alt="" />
                      <AvatarFallback className="text-[10px]">{initials(s.display)}</AvatarFallback>
                    </Avatar>
                    <span className="min-w-0 flex-1 truncate font-medium text-foreground">{s.display}</span>
                    {formatHint(s) ? (
                      <span className="max-w-[11rem] shrink-0 truncate text-xs text-muted-foreground">
                        {formatHint(s)}
                      </span>
                    ) : null}
                    <span className="shrink-0 text-xs text-muted-foreground/70">
                      {mq?.marker === "#" ? <Hash className="inline size-3" aria-hidden="true" /> : null}
                      {s.entity}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          ) : null}
        </div>
        <p className="mt-1.5 flex items-center gap-1 text-xs text-muted-foreground">
          Mention with @ · reference records with # · <kbd className="font-sans">⌘</kbd>
          <kbd className="font-sans">↵</kbd> to send
        </p>
      </div>
    </div>
  );
}
