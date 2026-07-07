import { toast } from "sonner";
import type {
  AppConfig,
  Branding,
  CatalogMeta,
  DashboardWidgetMeta,
  DocumentMeta,
  EntityRecord,
  LayoutSection,
  RegisterMeta,
  AuthUser,
  UiEvent,
  SettingMeta,
  ActionResult,
  BatchResult,
} from "./types";

import { toSnakeCase } from "./utils";
// Type-only (erased at build time), so this doesn't create a runtime cycle with widget-data.
import type { AggregateBuckets } from "./widget-data";

const BASE = "/api";
const CSRF_COOKIE = "XSRF-TOKEN";
const CSRF_HEADER = "X-XSRF-TOKEN";
const MUTATING = new Set(["POST", "PUT", "PATCH", "DELETE"]);

// A 401 on any data call means the session lapsed while the tab was open. The AuthProvider
// registers a handler here so it can recover (silently re-auth via the IdP, or fall back to the
// login screen) instead of the request just failing into the void. The bad-credentials 401 from
// the login endpoint is excluded at the call site below — that isn't session loss.
let onUnauthorized: (() => void) | null = null;
export function setUnauthorizedHandler(handler: (() => void) | null) {
  onUnauthorized = handler;
}

function readCsrfToken(): string | null {
  const match = document.cookie
    .split(";")
    .map((part) => part.trim())
    .find((part) => part.startsWith(`${CSRF_COOKIE}=`));
  if (!match) return null;
  return decodeURIComponent(match.slice(CSRF_COOKIE.length + 1));
}

/**
 * Open the shared SSE stream and feed every parsed {@link UiEvent} to {@code onEvent} until the
 * signal aborts. Rejects with a status-carrying {@link ApiError} on a non-OK response — including
 * 401 (which additionally triggers the auth-recovery handler) — and with the underlying error on
 * network failure; the caller's reconnect loop is expected to inspect the status and stop on 401
 * rather than hammer the endpoint.
 */
export async function streamUiEvents(
  onEvent: (event: UiEvent) => void,
  signal: AbortSignal
) {
  const res = await fetch(`${BASE}/events`, {
    signal,
    credentials: "same-origin",
    headers: {
      Accept: "text/event-stream",
    },
  });
  if (!res.ok || !res.body) {
    // A 401 means the session lapsed. Hand off to the auth-recovery handler (same as fetchJson) and
    // throw a typed error carrying the status, so the reconnect loop can stop instead of re-opening
    // /api/events every few seconds forever (a 401 flood). Other failures still reconnect.
    if (res.status === 401) onUnauthorized?.();
    throw new ApiError(`${res.status} ${res.statusText}`, res.status);
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  while (!signal.aborted) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    let boundary = buffer.indexOf("\n\n");
    while (boundary >= 0) {
      const chunk = buffer.slice(0, boundary);
      buffer = buffer.slice(boundary + 2);
      const data = chunk
        .split("\n")
        .filter((line) => line.startsWith("data:"))
        .map((line) => line.slice(5).trim())
        .join("\n");
      if (data) {
        try {
          onEvent(JSON.parse(data));
        } catch {
          // Ignore malformed keepalive/event payloads.
        }
      }
      boundary = buffer.indexOf("\n\n");
    }
  }
}

// Collapse concurrent identical GETs into a single network round-trip: a dashboard with several
// widgets reading the same entity would otherwise fire the same request once per widget on load.
// Keyed by URL and cleared the moment the request settles, so this only dedupes in-flight overlap —
// no cached response, hence no staleness (a later fetch always hits the network afresh).
const inFlightGets = new Map<string, Promise<unknown>>();

function fetchJson<T>(url: string, init?: RequestInit): Promise<T> {
  const method = (init?.method ?? "GET").toUpperCase();
  if (method !== "GET") return doFetch<T>(url, init);
  const existing = inFlightGets.get(url);
  if (existing) return existing as Promise<T>;
  const p = doFetch<T>(url, init).finally(() => inFlightGets.delete(url));
  inFlightGets.set(url, p);
  return p;
}

