import { whereParam, type WhereClause } from "./measure";
import { toast } from "../components/ui/toast";
import type {
  AppConfig,
  Branding,
  EntityRecord,
  AuthUser,
  UserAccountView,
  UiEvent,
  SettingMeta,
  CommandResult,
  CommandInputs,
  BatchResult,
  RefOptionSearch,
  RefOptionDecorateRequest,
  RefOptionDecorations,
  CommandFeedback,
  ProcessSnapshot,
  ProcessWorkItem,
  ProcessWorkItemEvent,
  TaskAssigneeOption,
} from "./types";

import { toSnakeCase } from "./utils";
// Type-only (erased at build time), so this doesn't create a runtime cycle with aggregate-data.
import type { AggregateBuckets } from "./aggregate-data";
import type { CommandDescriptor, CommandState, RecordActionDescriptor } from "./commands";

/** How many records a records read returns (default 500, the server's cap) and in which order. */
export type RecordsPage = { limit?: number; sort?: string };

/** A records read's query: `where` as JSON, `limit`, and `sort` (a field name, `-` prefix descending). */
function recordsParams(where: WhereClause | undefined, page: RecordsPage | undefined): URLSearchParams {
  const params = new URLSearchParams({ limit: String(page?.limit ?? 500) });
  const w = whereParam(where);
  if (w) params.set("where", w);
  if (page?.sort) {
    params.set("sort", page.sort.replace(/^-/, ""));
    params.set("dir", page.sort.startsWith("-") ? "desc" : "asc");
  }
  return params;
}

const BASE = "/api";
const CSRF_COOKIE = "XSRF-TOKEN";
const CSRF_HEADER = "X-XSRF-TOKEN";
const MUTATING = new Set(["POST", "PUT", "PATCH", "DELETE"]);
/** Matches CommandService.BATCH_LIMIT: one HTTP request stays bounded; larger UI batches chunk. */
const BATCH_REQUEST_LIMIT = 500;

// A 401 on any data call means the session lapsed while the tab was open. The AuthProvider
// registers a handler here so it can recover (silently re-auth via the IdP, or fall back to the
// login screen) instead of the request just failing into the void. The bad-credentials 401 from
// the login endpoint is excluded at the call site below — that isn't session loss.
let onUnauthorized: (() => void) | null = null;
export function setUnauthorizedHandler(handler: (() => void) | null) {
  onUnauthorized = handler;
}

/** Hand a 401 from a request made outside this module (a raw `fetch`) to the same auth recovery. */
export function handleUnauthorized() {
  onUnauthorized?.();
}

/**
 * Whether a failure is a lapsed session (HTTP 401). It goes to the auth recovery (re-authentication
 * or the login screen), so neither the api layer nor any caller reports it.
 */
export function isSessionLapse(error: unknown): boolean {
  return error instanceof ApiError && error.status === 401;
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
// no cached response, hence no staleness (a later fetch always hits the network afresh). Only the
// request is shared: each caller's own options still decide its error toast (see reportFailure).
const inFlightGets = new Map<string, Promise<unknown>>();

/**
 * Per-call request options. {@code silent} hands the error toast to the caller: the api layer
 * shows none, and the caller reports the rejection itself (see {@code notifyError}), so one
 * failure never produces two toasts (#505). Background calls whose failure must not interrupt the
 * user (live validation, presence) are silent too, and simply never report.
 */
export interface FetchOptions {
  silent?: boolean;
}

function fetchJson<T>(url: string, init?: RequestInit, opts?: FetchOptions): Promise<T> {
  const method = (init?.method ?? "GET").toUpperCase();
  if (method !== "GET") return reportFailure(doFetch<T>(url, init), opts);
  let request = inFlightGets.get(url) as Promise<T> | undefined;
  if (!request) {
    request = doFetch<T>(url, init).finally(() => inFlightGets.delete(url));
    inFlightGets.set(url, request);
  }
  return reportFailure(request, opts);
}

/**
 * The api layer's error toast, decided per call even when several callers share one in-flight GET:
 * a failure toasts once if any of its callers is not silent, and never for a silent-only one. The
 * error object is shared, so `notified` keeps it to one toast however many callers ask. A field-level
 * validation error is shown inline by the form, typed feedback is presented by its caller, and a
 * lapsed session (401) goes to the auth recovery — none of those is toasted here.
 */
function reportFailure<T>(request: Promise<T>, opts?: FetchOptions): Promise<T> {
  return request.catch((error: unknown) => {
    if (error instanceof ApiError && !opts?.silent && !error.notified
        && !isSessionLapse(error) && !error.fieldErrors && !error.feedback) {
      // eslint-disable-next-line onno/notify-caught-error -- this is the api layer's own toast; `notified` stops a second one
      toast.error(error.message);
      error.notified = true;
    }
    throw error;
  });
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
    let feedback: CommandFeedback | undefined;
    try {
      const body = await res.json();
      if (body.message) message = body.message;
      else if (body.error) message = body.error;
      if (body.fieldErrors && typeof body.fieldErrors === "object" && Object.keys(body.fieldErrors).length) {
        fieldErrors = body.fieldErrors as Record<string, string[]>;
      }
      if (typeof body.severity === "string" && ["info", "success", "warning", "error"].includes(body.severity)) {
        feedback = body as CommandFeedback;
      }
    } catch { /* ignore parse errors */ }
    const error = new ApiError(message, res.status, fieldErrors, feedback);
    // Whether to toast is each caller's decision (reportFailure), not the request's.
    // A 401 here is a lapsed session (the login endpoint's bad-credentials 401 is handled by its
    // own caller and never reaches a logged-in tab). Hand off to the registered recovery handler.
    if (res.status === 401 && !url.includes("/auth/login")) onUnauthorized?.();
    throw error;
  }
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  if (!text) return undefined as T;
  return JSON.parse(text);
}

