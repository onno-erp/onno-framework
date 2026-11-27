import type { FieldInput, FieldWidth } from "./cell-format";
import type { WhereClause } from "./measure";

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
  isPolymorphicRef?: boolean;
  refTargets?: Array<{
    kind: "catalog" | "document";
    name: string;
    title: string;
    javaType: string;
  }>;
  /** Whether refTarget names a catalog or a document (set only when isRef). */
  refKind?: "catalog" | "document";
  /**
   * Column on the ref target shown as a secondary line under the name in the picker, to
   * disambiguate same-named records (an app customization; meaningful only when isRef).
   */
  refSecondary?: string;
  /**
   * Cascading picker (an app customization; meaningful only when isRef): target field → this
   * form's field. The form sends `where: { [target]: <the field's current value> }` with each
   * search; while a source field is empty the picker stays unfiltered.
   */
  refFilterBy?: Record<string, string>;
  /** Disable values selected in sibling rows of this tabular section. */
  uniqueWithinSection?: boolean;
  /**
   * Show the picker's "open selected record" button (resolved server-side from
   * f.refField(...).openButton(bool); on by default; set only when isRef). #409
   */
  refOpenButton?: boolean;
  /**
   * Show the picker's clear button (resolved server-side from .clearButton(bool); never true for
   * a required ref; set only when isRef). #409
   */
  refClearButton?: boolean;
  isEnum: boolean;
  /** Display title of the enumeration type (from @Enumeration.title). */
  enumTitle?: string;
  enumValues?: EnumValue[];
  /** Sensitive attribute: write-only, rendered as a password control, masked in views. */
  secret?: boolean;
  precision: number;
  scale: number;
  // Presentation the app's customization sets (`fields`); the server's model carries none.
  visibleInList?: boolean;
  visibleInForm?: boolean;
  visibleInDetail?: boolean;
  order?: number;
  group?: string;
  /** The field's share of the form row (an app customization; default full). */
  width?: FieldWidth;
  /** The control the field edits with (an app customization; default by type). */
  input?: FieldInput;
  /** Edit-form placeholder text (from .field(...).placeholder(...)). */
  placeholder?: string;
  /** Optional help text (from .field(...).hint(...)); surfaced as a hoverable "?" by the label. */
  hint?: string;
  // Declarative validation constraints (mirrored from @Attribute) for instant inline errors.
  // The server re-checks all of these authoritatively before write.
  min?: number;
  max?: number;
  minLength?: number;
  /** Validation limit from `@Size(max)`; never above `length`, the column's storage length. */
  maxLength?: number;
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
  // Set by the app's customization (`fields`); absent means shown, in model order.
  visibleInList?: boolean;
  visibleInDetail?: boolean;
  order?: number;
}

export interface TabularSectionMeta {
  name: string;
  tableName: string;
  /** Authored section title (field hint label on the section name); absent → humanized name. */
  label?: string;
  attributes: AttributeMeta[];
}

export interface RefOptionSearch {
  targetKind: "catalog" | "document";
  targetName: string;
  /**
   * The entity whose form holds the reference field ("catalog"/"document" + logical name): with
   * `fieldPath` it selects the field's server-side PickerOptions. Without an owner the options stay
   * undecorated.
   */
  ownerKind?: "catalog" | "document";
  ownerName?: string;
  query: string;
  limit: number;
  /** Which target records are offered (a cascading field's `{ supplier: <id> }`). */
  where?: WhereClause;
  fieldPath: string;
  formValues: EntityRecord;
  section?: string;
  rowIndex?: number;
  rowValues?: EntityRecord;
  documentId?: string;
}

/** A picker dialog window to decorate (#411): the field's context plus the ids on screen. */
export type RefOptionDecorateRequest = Omit<RefOptionSearch, "query" | "limit"> & { ids: string[] };

/** Which of the requested ids the dropdown would offer, and how to present them. */
export interface RefOptionDecorations {
  offered: string[];
  decorations: Record<string, { badge?: string; tone?: string; color?: string; disabled?: boolean; reason?: string }>;
}

