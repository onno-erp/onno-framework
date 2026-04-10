import { toast } from "sonner";
import type {
  AppConfig,
  CatalogMeta,
  DashboardWidgetMeta,
  DocumentMeta,
  EntityRecord,
  LayoutSection,
  RegisterMeta,
} from "./types";

const BASE = "/api/ui";

async function fetchJson<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, {
    headers: { "Content-Type": "application/json" },
    ...init,
  });
  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`;
    try {
      const body = await res.json();
      if (body.message) message = body.message;
      else if (body.error) message = body.error;
    } catch { /* ignore parse errors */ }
    toast.error(message);
    throw new Error(message);
  }
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  if (!text) return undefined as T;
  return JSON.parse(text);
}

// Config & theme
export const api = {
  getConfig: () => fetchJson<AppConfig>(`${BASE}/config`),
  getTheme: () => fetchJson<Record<string, string>>(`${BASE}/theme`),

  // Metadata
  getLayout: () => fetchJson<LayoutSection[]>(`${BASE}/metadata/layout`),
  getCatalogs: () => fetchJson<CatalogMeta[]>(`${BASE}/metadata/catalogs`),
  getDocuments: () => fetchJson<DocumentMeta[]>(`${BASE}/metadata/documents`),
  getRegisters: () => fetchJson<RegisterMeta[]>(`${BASE}/metadata/registers`),
  getDashboardWidgets: () => fetchJson<DashboardWidgetMeta[]>(`${BASE}/metadata/dashboard`),

  // Catalog CRUD
  listCatalog: (name: string) =>
    fetchJson<EntityRecord[]>(`${BASE}/catalogs/${name}`),
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
