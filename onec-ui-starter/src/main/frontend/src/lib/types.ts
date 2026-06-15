/**
 * Server-side sentinel returned in place of a stored secret value (see SecretRedactor).
 * The real value is never sent to the client; this only signals "a value is set".
 */
export const SECRET_SET = "__SECRET_SET__";

export interface EnumValue {
  name: string;
  id: string;
}

export interface AttributeMeta {
  fieldName: string;
  displayName: string;
  columnName: string;
  javaType: string;
  length: number;
  required: boolean;
  isRef: boolean;
  refTarget?: string;
  /** Whether refTarget names a catalog or a document (set only when isRef). */
  refKind?: "catalog" | "document";
  isEnum: boolean;
  enumName?: string;
  enumValues?: EnumValue[];
  /** Sensitive attribute: write-only, rendered as a password control, masked in views. */
  secret?: boolean;
  precision: number;
  scale: number;
  visibleInList: boolean;
  visibleInForm: boolean;
  visibleInDetail: boolean;
  order: number;
  group: string;
  widthHint: string;
  widget: string;
  /** Edit-form placeholder text (from .field(...).placeholder(...)). */
  placeholder?: string;
  // Declarative validation constraints (mirrored from @Attribute) for instant inline errors.
  // The server re-checks all of these authoritatively before write.
  min?: number;
  max?: number;
  minLength?: number;
  pattern?: string;
  email?: boolean;
}

export interface TabularSectionMeta {
  name: string;
  tableName: string;
  attributes: AttributeMeta[];
}

/**
 * An inline related-list (child rows) panel on a catalog or document view, backed by a junction —
 * a join catalog or an information register. The panel reads the junction rows whose {@link
 * viaField} ref points at the record being edited; a join-catalog junction also adds/removes rows
 * by creating/deleting join records (so a many-to-many edits inline like a document's tabular
 * section, with no mirrored data), while a register-backed junction is read-only. See {@code
 * RelatedList}.
 */
export interface RelatedListMeta {
  /** Panel id + REST sub-path: GET /api/{catalogs|documents}/{owner}/{id}/related/{name}. */
  name: string;
  /** Heading; blank means derive one from {@link name}. */
  label: string;
  /** Logical name of the junction the panel reads (and, for a catalog, writes). */
  joinCatalog: string;
  /** Whether the junction is a join catalog or an information register. Defaults to "catalog". */
  sourceKind?: "catalog" | "register";
  /** Whether the panel is read-only (no inline add/remove) — true for register-backed junctions. */
  readOnly?: boolean;
  /** Field on the junction that scopes a row to the parent record (set on add). */
  viaField: string;
  /** Field on the junction shown/picked per row (the "other side"). */
  displayField: string;
  /** Logical name of the catalog/document the {@link displayField} ref points at. */
  target: string;
  /** Whether {@link target} names a catalog or a document. */
  targetKind: "catalog" | "document";
  /**
   * Whether the panel also renders read-only in the detail/read view (default true). The form
   * widget renders every panel regardless; only the detail surface honors this flag.
   */
  showInDetail?: boolean;
  /** Junction columns to render (defaults to just the display ref). */
  columns: AttributeMeta[];
}

export interface CatalogMeta {
  name: string;
  tableName: string;
  codeLength: number;
  attributes: AttributeMeta[];
}

export interface DocumentMeta {
  name: string;
  tableName: string;
  numberLength: number;
  attributes: AttributeMeta[];
  tabularSections: TabularSectionMeta[];
}

export interface RegisterMeta {
  name: string;
  tableName: string;
  type: "BALANCE" | "TURNOVER";
  dimensions: AttributeMeta[];
  resources: AttributeMeta[];
}

export interface AppConfig {
  readOnly: boolean;
  basePath: string;
}

// A consumer's brand color overrides for one mode. Only the slots the app set are
// present; the React shell coalesces each over its neutral default.
export interface BrandColors {
  page?: string;
  surface?: string;
  border?: string;
  text?: string;
  muted?: string;
  primary?: string;
  primarySoft?: string;
}

// Consumer branding the web client applies at runtime (document title, favicon, login
// logo, and the island/tab accent). The DivKit chrome renders the logo + brand palette
// server-side; this covers the parts the React shell owns. Every field is optional.
export interface Branding {
  appName?: string | null;
  logoUrl?: string | null;
  logoUrlDark?: string | null;
  faviconUrl?: string | null;
  palette?: { light?: BrandColors; dark?: BrandColors };
}

export interface AuthUser {
  authenticated: boolean;
  username: string;
  roles: string[];
  // Active auth backend: "in-memory", "oidc", or "resource-server".
  mode?: string;
  // Where to send the user to sign in. Non-null only in OIDC mode, where login is a
  // full-page redirect to Keycloak rather than a password POST.
  loginUrl?: string | null;
  // Where to send the user to sign out. Non-null only in OIDC mode, where logout is a
  // full-page redirect that ends the Keycloak SSO session, not a fetch POST.
  logoutUrl?: string | null;
}

export interface UiEvent {
  type: string;
  entityType?: string;
  entityName?: string;
  id?: string;
  timestamp?: string;
}

export interface DashboardWidgetMeta {
  title: string;
  widgetType: string;
  order: number;
  width: string;
  entityType: string;
  entityName: string;
  maxItems: number;
  dateField: string;
  titleField: string;
  extraConfig: Record<string, string>;
}

export interface LayoutItem {
  name: string;
  type: string;
  href: string;
}

export interface LayoutSection {
  name: string;
  order: number;
  icon: string;
  placement: string;
  items: LayoutItem[];
}

export type EntityRecord = Record<string, unknown>;

/** One app setting, backed by a framework @Constant. Booleans carry widget "switch". */
export interface SettingMeta {
  name: string;
  displayName: string;
  type: string;
  widget: string;
  value: unknown;
}

/**
 * The result of a custom EntityView action ({@link ActionSpec} handler): an optional success
 * toast, an optional {@code onec://} route to navigate to, and whether to refresh the surface.
 */
export interface ActionResult {
  message?: string | null;
  navigate?: string | null;
  refresh?: boolean;
}
