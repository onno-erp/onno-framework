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