async function doFetch<T>(url: string, init?: RequestInit): Promise<T> {
  const method = (init?.method ?? "GET").toUpperCase();
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...((init?.headers as Record<string, string>) ?? {}),
  };
  if (MUTATING.has(method)) {
    const csrf = readCsrfToken();
    if (csrf) headers[CSRF_HEADER] = csrf;
  }

  const res = await fetch(url, {
    ...init,
    credentials: "same-origin",
    headers,
  });
  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`;
    let fieldErrors: Record<string, string[]> | undefined;
    try {
      const body = await res.json();
      if (body.message) message = body.message;
      else if (body.error) message = body.error;
      if (body.fieldErrors && typeof body.fieldErrors === "object" && Object.keys(body.fieldErrors).length) {
        fieldErrors = body.fieldErrors as Record<string, string[]>;
      }
    } catch { /* ignore parse errors */ }
    // A field-level validation 422 is shown inline by the form, so don't also toast it. Other
    // failures (auth aside) surface as a toast as before.
    if (res.status !== 401 && !fieldErrors) toast.error(message);
    // A 401 here is a lapsed session (the login endpoint's bad-credentials 401 is handled by its
    // own caller and never reaches a logged-in tab). Hand off to the registered recovery handler.
    if (res.status === 401 && !url.endsWith("/auth/login")) onUnauthorized?.();
    throw new ApiError(message, res.status, fieldErrors);
  }
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  if (!text) return undefined as T;
  return JSON.parse(text);
}

/** A stored-media reference returned by {@code POST /api/media} (see MediaController). */
export interface StoredMedia {
  key: string;
  url: string;
  contentType: string;
  size: number;
  filename?: string | null;
}

/**
 * Stream a file to the framework's binary-upload endpoint and resolve to its stored reference. The
 * body is multipart/form-data (no JSON Content-Type — the browser sets the boundary itself); the
 * CSRF header rides along since this is a mutating request. Callers persist the returned {@code url}
 * instead of base64-ing the bytes through a field.
 */
export async function uploadMedia(file: File): Promise<StoredMedia> {
  const form = new FormData();
  form.append("file", file);
  const headers: Record<string, string> = {};
  const csrf = readCsrfToken();
  if (csrf) headers[CSRF_HEADER] = csrf;

  const res = await fetch(`${BASE}/media`, {
    method: "POST",
    credentials: "same-origin",
    headers,
    body: form,
  });
  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`;
    try {
      const body = await res.json();
      if (body.message) message = body.message;
      else if (body.error) message = body.error;
    } catch { /* ignore parse errors */ }
    throw new ApiError(message, res.status);
  }
  return (await res.json()) as StoredMedia;
}

/**
 * A resolved {@code @} mention or {@code #} reference in a comment body (see MentionResolver). The body
 * carries it as a `@[Display](kind/name/id)` or `#[Display](kind/name/id)` token; this is the live resolution for the current viewer — display
 * and avatar reflect the record now, and `readable` is false when the viewer can't open it (the chip
 * then degrades to plain text rather than a broken link).
 */
export interface CommentMention {
  id: string;
  kind: "catalogs" | "documents";
  /** URL-safe route name (snake_case) — the `onno://kind/name/id` the chip navigates to. */
  name: string;
  /** The entity's display name, e.g. "Customers" (null when the viewer can't read it). */
  entity: string | null;
  /** The record's current display, e.g. "Acme Corp" / "INV-42" (null when unreadable or deleted). */
  display: string | null;
  avatarUrl: string | null;
  readable: boolean;
}

/** A typeahead suggestion from `/api/mentions?q=&kind=` — one readable catalog/document record. */
export interface MentionSuggestion {
  kind: "catalogs" | "documents";
  name: string;
  entity: string;
  id: string;
  display: string;
  avatarUrl: string | null;
  /** Secondary line for the picker: a person's email, a document's yyyy-MM-dd date. */
  hint?: string | null;
}

/** One `(kind, name, id)` triple resolved to its live display — see `/api/mentions/resolve`. */
export interface ResolvedMentionTarget {
  kind: "catalogs" | "documents";
  name: string;
  entity: string | null;
  id: string;
  display: string | null;
  avatarUrl: string | null;
  readable: boolean;
  /** True when the record belongs to the identity catalog — paste renders it as `@`, else `#`. */
  person?: boolean;
}

/** A grouped reaction on a comment bubble. `mine` is true when the current viewer has selected it. */
export interface CommentReaction {
  emoji: string;
  count: number;
  mine: boolean;
}