export interface CatalogMeta {
  name: string;
  tableName: string;
  codeLength: number;
  attributes: AttributeMeta[];
  tabularSections?: TabularSectionMeta[];
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
  // onno.web.read-only: the application accepts no writes.
  readOnly: boolean;
  // BCP-47 locale configured by onno.ui.locale. The client uses it for date inputs, calendars, and
  // locale-sensitive widgets; absent on older servers means browser/default English behavior.
  locale?: string;
  // IANA zone from onno.ui.time-zone: the zone Instant fields are shown and edited in. Absent means
  // each viewer's browser zone.
  timeZone?: string;
  // The framework's chrome strings (English defaults + onno.ui.messages overrides), keyed by the
  // ids in lib/messages.ts. MessagesProvider overlays this on the bundled defaults.
  messages?: Record<string, string>;
  // Present when the server-side update check is enabled; drives the "update available" notice.
  update?: UpdateInfo;
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
// logo, and the island/tab accent). Every field is optional.
export interface Branding {
  appName?: string | null;
  logoUrl?: string | null;
  logoUrlDark?: string | null;
  // Optional fixed logo size in px; null keeps the intrinsic aspect ratio / default size.
  logoWidth?: number | null;
  logoHeight?: number | null;
  markUrl?: string | null;
  markUrlDark?: string | null;
  markFramed?: boolean;
  faviconUrl?: string | null;
  palette?: { light?: BrandColors; dark?: BrandColors };
}

export interface AuthUser {
  authenticated: boolean;
  username: string;
  roles: string[];
  // Active auth backend: "in-memory", "database", "oidc", or "resource-server".
  mode?: string;
  // Database mode: the account signed in with a one-time password and must set a new one before
  // anything else; the server answers 403 password_change_required to every other API call.
  passwordChangeRequired?: boolean;
  // Where to send the user to sign in. Non-null only in OIDC mode, where login is a
  // full-page redirect to Keycloak rather than a password POST.
  loginUrl?: string | null;
  // Where to send the user to sign out. Non-null only in OIDC mode, where logout is a
  // full-page redirect that ends the Keycloak SSO session, not a fetch POST.
  logoutUrl?: string | null;
}

/** A sign-in account in onno.auth.mode=database (GET /api/auth/users). */
export interface UserAccountView {
  id: string;
  username: string;
  roles: string[];
  enabled: boolean;
  passwordChangeRequired: boolean;
  createdAt: string;
  passwordChangedAt?: string | null;
  lastLoginAt?: string | null;
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
  // `tasks-changed` uses entityType "process-task" and carries only the affected instance id.
  // Candidate users/roles stay server-side; eligible clients refetch their authenticated inbox.
  // Present on the `ready` ack that opens each stream, and on the explicit dev `reload` push.
  // `bootId` identifies the server's application-context incarnation; `devMode` marks a
  // live-development server. A changed bootId across a reconnect means the server restarted under
  // us; a `reload` event is an on-demand refresh (the .onno-reload trigger file). In dev mode the
  // bus answers both with a full page reload (see ui-event-bus.ts).
  bootId?: string;
  devMode?: boolean;
}

/** Optional narrowing for consumers of the shared live-event bus (including custom widgets). */
export interface UiEventFilter {
  types?: readonly string[];
  entityType?: string;
  entityName?: string;
  id?: string;
}

export type EntityRecord = Record<string, unknown>;

/** One `@Constant` setting: its name, Java value type (`"String"`, `"Boolean"`, …) and value. */
export interface SettingMeta {
  name: string;
  type: string;
  value: unknown;
}

export type ProcessWorkItemStatus = "OPEN" | "CLAIMED" | "COMPLETED" | "CANCELLED";

/** One durable human task returned by the authenticated process inbox. */
export interface ProcessWorkItem {
  id: string;
  instanceId: string;
  tokenId?: string | null;
  definitionKey: string;
  definitionVersion: number;
  stepKey: string;
  title: string;
  status: ProcessWorkItemStatus;
  assigneeId?: string | null;
  assignee?: string | null;
  subject?: {
    kind: "catalogs" | "documents";
    entityName: string;
    id: string;
    label?: string | null;
  } | null;
  createdAt: string;
  claimedAt?: string | null;
  completedAt?: string | null;
  outcome?: string | null;
  outcomes: string[];
}

export interface TaskAssigneeOption {
  actorId: string;
  username: string;
  display: string;
  avatarUrl?: string | null;
}

export interface ProcessWorkItemEvent {
  id: string;
  workItemId: string;
  instanceId: string;
  type: "CREATED" | "CLAIMED" | "DELEGATED" | "COMPLETED" | "CANCELLED";
  actorId?: string | null;
  actor?: string | null;
  fromAssigneeId?: string | null;
  fromAssignee?: string | null;
  toAssigneeId?: string | null;
  toAssignee?: string | null;
  reason?: string | null;
  occurredAt: string;
  sequence: number;
}

export interface ProcessSnapshot {
  id: string;
  definitionKey: string;
  definitionVersion: number;
  currentStep?: string | null;
  activeSteps: string[];
  status: "ACTIVE" | "COMPLETED" | "CANCELLED";
  rootInstanceId: string;
  parentInstanceId?: string | null;
  parentTokenId?: string | null;
  startedById: string;
  startedBy: string;
  startedAt: string;
  updatedAt: string;
  completedAt?: string | null;
  cancelledAt?: string | null;
  cancelReason?: string | null;
  version: number;
}

/**
 * What an entity command handler returns: whether to reload, optional feedback, and optionally
 * where to go next — an `onno://` link or an app route the shell opens after the command.
 */
export interface CommandResult {
  refresh?: boolean;
  feedback?: CommandFeedback | null;
  navigateTo?: string | null;
}

export type FeedbackSeverity = "info" | "success" | "warning" | "error";

/**
 * What a command says about its outcome — meaning only; the client decides how to show it (see
 * `useCommandFeedback`). A rejection (HTTP 422) also names the inputs to fix.
 */
export interface CommandFeedback {
  severity: FeedbackSeverity;
  title?: string | null;
  message?: string | null;
  details?: string[];
  fieldErrors?: Record<string, string[]>;
  formErrors?: string[];
}

/**
 * The values a command posts as its {@code inputs}: a scalar input is a string; a repeatable row
 * group is an array of {column → value} row objects, read back server-side via
 * {@code CommandContext.inputRows(key)}.
 */
export type CommandInputs = Record<string, string | Record<string, string>[]>;

/** The summary a batch endpoint returns (batch command run / batch delete): counts + failed ids. */
export interface BatchResult {
  ok: number;
  failed: string[];
  total: number;
  /** First purposeful rejection, when one of the per-record handlers rejected the batch. */
  feedback?: CommandFeedback;
  feedbackRejected?: boolean;
}