/**
 * Run an arbitrarily large UI batch through the server's bounded batch endpoint. Chunks are
 * sequential because each request already fans out on the server; overlapping chunks would multiply
 * database concurrency and defeat onno.web.batch.parallelism. The caller still receives the original
 * one-summary contract.
 */
async function fetchBatch(
  url: string,
  ids: string[],
  body: (chunk: string[]) => Record<string, unknown>
): Promise<BatchResult> {
  // Preserve the endpoint's validation for an empty request, and avoid needless aggregation for the
  // overwhelmingly common one-chunk case.
  if (ids.length <= BATCH_REQUEST_LIMIT) {
    return fetchJson<BatchResult>(url, {
      method: "POST",
      body: JSON.stringify(body(ids)),
    });
  }

  const combined: BatchResult = { ok: 0, failed: [], total: 0 };
  for (let offset = 0; offset < ids.length; offset += BATCH_REQUEST_LIMIT) {
    const chunk = ids.slice(offset, offset + BATCH_REQUEST_LIMIT);
    const result = await fetchJson<BatchResult>(url, {
      method: "POST",
      body: JSON.stringify(body(chunk)),
    });
    combined.ok += result.ok;
    combined.failed.push(...result.failed);
    combined.total += result.total;
    if (!combined.feedback && result.feedback) {
      combined.feedback = result.feedback;
      combined.feedbackRejected = result.feedbackRejected;
    }
  }
  return combined;
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
    if (res.status === 401) onUnauthorized?.();
    throw new ApiError(message, res.status);
  }
  return (await res.json()) as StoredMedia;
}

/**
 * POST the selected record ids to a server download endpoint (a list's selection download, e.g. batch
 * print) and save the returned attachment. The file name comes from Content-Disposition (RFC 5987
 * {@code filename*} preferred), falling back to {@code fallbackName}.
 */