/** One comment in an entity's discussion thread (see CommentController). */
export interface CommentView {
  id: string;
  authorName: string | null;
  /** The author's avatar image URL when their account links to a record with an avatar; else null. */
  authorAvatarUrl: string | null;
  body: string;
  /** Null for a top-level comment; set for replies. */
  parentId: string | null;
  /** The mentions/references in `body`, resolved live for the current viewer (empty when none/disabled). */
  mentions: CommentMention[];
  /** Grouped reactions for this comment. */
  reactions: CommentReaction[];
  /** ISO-8601 instant, zone-qualified ("…Z"); localize per viewer. `editedAt` is null until edited. */
  createdAt: string | null;
  editedAt: string | null;
  /** True when the current user authored this comment. */
  mine: boolean;
  /** True when the current user may delete it (author or ADMIN). */
  canDelete: boolean;
}

/** One user currently viewing a record (see PresenceController). */
export interface PresenceViewer {
  userId: string;
  displayName: string;
  /** The viewer's avatar image URL, when their identity record has one; absent → render initials. */
  avatarUrl?: string;
}

/** The response to a presence ping: the record's current viewers plus the caller's own id. */
export interface PresenceState {
  /** The caller's own user id, so the client can omit itself from the markers. */
  you: string;
  viewers: PresenceViewer[];
}

/** One record currently being viewed, in the ambient-presence snapshot. */
export interface PresenceRecord {
  /** Route kind — "catalogs" | "documents". */
  kind: string;
  /** Route name (e.g. "properties"). */
  name: string;
  /** Record id (uuid). */
  id: string;
  viewers: PresenceViewer[];
}

/** The whole-app presence picture: every viewed record the caller may read, plus the caller's own id. */
export interface PresenceSnapshot {
  you: string;
  records: PresenceRecord[];
}

/** One notification in the per-user timeline (see NotificationController). */
export interface NotificationView {
  id: string;
  /** Source tag ("mention" | "assignment" | …) — the client maps it to an icon and a template. */
  type: string;
  title: string;
  body?: string | null;
  /** The "kind/name/id" route the row opens (an onno:// url body), or null for a non-navigating notice. */
  link?: string | null;
  actorName?: string | null;
  actorAvatar?: string | null;
  createdAt: string;
  readAt?: string | null;
  unread: boolean;
}

/** One newest-first window of the notification timeline, plus the badge's unread total. */
export interface NotificationPage {
  items: NotificationView[];
  nextCursor: string | null;
  hasMore: boolean;
  unreadCount: number;
}

export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
    /** Per-field validation messages from a 422, keyed by attribute field name. */
    public fieldErrors?: Record<string, string[]>
  ) {
    super(message);
  }
}

