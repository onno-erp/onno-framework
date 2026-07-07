/**
 * Server-side sentinel returned in place of a stored secret value (see SecretRedactor).
 * The real value is never sent to the client; this only signals "a value is set".
 */
export const SECRET_SET = "__SECRET_SET__";

export interface EnumValue {
  /** Java constant name — the stable contract key written back to the server. */
  name: string;
  /**
   * Human/localized display label (from @EnumLabel); equals name when unlabelled. Optional so an
   * older server payload (no label) still type-checks — callers fall back to name.
   */
  label?: string;
  /**
   * Optional badge colour (CSS hex, from @EnumLabel(color = …)). When present the value renders as a
   * colored status pill — in the form dropdown and detail view — instead of plain text.
   */
  color?: string;
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
  /**
   * Column on the ref target shown as a secondary line under the name in the picker, to
   * disambiguate same-named records (from f.field(...).refSecondary(...); set only when isRef).
   */
  refSecondary?: string;
  isEnum: boolean;
  enumName?: string;
  /** Display title of the enumeration type (from @Enumeration.title); falls back to enumName. */
  enumTitle?: string;
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
  /** Optional help text (from .field(...).hint(...)); surfaced as a hoverable "?" by the label. */
  hint?: string;
  // Declarative validation constraints (mirrored from @Attribute) for instant inline errors.
  // The server re-checks all of these authoritatively before write.
  min?: number;
  max?: number;
  minLength?: number;
  pattern?: string;
  email?: boolean;
}

/**
 * A built-in system column (code/description on catalogs; number/date/posted on documents). It
 * carries the same field-hint config as an attribute so the layout DSL can hide/reorder/relabel it.
 * The {@link displayName} already folds in a {@code .field(...).label(...)} override (#154), so the
 * form/detail read it instead of hardcoding an English label.
 */
export interface SystemColumnMeta {
  fieldName: string;
  displayName: string;
  columnName: string;
  visibleInList: boolean;
  visibleInDetail: boolean;
  order: number;
  widthHint?: string;
  placeholder?: string;
  format?: string;
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
  // The framework's chrome strings (English defaults + onno.ui.messages overrides), keyed by the
  // ids in lib/messages.ts. MessagesProvider overlays this on the bundled defaults.
  messages?: Record<string, string>;
  // Present when the server-side update check is enabled; drives the "update available" notice.
  update?: UpdateInfo;
  // Absolute URLs of consumer widget-plugin ESM modules to load at boot (from onno-plugins/ on the
  // classpath, plus any onno.ui.plugins.extra-urls). Each self-registers via window.onno.registerWidget.
  pluginScripts?: string[];
}

// The result of the server's framework-version check (see UpdateChecker / ReleaseController).
export interface UpdateInfo {
  available: boolean;
  current: string | null;
  latest: string | null;
  url: string | null;
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
  // Optional fixed logo size in px; null keeps the intrinsic aspect ratio / default size.
  logoWidth?: number | null;
  logoHeight?: number | null;
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
  /** Present only on a `presence` event: the record's route kind ("catalogs"/"documents"). */
  kind?: string;
  /** Present only on a `presence` event: the current viewer set of the record. */
  viewers?: { userId: string; displayName: string; avatarUrl?: string }[];
  // Present only on a `notification` event (see UiEventPublisher.publishNotification). `id` carries the
  // stored notification's id. The store prepends the delta optimistically; a peer-node event trimmed to
  // fit the cluster payload cap arrives without `title`, which the store treats as "refetch the feed".
  notificationType?: string;
  title?: string;
  body?: string;
  link?: string;
  actorName?: string;
  actorAvatar?: string;
  createdAt?: string;
  unread?: boolean;
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
  /** Optional help text (from .widget(...).hint(...)); surfaced as a hoverable "?" by the title. */
  hint?: string;
  /**
   * The viewer's write access on the widget's entity (server-stamped from RBAC). When false,
   * interactive widgets disable their mutations — kanban drag, calendar reschedule. Absent (old
   * server) means unknown; treat as writable so behavior doesn't regress, REST enforces anyway.
   */
  canWrite?: boolean;
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
 * toast, an optional {@code onno://} route to navigate to, and whether to refresh the surface.
 */
export interface ActionResult {
  message?: string | null;
  navigate?: string | null;
  refresh?: boolean;
}

/** The summary a batch endpoint returns (batch action run / batch delete): counts + failed ids. */
export interface BatchResult {
  ok: number;
  failed: string[];
  total: number;
}