export async function downloadSelection(url: string, ids: string[], fallbackName: string): Promise<void> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  const csrf = readCsrfToken();
  if (csrf) headers[CSRF_HEADER] = csrf;
  const res = await fetch(url, {
    method: "POST",
    credentials: "same-origin",
    headers,
    body: JSON.stringify({ ids }),
  });
  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`;
    try {
      const body = await res.json();
      if (body.message) message = body.message;
      else if (body.error) message = body.error;
    } catch { /* ignore parse errors */ }
    if (res.status === 401) onUnauthorized?.();
    throw new ApiError(message, res.status);
  }
  const disposition = res.headers.get("Content-Disposition") ?? "";
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition)?.[1];
  const plain = /filename="?([^";]+)"?/i.exec(disposition)?.[1];
  const name = encoded ? decodeURIComponent(encoded) : plain ?? fallbackName;
  const href = URL.createObjectURL(await res.blob());
  try {
    const anchor = document.createElement("a");
    anchor.href = href;
    anchor.download = name;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
  } finally {
    setTimeout(() => URL.revokeObjectURL(href), 60_000);
  }
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

/** One period close in force (see PeriodCloseController). */
export interface PeriodLockView {
  /** `global`, `context:<name>`, `register:<name>` or `organization:<id>`. */
  scope: string;
  label?: string;
  /** The last closed day, inclusive (ISO date); null after a full reopen. */
  closedThrough: string | null;
  state?: string;
  updatedAt?: string | null;
  updatedBy?: string | null;
}

/** The closes in force, and whether the viewer may close and reopen periods. */
export interface PeriodCloseState {
  locks: PeriodLockView[];
  canManage: boolean;
}

/** One period-close audit row: a close, a reopen, or an override/adjustment inside a closed period. */
export interface PeriodCloseAuditEntry {
  id: string;
  at: string;
  action: "CLOSE" | "REOPEN" | "OVERRIDE" | "ADJUSTMENT" | string;
  scope: string;
  previous: string | null;
  closedThrough: string | null;
  actor: string | null;
  reason: string | null;
  hard?: boolean;
  operation?: string;
  documentType?: string;
  documentId?: string | null;
  documentDate?: string | null;
}

/** One changed value in a record-history entry (see RecordHistoryController). */
export interface HistoryChangeView {
  /** Tabular section name, or null for a header field. */
  section: string | null;
  line: number | null;
  rowChange: "ADDED" | "REMOVED" | "CHANGED" | null;
  field: string | null;
  label: string | null;
  type: "text" | "number" | "boolean" | "date" | "datetime" | "ref" | "enum";
  /** A secret attribute: changed, values withheld. */
  secret: boolean;
  old: string | null;
  new: string | null;
  oldDisplay?: string;
  newDisplay?: string;
}

/** One recorded event on a record: a write with its field changes, or a post/unpost/delete. */
export interface HistoryEntryView {
  id: string;
  event: string;
  at: string;
  actor: string;
  actorId: string | null;
  changes: HistoryChangeView[];
}

/** One keyset window of a record's history, newest first — the shared collection envelope. */
export type HistoryPage = CollectionPage<HistoryEntryView>;

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
export interface CollectionPage<T> {
  rows: T[];
  nextCursor: string | null;
  hasMore: boolean;
}

export interface NotificationPage extends CollectionPage<NotificationView> {
  items: NotificationView[];
  nextCursor: string | null;
  hasMore: boolean;
  unreadCount: number;
  /** The distinct notification types this user has, most-recent-first — the panel's modular filter tabs. */
  types: string[];
}

/**
 * The outcome of a dry-run validate (`POST /api/{kind}/{name}[/{id}]/validate`): the full write
 * lifecycle — declarative constraints, hooks, business rules — run server-side against the form's
 * current values without persisting. Always HTTP 200; failures live in the payload.
 */
export interface ValidationReport {
  valid: boolean;
  /** Per-field messages, keyed by attribute field name (or "code"/"description"/"number"). */
  fieldErrors: Record<string, string[]>;
  /** Cross-field (form-level) rule messages. */
  formErrors: string[];
}

export type FormFeedbackSeverity = "ERROR" | "WARNING" | "INFO";

export interface FormFeedback {
  severity: FormFeedbackSeverity;
  /** Blank targets the whole form; dot paths target a tabular-section column. */
  field: string;
  message: string;
}

export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
    /** Per-field validation messages from a 422, keyed by attribute field name. */
    public fieldErrors?: Record<string, string[]>,
    /** Typed expected command rejection (a CommandRejectedException, HTTP 422). */
    public feedback?: CommandFeedback
  ) {
    super(message);
  }

  /**
   * True once this failure has been shown to the user — by the api layer's toast or by a caller's
   * {@code notifyError}. Whoever shows it sets the flag, so nobody shows it a second time (#505).
   */
  notified = false;
}

/**
 * Validates an extension-supplied read path and returns its normalized same-origin form
 * (`/api/...` plus any query string). Rejects `..`/`.` segments (also percent-encoded), absolute or
 * protocol-relative URLs, and anything that does not resolve under `/api/`. This keeps extension reads
 * on the documented extension surface; it is not a security boundary (extensions share the session).
 */
export function normalizeExtensionApiPath(path: string): string {
  const invalid = () => new Error(`Expected a same-origin /api/ path, got "${path}"`);
  if (typeof path !== "string" || !path.startsWith("/api/") || path.startsWith("//") || path.includes("\\")) {
    throw invalid();
  }
  const pathname = path.split(/[?#]/, 1)[0];
  const segments = pathname.split("/").map((segment) => {
    try {
      return decodeURIComponent(segment);
    } catch {
      throw invalid();
    }
  });
  if (segments.some((segment) => segment === ".." || segment === "." || segment.includes("/") || segment.includes("\\"))) {
    throw invalid();
  }
  const url = new URL(path, window.location.origin);
  if (url.origin !== window.location.origin || !url.pathname.startsWith("/api/")) throw invalid();
  return url.pathname + url.search;
}

// Config & theme
export const api = {
  /**
   * Authenticated extension read. The path is validated by {@link normalizeExtensionApiPath} and shares
   * the host's in-flight GET deduplication, never a response cache.
   */
  getJson: <T,>(path: string): Promise<T> => {
    let normalized: string;
    try {
      normalized = normalizeExtensionApiPath(path);
    } catch (e) {
      return Promise.reject(e);
    }
    return fetchJson<T>(normalized, undefined, {silent:true});
  },
  getCurrentUser: () => fetchJson<AuthUser>(`${BASE}/auth/me`),
  login: (username: string, password: string, remember?: boolean) =>
    fetchJson<AuthUser>(`${BASE}/auth/login`, {
      method: "POST",
      body: JSON.stringify({ username, password, remember }),
    }),
  /** Sign in through an application `LoginMethod` bean (`POST /api/auth/login/{method}`). */
  loginWith: (method: string, credentials: Record<string, unknown>) =>
    fetchJson<AuthUser>(`${BASE}/auth/login/${encodeURIComponent(method)}`, {
      method: "POST",
      body: JSON.stringify(credentials),
    }, { silent: true }),
  /** A `LoginMethod`'s first step, e.g. send a code (`POST /api/auth/login/{method}/challenge`). */
  loginChallenge: (method: string, input: Record<string, unknown>) =>
    fetchJson<Record<string, unknown>>(`${BASE}/auth/login/${encodeURIComponent(method)}/challenge`, {
      method: "POST",
      body: JSON.stringify(input),
    }, { silent: true }),
  logout: () =>
    fetchJson<void>(`${BASE}/auth/logout`, { method: "POST" }),
  // Database-mode accounts. The caller renders its own error text, so these stay silent.
  getPasswordPolicy: () =>
    fetchJson<{ minLength: number }>(`${BASE}/auth/password-policy`, undefined, { silent: true }),
  changePassword: (currentPassword: string, newPassword: string) =>
    fetchJson<void>(`${BASE}/auth/password`, {
      method: "POST",
      body: JSON.stringify({ currentPassword, newPassword }),
    }, { silent: true }),
  listUserAccounts: () =>
    fetchJson<UserAccountView[]>(`${BASE}/auth/users`, undefined, { silent: true }),
  createUserAccount: (username: string, roles: string[], password?: string) =>
    fetchJson<{ account: UserAccountView; temporaryPassword: string | null }>(`${BASE}/auth/users`, {
      method: "POST",
      body: JSON.stringify({ username, roles, password: password || null }),
    }, { silent: true }),
  updateUserAccount: (id: string, patch: { roles?: string[]; enabled?: boolean }) =>
    fetchJson<UserAccountView>(`${BASE}/auth/users/${encodeURIComponent(id)}`, {
      method: "PATCH",
      body: JSON.stringify(patch),
    }, { silent: true }),
  resetUserPassword: (id: string) =>
    fetchJson<{ temporaryPassword: string }>(`${BASE}/auth/users/${encodeURIComponent(id)}/reset-password`, {
      method: "POST",
    }, { silent: true }),
  signOutUserAccount: (id: string) =>
    fetchJson<UserAccountView>(`${BASE}/auth/users/${encodeURIComponent(id)}/sign-out`, {
      method: "POST",
    }, { silent: true }),

  getConfig: () => fetchJson<AppConfig>(`${BASE}/config`),
  getTheme: () => fetchJson<Record<string, string>>(`${BASE}/theme`),
  getBranding: () => fetchJson<Branding>(`${BASE}/branding`),

  // App settings (framework @Constant values) — admin only
  getSettings: () => fetchJson<SettingMeta[]>(`${BASE}/settings`),
  saveSettings: (values: Record<string, unknown>, opts?: FetchOptions) =>
    fetchJson<void>(`${BASE}/settings`, { method: "PUT", body: JSON.stringify(values) }, opts),

  // Durable business-process work
  listProcessTasks: (cursor?: string | null) =>
    fetchJson<CollectionPage<ProcessWorkItem>>(`${BASE}/tasks${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ""}`),
  claimProcessTask: (id: string) =>
    fetchJson<ProcessWorkItem>(`${BASE}/tasks/${id}/claim`, { method: "POST" }),
  searchTaskAssignees: (query: string) =>
    fetchJson<TaskAssigneeOption[]>(`${BASE}/task-assignees?q=${encodeURIComponent(query)}`),
  getProcessTaskHistory: (id: string, cursor?: string | null) =>
    fetchJson<CollectionPage<ProcessWorkItemEvent>>(`${BASE}/tasks/${id}/history${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ""}`),
  delegateProcessTask: (id: string, targetActorId: string, reason: string) =>
    fetchJson<ProcessWorkItem>(`${BASE}/tasks/${id}/delegate`, {
      method: "POST",
      body: JSON.stringify({ targetActorId, reason }),
    }),
  completeProcessTask: (id: string, outcome: string) =>
    fetchJson<ProcessSnapshot>(`${BASE}/tasks/${id}/complete`, {
      method: "POST",
      body: JSON.stringify({ outcome }),
    }),

  // Catalog CRUD. These array-only convenience reads are bounded; widgets use the Page variants
  // below to retain continuation metadata. The old unbounded collection route no longer exists.
  listCatalog: (name: string, where?: WhereClause, page?: RecordsPage) => {
    // Normalize the entity name (PascalCase → snake_case) so every caller — the built-in components
    // (which pre-snake) and app code (which may pass the logical `entityName`) — hits the
    // same URL and shares the in-flight GET dedup instead of double-fetching the same rows.
    name = toSnakeCase(name);
    const params = recordsParams(where, page);
    return fetchJson<{ rows?: EntityRecord[] }>(
      `${BASE}/list/catalogs/${name}?${params.toString()}`
    ).then((page) => page.rows ?? []);
  },
  /** One window of catalog rows for a calendar: `params` carries the ge/le bounds on its date column. */
  listCatalogWindow: (name: string, params: URLSearchParams) =>
    fetchJson<{ rows?: EntityRecord[] }>(`${BASE}/list/catalogs/${toSnakeCase(name)}?${params.toString()}`)
      .then((page) => page.rows ?? []),
  /** Calendar window with continuation metadata; row-shaped widgets must retain `hasMore`. */
  listCatalogWindowPage: (name: string, params: URLSearchParams) =>
    fetchJson<CollectionPage<EntityRecord>>(`${BASE}/list/catalogs/${toSnakeCase(name)}?${params.toString()}`),
  searchRefOptions: (request: RefOptionSearch) =>
    fetchJson<EntityRecord[]>(`${BASE}/ref-options/search`, {
      method: "POST",
      body: JSON.stringify(request),
    }),
  /**
   * The target's list descriptor for the "Show all…" picker dialog (#411): its default columns
   * and filters, no write affordances.
   */
  getPickerList: <T>(kind: "catalogs" | "documents", name: string) =>
    fetchJson<T>(`${BASE}/ui/pickers/${kind}/${encodeURIComponent(name)}`),
  /**
   * The folders directly under {@code parent} (the roots without it) of a hierarchical catalog, each
   * flagged {@code hasChildFolders} so a folder tree draws an arrow only where it can expand (#411).
   */
  getCatalogChildFolders: (name: string, parent: string | null, cursor?: string | null) => {
    const qs = new URLSearchParams({ folders: "true" });
    if (parent) qs.set("parent", parent);
    if (cursor) qs.set("cursor", cursor);
    return fetchJson<CollectionPage<EntityRecord>>(`${BASE}/catalogs/${encodeURIComponent(name)}/children?${qs.toString()}`);
  },
  /**
   * The catalog tree pruned to the paths from the roots to {@code ids}, nested through `children`;
   * unreadable records and ancestors are left out (#411).
   */
  getCatalogPaths: (name: string, ids: string[]) =>
    fetchJson<EntityRecord[]>(`${BASE}/catalogs/${encodeURIComponent(name)}/tree?ids=${encodeURIComponent(ids.join(","))}`,
      undefined, { silent: true }),
  /** Which rows of a picker dialog window the dropdown would offer, and their decorations (#411). */
  decorateRefOptions: (request: RefOptionDecorateRequest) =>
    fetchJson<RefOptionDecorations>(`${BASE}/ref-options/decorate`, {
      method: "POST",
      body: JSON.stringify(request),
    }, { silent: true }),
  /** Remember a ref pick so the next empty-query search offers it first (#409). Answers 204. */
  rememberRefPick: (pick: { targetKind: "catalog" | "document"; targetName: string; id: string }) =>
    // Silent: remembering is a background courtesy; a failure must not toast over the pick.
    fetchJson<void>(`${BASE}/ref-options/recent`, {
      method: "POST",
      body: JSON.stringify(pick),
    }, { silent: true }),
  listCatalogPage: (name: string, where?: WhereClause, page?: RecordsPage & { cursor?: string | null }) => {
    const params = recordsParams(where, { ...page, limit: page?.limit ?? 100 });
    if (page?.cursor) params.set("cursor", page.cursor);
    return fetchJson<CollectionPage<EntityRecord>>(`${BASE}/list/catalogs/${toSnakeCase(name)}?${params}`);
  },
  getCatalogItem: (name: string, id: string) =>
    fetchJson<EntityRecord>(`${BASE}/catalogs/${name}/${id}`),
  createCatalogItem: (name: string, data: EntityRecord, opts?: FetchOptions) =>
    fetchJson<EntityRecord>(`${BASE}/catalogs/${name}`, {
      method: "POST",
      body: JSON.stringify(data),
    }, opts),
  updateCatalogItem: (name: string, id: string, data: EntityRecord, opts?: FetchOptions) =>
    fetchJson<EntityRecord>(`${BASE}/catalogs/${name}/${id}`, {
      method: "PUT",
      body: JSON.stringify(data),
    }, opts),
  deleteCatalogItem: (name: string, id: string, opts?: FetchOptions) =>
    fetchJson<void>(`${BASE}/catalogs/${name}/${id}`, { method: "DELETE" }, opts),
  /** Server-side copy of a record: same attributes, fresh id/code. Backs the list's ⌘V paste. */
  duplicateCatalogItem: (name: string, id: string) =>
    fetchJson<EntityRecord>(`${BASE}/catalogs/${name}/${id}/duplicate`, { method: "POST" }),

  // Document CRUD. Date-ranged widgets read one bounded keyset window from the list feed.
  listDocuments: (name: string, from?: string, to?: string, where?: WhereClause, page?: RecordsPage) => {
    // See listCatalog: normalize so every caller shares one fetch.
    name = toSnakeCase(name);
    const params = recordsParams(where, page);
    if (from) params.set("from", from);
    if (to) params.set("to", to);
    return fetchJson<{ rows?: EntityRecord[] }>(
      `${BASE}/list/documents/${name}?${params.toString()}`
    ).then((page) => page.rows ?? []);
  },
  listDocumentsPage: (name: string, from?: string, to?: string, where?: WhereClause,
      page?: RecordsPage & { cursor?: string | null }) => {
    const params = recordsParams(where, { ...page, limit: page?.limit ?? 100 });
    if (from) params.set("from", from);
    if (to) params.set("to", to);
    if (page?.cursor) params.set("cursor", page.cursor);
    return fetchJson<CollectionPage<EntityRecord>>(`${BASE}/list/documents/${toSnakeCase(name)}?${params}`);
  },
  getDocument: (name: string, id: string) =>
    fetchJson<EntityRecord>(`${BASE}/documents/${name}/${id}`),
  /** A saved record's current values, header actions, commands and write access, re-read in place. */
  getRecordRefresh: (kind: "catalogs" | "documents", name: string, id: string) =>
    fetchJson<{ record: EntityRecord; actions: RecordActionDescriptor[]; commands?: CommandDescriptor[]; readOnly: boolean; canPost?: boolean }>(
      `${BASE}/ui/record-refresh/${kind}/${encodeURIComponent(name)}/${id}`, undefined, { silent: true }),
  createDocument: (name: string, data: EntityRecord, opts?: FetchOptions) =>
    fetchJson<EntityRecord>(`${BASE}/documents/${name}`, {
      method: "POST",
      body: JSON.stringify(data),
    }, opts),
  updateDocument: (name: string, id: string, data: EntityRecord, opts?: FetchOptions) =>
    fetchJson<EntityRecord>(`${BASE}/documents/${name}/${id}`, {
      method: "PUT",
      body: JSON.stringify(data),
    }, opts),
  deleteDocument: (name: string, id: string) =>
    fetchJson<void>(`${BASE}/documents/${name}/${id}`, { method: "DELETE" }),
  /** Server-side copy of a document (attributes + line items, unposted, dated now). Backs ⌘V paste. */
  duplicateDocument: (name: string, id: string) =>
    fetchJson<EntityRecord>(`${BASE}/documents/${name}/${id}/duplicate`, { method: "POST" }),
  /**
   * Dry-run the write lifecycle server-side against the form's current values — live validation.
   * No id validates a create; with an id the values overlay the stored record, like a PUT. Silent:
   * a transient failure of a background check must not toast over the user's typing.
   */
  validateRecord: (kind: "catalogs" | "documents", name: string, id: string | null, data: EntityRecord) =>
    fetchJson<ValidationReport>(
      `${BASE}/${kind}/${name}/${id ? `${id}/` : ""}validate`,
      { method: "POST", body: JSON.stringify(data) },
      { silent: true }
    ),
  /** Run one of the entity's live checks (a `Checks` bean) against the form's unsaved values. */
  runCheck: (
    kind: "catalogs" | "documents",
    name: string,
    key: string,
    id: string | null,
    values: EntityRecord
  ) =>
    fetchJson<FormFeedback[]>(
      `${BASE}/checks/${kind}/${name}/${encodeURIComponent(key)}`,
      { method: "POST", body: JSON.stringify({ id, values }) },
      { silent: true }
    ),
  // Pre-aggregated buckets for a data component (chart/stat/sparkline/gauge): a server-side GROUP BY
  // returning O(buckets) rows instead of the entity's whole table (#199, see ListDataController).
  // `params` is the ready-to-send query — metric/field, groupBy/groupByDate, seriesBy, where,
  // dateField/from/to — exactly as built by the component (see useAggregateBuckets).
  aggregate: (kind: "catalogs" | "documents" | "registers", name: string, params: Record<string, string>) => {
    // See listCatalog: normalize so every caller shares one fetch.
    name = toSnakeCase(name);
    const qs = new URLSearchParams(params).toString();
    return fetchJson<AggregateBuckets>(`${BASE}/list/${kind}/${name}/aggregate${qs ? "?" + qs : ""}`);
  },
  /**
   * The opening values of a command's inputs — its server-side `defaults` hook evaluated for this
   * record/open. Only meaningful when the command descriptor carries dynamicForm: true.
   */
  getCommandDefaults: (kind: string, name: string, key: string, id?: string) =>
    fetchJson<{ values: Record<string, string>; rows: Record<string, Record<string, string>[]> }>(
      `${BASE}/commands/${kind}/${name}/${encodeURIComponent(key)}/defaults${id ? `?id=${encodeURIComponent(id)}` : ""}`
    ),
  /**
   * The entity's current command descriptors and, with an id, that record's command state. Lists
   * with late-bound commands call this before offering them; silent, so a transient failure keeps
   * the last-known/static commands.
   */
  getCommands: (kind: string, name: string, id?: string) =>
    fetchJson<{ commands: CommandDescriptor[]; state: Record<string, CommandState> }>(
      `${BASE}/commands/${kind}/${name}${id ? `?id=${encodeURIComponent(id)}` : ""}`,
      undefined,
      { silent: true }
    ),
  /**
   * Run an entity command: a record command passes the record id, a collection command none. The
   * collected inputs reach the handler through CommandContext.
   */
  runCommand: (kind: string, name: string, key: string, id?: string, inputs?: CommandInputs) =>
    fetchJson<CommandResult>(
      `${BASE}/commands/${kind}/${name}/${encodeURIComponent(key)}`,
      { method: "POST", body: JSON.stringify(id ? { id, inputs: inputs ?? {} } : { inputs: inputs ?? {} }) }
    ),
  /**
   * Run a record command over many records. Each request is capped at 500 ids; larger selections are
   * transparently chunked and folded back into one {ok, failed[], total} result.
   */
  runCommandBatch: (kind: string, name: string, key: string, ids: string[], inputs?: CommandInputs) =>
    fetchBatch(
      `${BASE}/commands/${kind}/${name}/${encodeURIComponent(key)}/batch`,
      ids,
      (chunk) => ({ ids: chunk, inputs: inputs ?? {} })
    ),
  /** Soft-delete many records; chunks at 500 and returns one combined batch summary. */
  batchDelete: (kind: "catalogs" | "documents", name: string, ids: string[]) =>
    fetchBatch(`${BASE}/${kind}/${name}/batch-delete`, ids, (chunk) => ({ ids: chunk })),
  // Comments — a discussion thread on any catalog/document detail (see CommentController). Reads
  // and posts are gated server-side on read access to the owning entity; the author is stamped
  // from the session, never sent by the client.
  // Period close (`/api/period-close`): the closes in force; managers close, reopen and read the audit.
  getPeriodClose: () => fetchJson<PeriodCloseState>(`${BASE}/period-close`),
  getPeriodCloseAudit: (limit = 20) => fetchJson<PeriodCloseAuditEntry[]>(`${BASE}/period-close/audit?limit=${limit}`),
  closePeriod: (request: { scope: string; closedThrough: string; hard?: boolean; reason?: string }) =>
    fetchJson<PeriodLockView>(`${BASE}/period-close/close`, { method: "POST", body: JSON.stringify(request) }),
  reopenPeriod: (request: { scope: string; closedThrough?: string | null; reason: string }) =>
    fetchJson<PeriodLockView>(`${BASE}/period-close/reopen`, { method: "POST", body: JSON.stringify(request) }),
  // Record change history — read-only, gated server-side like a read of the record itself.
  listHistory: (kind: "catalogs" | "documents", name: string, id: string, cursor?: string | null) =>
    fetchJson<HistoryPage>(
      `${BASE}/history/${kind}/${name}/${id}${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ""}`
    ),
  listComments: (kind: "catalogs" | "documents", name: string, id: string, cursor?: string | null) =>
    fetchJson<CollectionPage<CommentView>>(`${BASE}/comments/${kind}/${name}/${id}${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ""}`),
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
  // catalog (falls back to all catalogs when the app has no identity link).
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
  getPresenceSnapshot: () => fetchJson<PresenceSnapshot>(`${BASE}/presence`, undefined, { silent: true }),
  // Presence — route-level collaboration markers (see PresenceController). Post the pane's route `path`;
  // the server derives the presence identity (record / entity list / page). `enter` on open and a periodic
  // `heartbeat` keep the viewer alive (the server expires them by TTL once heartbeats stop); both return the
  // route's current viewers. Entity routes are gated server-side on read access; pages are visible to any
  // signed-in user.
  presence: (path: string, action: "enter" | "heartbeat") =>
    fetchJson<PresenceState>(`${BASE}/presence`, {
      method: "POST",
      body: JSON.stringify({ path, action }),
    }, { silent: true }),
  // Best-effort leave on page/boundary teardown: `keepalive` lets the request outlive the unloading
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

  // Register reads. `where` selects movement rows (movements, turnover) or balance rows (balance);
  // rows come back in the logical vocabulary (field names, `period`, `recorder`, `movementType`).
  // Each call reads one keyset window (`cursor`/`limit`) of the shared collection envelope;
  // `allRegisterRows` follows the continuation for callers that need every row.
  getMovements: (name: string, options: { from?: string; to?: string; where?: WhereClause } & RegisterPageRequest = {}) =>
    fetchRegisterPage(`${BASE}/registers/${name}/movements${registerQuery(options)}`),
  getBalance: (name: string, options: { where?: WhereClause; asOf?: string } & RegisterPageRequest = {}) =>
    fetchRegisterPage(`${BASE}/registers/${name}/balance${registerQuery(options)}`),
  getTurnover: (name: string, options: { from: string; to: string; where?: WhereClause } & RegisterPageRequest) =>
    fetchRegisterPage(`${BASE}/registers/${name}/turnover${registerQuery(options)}`),
  // An information register's slice of last values: per dimension tuple, the record in force `at`
  // (default now), narrowed by `where`. `name` is the register's logical name.
  getInformationSlice: (name: string, options: { at?: string; where?: WhereClause } = {}) => {
    const params = new URLSearchParams();
    if (options.at) params.set("at", options.at);
    const w = whereParam(options.where);
    if (w) params.set("where", w);
    const qs = params.toString();
    return fetchJson<EntityRecord[]>(
      `${BASE}/information-registers/${encodeURIComponent(name)}/slice-last${qs ? `?${qs}` : ""}`,
    );
  },
};

/** One keyset window of a register read: the continuation cursor and the window size. */
export interface RegisterPageRequest {
  cursor?: string | null;
  limit?: number;
}

/** Reads one register window; a bare array (an unpaged server) reads as one complete window. */
async function fetchRegisterPage(url: string): Promise<CollectionPage<EntityRecord>> {
  const body = await fetchJson<CollectionPage<EntityRecord> | EntityRecord[]>(url);
  if (Array.isArray(body)) return { rows: body, nextCursor: null, hasMore: false };
  return body;
}

/**
 * Every row of a paged register read, following `nextCursor` window by window. Totals and charts
 * must consume every window — a preview window is never a full total.
 */
export async function allRegisterRows(
  read: (page: RegisterPageRequest) => Promise<CollectionPage<EntityRecord>>,
  limit = 500,
): Promise<EntityRecord[]> {
  const rows: EntityRecord[] = [];
  let cursor: string | null = null;
  do {
    const page: CollectionPage<EntityRecord> = await read({ cursor, limit });
    rows.push(...page.rows);
    cursor = page.nextCursor;
    if (page.hasMore && !cursor) throw new Error("Register feed omitted its continuation");
  } while (cursor);
  return rows;
}

/** A register read's query string: the set options, `where` as compact JSON. */
function registerQuery(options: { from?: string; to?: string; asOf?: string; where?: WhereClause } & RegisterPageRequest): string {
  const params = new URLSearchParams();
  if (options.cursor) params.set("cursor", options.cursor);
  if (options.limit !== undefined) params.set("limit", String(options.limit));
  if (options.from) params.set("from", options.from);
  if (options.to) params.set("to", options.to);
  if (options.asOf) params.set("asOf", options.asOf);
  const w = whereParam(options.where);
  if (w) params.set("where", w);
  const qs = params.toString();
  return qs ? `?${qs}` : "";
}