// Config & theme
export const api = {
  getCurrentUser: () => fetchJson<AuthUser>(`${BASE}/auth/me`),
  login: (username: string, password: string) =>
    fetchJson<AuthUser>(`${BASE}/auth/login`, {
      method: "POST",
      body: JSON.stringify({ username, password }),
    }),
  logout: () =>
    fetchJson<void>(`${BASE}/auth/logout`, { method: "POST" }),

  getConfig: () => fetchJson<AppConfig>(`${BASE}/config`),
  getTheme: () => fetchJson<Record<string, string>>(`${BASE}/theme`),
  getBranding: () => fetchJson<Branding>(`${BASE}/branding`),

  // App settings (framework @Constant values) — admin only
  getSettings: () => fetchJson<SettingMeta[]>(`${BASE}/settings`),
  saveSettings: (values: Record<string, unknown>) =>
    fetchJson<void>(`${BASE}/settings`, { method: "PUT", body: JSON.stringify(values) }),

  // Catalog CRUD
  // `filter` is an optional WidgetFilter predicate (a widget's config("filter", …)) applied
  // server-side — lets a dashboard widget scope its rows, e.g. "status != 'DRAFT'".
  listCatalog: (name: string, filter?: string) => {
    // Normalize the entity name (PascalCase → snake_case) so every caller — the built-in widgets
    // (which pre-snake) and SDK-authored custom widgets (which pass the raw `entityName`) — hits the
    // same URL and shares the in-flight GET dedup instead of double-fetching the same rows.
    name = toSnakeCase(name);
    const params = new URLSearchParams();
    if (filter) params.set("filter", filter);
    const qs = params.toString();
    return fetchJson<EntityRecord[]>(`${BASE}/catalogs/${name}${qs ? "?" + qs : ""}`);
  },
  // Server-side typeahead for ref pickers: capped, case-insensitive code/description match.
  searchCatalog: (name: string, q: string, limit = 30) => {
    const params = new URLSearchParams({ q, limit: String(limit) });
    return fetchJson<EntityRecord[]>(`${BASE}/catalogs/${name}?${params.toString()}`);
  },
  getCatalogItem: (name: string, id: string) =>
    fetchJson<EntityRecord>(`${BASE}/catalogs/${name}/${id}`),
  createCatalogItem: (name: string, data: EntityRecord) =>
    fetchJson<EntityRecord>(`${BASE}/catalogs/${name}`, {
      method: "POST",
      body: JSON.stringify(data),
    }),
  updateCatalogItem: (name: string, id: string, data: EntityRecord) =>
    fetchJson<EntityRecord>(`${BASE}/catalogs/${name}/${id}`, {
      method: "PUT",
      body: JSON.stringify(data),
    }),
  deleteCatalogItem: (name: string, id: string) =>
    fetchJson<void>(`${BASE}/catalogs/${name}/${id}`, { method: "DELETE" }),
  /** Server-side copy of a record: same attributes, fresh id/code. Backs the list's ⌘V paste. */
  duplicateCatalogItem: (name: string, id: string) =>
    fetchJson<EntityRecord>(`${BASE}/catalogs/${name}/${id}/duplicate`, { method: "POST" }),
  // Live rows of a related-list panel: the junction rows tied to record {id} (see RelatedListMeta).
  // The owner can be a catalog or a document, so the endpoint kind travels with the call.
  getRelatedList: (kind: "catalogs" | "documents", name: string, id: string, relatedName: string) =>
    fetchJson<EntityRecord[]>(`${BASE}/${kind}/${name}/${id}/related/${relatedName}`),

  // Document CRUD
  // `filter` is an optional WidgetFilter predicate (a widget's config("filter", …)) applied
  // server-side — lets a chart/list/calendar widget scope its rows, e.g. "status != 'DRAFT'".
  listDocuments: (name: string, from?: string, to?: string, filter?: string) => {
    // See listCatalog: normalize so built-in and SDK custom widgets share one fetch.
    name = toSnakeCase(name);
    const params = new URLSearchParams();
    if (from) params.set("from", from);
    if (to) params.set("to", to);
    if (filter) params.set("filter", filter);
    const qs = params.toString();
    return fetchJson<EntityRecord[]>(`${BASE}/documents/${name}${qs ? "?" + qs : ""}`);
  },
  getDocument: (name: string, id: string) =>
    fetchJson<EntityRecord>(`${BASE}/documents/${name}/${id}`),
  // Server-side typeahead for document ref pickers: capped, case-insensitive _number match.
  searchDocument: (name: string, q: string, limit = 30) => {
    const params = new URLSearchParams({ q, limit: String(limit) });
    return fetchJson<EntityRecord[]>(`${BASE}/documents/${name}?${params.toString()}`);
  },
  createDocument: (name: string, data: EntityRecord) =>
    fetchJson<EntityRecord>(`${BASE}/documents/${name}`, {
      method: "POST",
      body: JSON.stringify(data),
    }),
  updateDocument: (name: string, id: string, data: EntityRecord) =>
    fetchJson<EntityRecord>(`${BASE}/documents/${name}/${id}`, {
      method: "PUT",
      body: JSON.stringify(data),
    }),
  deleteDocument: (name: string, id: string) =>
    fetchJson<void>(`${BASE}/documents/${name}/${id}`, { method: "DELETE" }),
  /** Server-side copy of a document (attributes + line items, unposted, dated now). Backs ⌘V paste. */
  duplicateDocument: (name: string, id: string) =>
    fetchJson<EntityRecord>(`${BASE}/documents/${name}/${id}/duplicate`, { method: "POST" }),
  // Pre-aggregated buckets for a data widget (chart/stat/sparkline/gauge): a server-side GROUP BY
  // returning O(buckets) rows instead of the entity's whole table (#199, see ListDataController).
  // `params` is the ready-to-send query — metric/field, groupBy/groupByDate, seriesBy, filter,
  // dateField/from/to — exactly as built by the widget (see useWidgetBuckets).
  aggregateWidget: (kind: "catalogs" | "documents", name: string, params: Record<string, string>) => {
    // See listCatalog: normalize so built-in and SDK custom widgets share one fetch.
    name = toSnakeCase(name);
    const qs = new URLSearchParams(params).toString();
    return fetchJson<AggregateBuckets>(`${BASE}/list/${kind}/${name}/aggregate${qs ? "?" + qs : ""}`);
  },
  // Custom EntityView action: POSTs to the server handler and returns its ActionResult. A
  // toolbar action passes no id; a row/detail action passes the record id. The current toolbar
  // input values (if any) ride along in the body and reach the handler via ActionContext.
  runAction: (kind: string, name: string, key: string, id?: string, inputs?: Record<string, string>) =>
    fetchJson<ActionResult>(
      `${BASE}/actions/${kind}/${name}/${key}${id ? `?id=${encodeURIComponent(id)}` : ""}`,
      { method: "POST", body: JSON.stringify({ inputs: inputs ?? {} }) }
    ),
  /**
   * Run a server action over many records in ONE request (the list's batch selection). The server
   * invokes the handler per id sequentially and returns {ok, failed[], total} — per-id failures
   * don't abort the batch. Capped server-side at 500 ids.
   */
  runActionBatch: (kind: string, name: string, key: string, ids: string[], inputs?: Record<string, string>) =>
    fetchJson<BatchResult>(`${BASE}/actions/${kind}/${name}/${key}/batch`, {
      method: "POST",
      body: JSON.stringify({ ids, inputs: inputs ?? {} }),
    }),
  /** Soft-delete many records in one request; same {ok, failed[], total} contract as runActionBatch. */
  batchDelete: (kind: "catalogs" | "documents", name: string, ids: string[]) =>
    fetchJson<BatchResult>(`${BASE}/${kind}/${name}/batch-delete`, {
      method: "POST",
      body: JSON.stringify({ ids }),
    }),
  // Page-level action button (PageBuilder.actions): POSTs to the server handler resolved by
  // re-composing the page at {route}, and returns its ActionResult. The page's profile rides
  // along so the same page variant resolves; data writes refresh embedded lists over SSE.
  runPageAction: (route: string, key: string, profile?: string, inputs?: Record<string, string>) => {
    const params = new URLSearchParams({ route, key });
    if (profile) params.set("profile", profile);
    return fetchJson<ActionResult>(`${BASE}/divkit/page-action?${params.toString()}`, {
      method: "POST",
      body: JSON.stringify({ inputs: inputs ?? {} }),
    });
  },

  // Comments — a discussion thread on any catalog/document detail (see CommentController). Reads
  // and posts are gated server-side on read access to the owning entity; the author is stamped
  // from the session, never sent by the client.
  listComments: (kind: "catalogs" | "documents", name: string, id: string) =>
    fetchJson<CommentView[]>(`${BASE}/comments/${kind}/${name}/${id}`),
  addComment: (kind: "catalogs" | "documents", name: string, id: string, body: string, parentId?: string | null) =>
    fetchJson<CommentView>(`${BASE}/comments/${kind}/${name}/${id}`, {
      method: "POST",
      body: JSON.stringify({ body, parentId: parentId ?? null }),
    }),
  deleteComment: (commentId: string) =>
    fetchJson<void>(`${BASE}/comments/${commentId}`, { method: "DELETE" }),
  toggleCommentReaction: (commentId: string, emoji: string) =>
    fetchJson<CommentReaction[]>(`${BASE}/comments/${commentId}/reactions`, {
      method: "POST",
      body: JSON.stringify({ emoji }),
    }),
  // Cross-entity typeahead for the @ mention / # reference picker: matching readable records,
  // ranked and capped server-side (see MentionController). `people` narrows to the identity
  // catalog (falls back to all catalogs when the app has no Layout.identity link).
  searchMentions: (q: string, kind?: "people" | "catalogs" | "documents") => {
    const params = new URLSearchParams({ q });
    if (kind) params.set("kind", kind);
    return fetchJson<MentionSuggestion[]>(`${BASE}/mentions?${params.toString()}`);
  },
  // Resolve a pasted internal record URL to its live display so the compose box can swap the
  // link for a mention. Unreadable/unknown records come back readable=false with no display.
  resolveMention: (kind: "catalogs" | "documents", name: string, id: string) =>
    fetchJson<ResolvedMentionTarget>(
      `${BASE}/mentions/resolve?${new URLSearchParams({ kind, name, id }).toString()}`
    ),

  // Per-user notifications (see NotificationController). One newest-first window; `unread` restricts to
  // unread rows, `cursor` (from a previous page's nextCursor) resumes. Marking read returns the fresh
  // unread total so the badge updates without a refetch.
  getNotifications: (opts?: { unread?: boolean; cursor?: string }) => {
    const params = new URLSearchParams();
    if (opts?.unread) params.set("unread", "true");
    if (opts?.cursor) params.set("cursor", opts.cursor);
    const qs = params.toString();
    return fetchJson<NotificationPage>(`${BASE}/notifications${qs ? "?" + qs : ""}`);
  },
  markNotificationRead: (id: string) =>
    fetchJson<{ unreadCount: number }>(`${BASE}/notifications/${id}/read`, { method: "POST" }),
  markAllNotificationsRead: () =>
    fetchJson<{ marked: number; unreadCount: number }>(`${BASE}/notifications/read-all`, {
      method: "POST",
    }),

  // The ambient-presence snapshot: every viewed record the caller may read. Loaded once on startup;
  // live `presence` SSE deltas keep the client store current after that.
  getPresenceSnapshot: () => fetchJson<PresenceSnapshot>(`${BASE}/presence`),
  // Presence — route-level collaboration markers (see PresenceController). Post the pane's route `path`;
  // the server derives the presence identity (record / entity list / page). `enter` on open and a periodic
  // `heartbeat` keep the viewer alive (the server expires them by TTL once heartbeats stop); both return the
  // route's current viewers. Entity routes are gated server-side on read access; pages are visible to any
  // signed-in user.
  presence: (path: string, action: "enter" | "heartbeat") =>
    fetchJson<PresenceState>(`${BASE}/presence`, {
      method: "POST",
      body: JSON.stringify({ path, action }),
    }),
  // Best-effort leave on page/island teardown: `keepalive` lets the request outlive the unloading
  // document (and still carry the CSRF header, unlike sendBeacon). A dropped leave is harmless — the
  // server's presence TTL reaps the viewer anyway; this just makes them vanish for others promptly.
  leavePresence: (path: string) => {
    const headers: Record<string, string> = { "Content-Type": "application/json" };
    const csrf = readCsrfToken();
    if (csrf) headers[CSRF_HEADER] = csrf;
    return fetch(`${BASE}/presence`, {
      method: "POST",
      credentials: "same-origin",
      headers,
      body: JSON.stringify({ path, action: "leave" }),
      keepalive: true,
    }).catch(() => {
      /* best-effort; TTL backstops */
    });
  },

  postDocument: (name: string, id: string) =>
    fetchJson<EntityRecord>(`${BASE}/documents/${name}/${id}/post`, { method: "POST" }),
  unpostDocument: (name: string, id: string) =>
    fetchJson<EntityRecord>(`${BASE}/documents/${name}/${id}/unpost`, { method: "POST" }),

  // Register queries
  getMovements: (name: string, from?: string, to?: string) => {
    const params = new URLSearchParams();
    if (from) params.set("from", from);
    if (to) params.set("to", to);
    const qs = params.toString();
    return fetchJson<EntityRecord[]>(
      `${BASE}/registers/${name}/movements${qs ? "?" + qs : ""}`
    );
  },
  getBalance: (name: string, filters?: Record<string, string>) => {
    const params = new URLSearchParams(filters);
    const qs = params.toString();
    return fetchJson<EntityRecord[]>(
      `${BASE}/registers/${name}/balance${qs ? "?" + qs : ""}`
    );
  },
  getTurnover: (
    name: string,
    from: string,
    to: string,
    filters?: Record<string, string>
  ) => {
    const params = new URLSearchParams({ from, to, ...filters });
    return fetchJson<EntityRecord[]>(
      `${BASE}/registers/${name}/turnover?${params}`
    );
  },
};
