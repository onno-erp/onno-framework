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
} from "./types";

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
    throw new Error(`${res.status} ${res.statusText}`);
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

async function fetchJson<T>(url: string, init?: RequestInit): Promise<T> {
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
 * A resolved {@code @}-mention in a comment body (see MentionResolver). The body carries the mention
 * as a `@[Display](kind/name/id)` token; this is the live resolution for the current viewer — display
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

/** A typeahead suggestion from `/api/mentions?q=` — one readable catalog/document record. */
export interface MentionSuggestion {
  kind: "catalogs" | "documents";
  name: string;
  entity: string;
  id: string;
  display: string;
  avatarUrl: string | null;
}

/** One comment in an entity's discussion thread (see CommentController). */
export interface CommentView {
  id: string;
  authorName: string | null;
  /** The author's avatar image URL when their account links to a record with an avatar; else null. */
  authorAvatarUrl: string | null;
  body: string;
  /** The mentions in `body`, resolved live for the current viewer (empty when none/disabled). */
  mentions: CommentMention[];
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
  listCatalog: (name: string) =>
    fetchJson<EntityRecord[]>(`${BASE}/catalogs/${name}`),
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
  // Live rows of a related-list panel: the junction rows tied to record {id} (see RelatedListMeta).
  // The owner can be a catalog or a document, so the endpoint kind travels with the call.
  getRelatedList: (kind: "catalogs" | "documents", name: string, id: string, relatedName: string) =>
    fetchJson<EntityRecord[]>(`${BASE}/${kind}/${name}/${id}/related/${relatedName}`),

  // Document CRUD
  listDocuments: (name: string, from?: string, to?: string) => {
    const params = new URLSearchParams();
    if (from) params.set("from", from);
    if (to) params.set("to", to);
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
  // Custom EntityView action: POSTs to the server handler and returns its ActionResult. A
  // toolbar action passes no id; a row/detail action passes the record id. The current toolbar
  // input values (if any) ride along in the body and reach the handler via ActionContext.
  runAction: (kind: string, name: string, key: string, id?: string, inputs?: Record<string, string>) =>
    fetchJson<ActionResult>(
      `${BASE}/actions/${kind}/${name}/${key}${id ? `?id=${encodeURIComponent(id)}` : ""}`,
      { method: "POST", body: JSON.stringify({ inputs: inputs ?? {} }) }
    ),
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
  addComment: (kind: "catalogs" | "documents", name: string, id: string, body: string) =>
    fetchJson<CommentView>(`${BASE}/comments/${kind}/${name}/${id}`, {
      method: "POST",
      body: JSON.stringify({ body }),
    }),
  deleteComment: (commentId: string) =>
    fetchJson<void>(`${BASE}/comments/${commentId}`, { method: "DELETE" }),
  // Cross-entity typeahead for the @-mention picker: every readable catalog/document record
  // matching q, ranked and capped server-side (see MentionController).
  searchMentions: (q: string) =>
    fetchJson<MentionSuggestion[]>(`${BASE}/mentions?q=${encodeURIComponent(q)}`),

  // The ambient-presence snapshot: every viewed record the caller may read. Loaded once on startup;
  // live `presence` SSE deltas keep the client store current after that.
  getPresenceSnapshot: () => fetchJson<PresenceSnapshot>(`${BASE}/presence`),
  // Presence — record-level collaboration markers (see PresenceController). `enter` on open and a
  // periodic `heartbeat` keep the viewer alive (the server expires them by TTL once heartbeats stop);
  // both return the record's current viewers. Gated server-side on read access to the owning entity.
  presence: (kind: "catalogs" | "documents", name: string, id: string,
             action: "enter" | "heartbeat") =>
    fetchJson<PresenceState>(`${BASE}/presence/${kind}/${name}/${id}`, {
      method: "POST",
      body: JSON.stringify({ action }),
    }),
  // Best-effort leave on page/island teardown: `keepalive` lets the request outlive the unloading
  // document (and still carry the CSRF header, unlike sendBeacon). A dropped leave is harmless — the
  // server's presence TTL reaps the viewer anyway; this just makes them vanish for others promptly.
  leavePresence: (kind: "catalogs" | "documents", name: string, id: string) => {
    const headers: Record<string, string> = { "Content-Type": "application/json" };
    const csrf = readCsrfToken();
    if (csrf) headers[CSRF_HEADER] = csrf;
    return fetch(`${BASE}/presence/${kind}/${name}/${id}`, {
      method: "POST",
      credentials: "same-origin",
      headers,
      body: JSON.stringify({ action: "leave" }),
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
