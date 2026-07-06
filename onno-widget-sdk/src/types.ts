/**
 * The metadata the server hands a widget renderer. Emitted from a server-side widget declaration —
 * {@code b.widget("Recent activity").type("eventLog").document(Invoice.class).config("since", "7d")}
 * — and delivered as the {@code widget} prop to the component registered under that type.
 *
 * Mirrors the framework's {@code DashboardWidgetMeta}; kept local so the SDK has no dependency on the
 * SPA package.
 */
export interface DashboardWidgetMeta {
  /** The widget's heading (from {@code .widget("…")}). */
  title: string;
  /** The registered type key (from {@code .type("…")}) — how this component was resolved. */
  widgetType: string;
  /** Sort order within the grid. */
  order: number;
  /** Grid width hint, e.g. {@code "half"} / {@code "full"}. */
  width: string;
  /** Bound entity kind: {@code "catalogs"} or {@code "documents"}. */
  entityType: string;
  /** Bound entity display name, e.g. {@code "Invoices"} (from {@code .document(...)}/{@code .catalog(...)}). */
  entityName: string;
  /** Row cap hint (from {@code .maxItems(...)}). */
  maxItems: number;
  /** Date field to bucket/scope by (from {@code .dateField(...)}). */
  dateField: string;
  /** Title/label field (from {@code .titleField(...)}). */
  titleField: string;
  /** Free-form key/values from {@code .config(key, value)} — the widget's own parameters. */
  extraConfig: Record<string, string>;
  /** Optional help text (from {@code .hint(...)}). */
  hint?: string;
}

/** A read record from the REST API — column name → value (display/ref sidecars included). */
export type EntityRecord = Record<string, unknown>;

/**
 * The read-only data client handed to widgets. Deliberately no create/update/delete/post — a widget
 * reads and renders. Every call is same-origin and rides the app's session + CSRF like the rest of
 * the UI.
 */
export interface OnnoReadApi {
  listCatalog(name: string, filter?: string): Promise<EntityRecord[]>;
  searchCatalog(name: string, q: string, limit?: number): Promise<EntityRecord[]>;
  getCatalogItem(name: string, id: string): Promise<EntityRecord>;
  listDocuments(name: string, from?: string, to?: string, filter?: string): Promise<EntityRecord[]>;
  searchDocument(name: string, q: string, limit?: number): Promise<EntityRecord[]>;
  getDocument(name: string, id: string): Promise<EntityRecord>;
  getBalance(name: string, filters?: Record<string, string>): Promise<unknown>;
  getTurnover(name: string, from: string, to: string, filters?: Record<string, string>): Promise<unknown>;
  getMovements(name: string, from?: string, to?: string): Promise<unknown>;
}

/** The shape the host installs on {@code window.onno} (see the SDK's runtime bindings). */
export interface OnnoHost {
  React: typeof import("react");
  jsxRuntime: unknown;
  registerWidget: (
    widgetType: string,
    component: import("react").ComponentType<{ widget: DashboardWidgetMeta }>
  ) => void;
  html: (strings: TemplateStringsArray, ...values: unknown[]) => unknown;
  api: OnnoReadApi;
  version: number;
}
