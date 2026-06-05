import { toast } from "sonner";
import type {
  AppConfig,
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
    throw new ApiError(message, res.status, fieldErrors);
  }
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  if (!text) return undefined as T;
  return JSON.parse(text);
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
