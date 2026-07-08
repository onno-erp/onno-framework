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
  /** Bound entity kind (singular): {@code "catalog"} or {@code "document"}. */
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

/** One resolved list column, as the entity's ListSpec/field hints produced it. */
export interface ListRendererColumn {
  /** The data column to read off each row (display/ref sidecars ride as {@code {col}_display} etc.). */
  columnName: string;
  /** The header label the table would have shown. */
  label: string;
  /** Authored width hint in px, or "" (size to content). */
  width: string;
  /** Display hint: "image"/"avatar" cells hold an image URL. */
  widget?: string;
  /** Display format: a date pattern ("dd-MM-yy") or number spec ("currency:EUR", "integer", …). */
  format?: string;
  /** Optional help text from the field hint. */
  hint?: string;
}

/** The descriptor slice a custom list renderer receives alongside the rows. */
export interface ListRendererDescriptor {
  /** Entity kind: "catalogs" | "documents" | "registers". */
  kind: string;
  /** The entity's route name (snake_case of the display name). */
  name: string;
  /** The list's resolved title. */
  title: string;
  /** The resolved columns (labels, formats, widgets) — the data contract the server decorated. */
  columns: ListRendererColumn[];
  /** The viewer's write access on the entity (RBAC-stamped; REST enforces regardless). */
  canWrite: boolean;
}

/**
 * The props a custom list-body renderer receives (see {@code registerListRenderer}). The framework
 * keeps the toolbar (search, filters, sort) and the feed (infinite scroll / pages, live refresh);
 * the renderer only draws the current window of rows and opens records through the callback.
 */
export interface ListRendererProps {
  /** The rows of the current window (all loaded rows in infinite mode; the page in paged mode). */
  rows: EntityRecord[];
  list: ListRendererDescriptor;
  /** Open a record's detail pane (no-op for rows without an id). */
  open: (row: EntityRecord) => void;
  /** The record's {@code onno://} detail route, or null when it has none. */
  openUrl: (row: EntityRecord) => string | null;
}

/**
 * The host's UI primitives, re-exposed to widgets so a custom widget renders the *real*
 * design-system controls (Radix-backed Select/Popover, the app's Button/Segmented/Badge/…) instead
 * of hand-rolled lookalikes. They carry the host's Tailwind classes — already emitted into the host
 * stylesheet — so a widget reusing them sidesteps the class-emission gotcha (utilities the host
 * doesn't itself emit produce no CSS when compiled outside the host build) and never drifts from the
 * product's look.
 *
 * The props are the underlying components' own (Radix + the host's variants); typed loosely here
 * because the SDK can't depend on the SPA package. In practice you pass the same props you'd pass a
 * shadcn/Radix {@code Select}/{@code Popover}/{@code Button}.
 *
 * @example
 *   import { ui } from "@onno/widget-sdk";
 *   const { Select, SelectTrigger, SelectContent, SelectItem, Button, Segmented } = ui;
 */
export interface OnnoUi {
  Button: import("react").ComponentType<any>;
  Badge: import("react").ComponentType<any>;
  Input: import("react").ComponentType<any>;
  Label: import("react").ComponentType<any>;
  Textarea: import("react").ComponentType<any>;
  Checkbox: import("react").ComponentType<any>;
  Switch: import("react").ComponentType<any>;
  Segmented: import("react").ComponentType<any>;
  DatePicker: import("react").ComponentType<any>;
  Card: import("react").ComponentType<any>;
  CardHeader: import("react").ComponentType<any>;
  CardTitle: import("react").ComponentType<any>;
  CardDescription: import("react").ComponentType<any>;
  CardContent: import("react").ComponentType<any>;
  Popover: import("react").ComponentType<any>;
  PopoverTrigger: import("react").ComponentType<any>;
  PopoverContent: import("react").ComponentType<any>;
  Select: import("react").ComponentType<any>;
  SelectContent: import("react").ComponentType<any>;
  SelectGroup: import("react").ComponentType<any>;
  SelectItem: import("react").ComponentType<any>;
  SelectTrigger: import("react").ComponentType<any>;
  SelectValue: import("react").ComponentType<any>;
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
  /** The host's UI component primitives (see {@link OnnoUi}). Present from host contract v2. */
  ui: OnnoUi;
  version: number;
}
