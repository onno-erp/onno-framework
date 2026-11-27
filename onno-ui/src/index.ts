import * as navigationBlocks from "./components/shell/navigation";
/**
 * `@onno/ui` — the onno UI as a library. An app's `main.tsx` calls {@link defineApp} once; pages
 * and components are built from the exports below. Entity names, fields and enum values are typed
 * from the app's generated `model.gen.ts`.
 *
 *   import { defineApp, EntityList, Page } from "@onno/ui";
 *
 *   defineApp({
 *     pages: [{ list: "Orders", component: OrdersPage, icon: ClipboardList }],
 *   });
 */
import { RecordActions as recordActionsImpl } from "./components/actions/record-actions";
import type { FC } from "react";
import type { UseMutationResult, UseQueryResult } from "@tanstack/react-query";
import * as recordBlocks from "./components/entities/record-form";
import * as blocks from "./components/pages/page-blocks";
import * as data from "./lib/query";
import * as measures from "./lib/measure";
import { defineApp as defineAppImpl } from "./start";
import { openRecord as openRecordImpl, useCommandPresentations as useCommandPresentationsImpl } from "./lib/app-config";
import { openRoute as openRouteImpl } from "./lib/navigation";
import { links as linksImpl, type OnnoLink } from "./lib/links";
import { useModel } from "./lib/model-store";
import { holdsPermission, holdsRole } from "./lib/app-model";
import { subscribeUiEvents as subscribeUiEventsImpl, useUiEvents as useUiEventsImpl } from "./hooks/use-ui-events";
import { localeOutsideReact, translateOutsideReact } from "./lib/messages-outside-react";
import type { CommandInputs, CommandResult } from "./lib/types";
import type {
  AggregateBuckets, AppDefinition, By, ConstantName, ConstantValue, InformationRecordOf, InformationRegisterName,
  InformationWhere, Measure, MeasureWhere, MovementWhere, NumericField, SettingProps, Where, CalendarProps, ChartProps, DocumentName, EntityListProps,
  EntityMapProps, EntityMutation, EntityName, EntityQuery, FieldOf, GaugeProps, KanbanProps, RecentRecordsProps,
  PermissionName, RoleName, RecordPageLayoutProps, RecordFieldsProps, RecordLinesProps, SectionOf,
  PagePath, RecordOf, RegisterName, SparklineProps, StatProps, TopField, ValuesOf,
} from "./public-types";
import { Stat as StatImpl } from "./components/widgets/stat";
import { Setting as SettingImpl } from "./components/widgets/setting";
import { Sparkline as SparklineImpl } from "./components/widgets/sparkline";
import { Gauge as GaugeImpl } from "./components/widgets/gauge";
import { Chart as ChartImpl } from "./components/widgets/chart";
import { Calendar as CalendarImpl } from "./components/widgets/calendar";
import { RecentRecords as RecentRecordsImpl } from "./components/widgets/recent-records";
import { Kanban as KanbanImpl } from "./components/widgets/kanban";
import { EntityMap as EntityMapImpl } from "./components/maps/entity-map";

export type * from "./public-types";
export type { CommandFeedback, CommandInputs, CommandResult, EntityRecord } from "./lib/types";
export type { ListRendererProps, ListRendererComponent } from "./components/lists/entity-list";
export type { RouteProps, RouteOptions } from "./lib/routes";
export type {
  DraftFile, ExtensionContext, ExtensionProps, ExtensionSlotName, ExtensionSlotProps, UiExtension,
} from "./lib/extension-types";
export type { Translate } from "./lib/messages";

type El = ReturnType<FC>;

// ---- the app ----------------------------------------------------------------------------------

/**
 * The whole app UI as one declaration — call it once from `main.tsx`. Renders the shell with the
 * app's pages, navigation and entity customizations, handed down the tree. Every part is optional.
 */
export const defineApp = defineAppImpl as <P extends PagePath = never>(app: AppDefinition<P>) => void;

/** Open a record's screen (`openRecord("Orders", id)`), or a new one, optionally pre-filled. */
export const openRecord = openRecordImpl as <E extends EntityName>(
  entity: E, id?: string, prefill?: Partial<Record<FieldOf<E>, string>>,
) => void;

/**
 * Open an `onno://` link (build one with {@link links}) or an app page path (`"/ops"`) in the
 * workspace. It replaces what the active tab shows, like a link in a browser window, and the tab's
 * Back returns to the previous screen; `{ newWorkspaceTab: true }` opens it in a new workspace tab
 * instead, `{ newTab: true }` in a new browser tab.
 */
export const openRoute = openRouteImpl as (
  route: OnnoLink | PagePath, options?: { newWorkspaceTab?: boolean; newTab?: boolean },
) => void;

/**
 * Typed `onno://` links — the deep-link protocol web and mobile clients share:
 * `openRoute(links.list("Orders", { where: { posted: false } }))`, `links.record("Orders", id)`.
 */
export const links = linksImpl as {
  record: <E extends EntityName>(entity: E, id: string) => OnnoLink;
  newRecord: <E extends EntityName>(entity: E, prefill?: Partial<Record<FieldOf<E>, string>>) => OnnoLink;
  list: <E extends EntityName>(entity: E, options?: { where?: Where<E> }) => OnnoLink;
  page: (path: PagePath) => OnnoLink;
};

/** The business model as the signed-in user sees it, or null until loaded. */
export { useModel };

/**
 * Whether the signed-in user holds any of these roles, compared normalized (false until the model
 * loads). No role passes implicitly.
 */
export function useHasRole(...roles: RoleName[]): boolean {
  const model = useModel();
  return !!model && roles.length > 0 && holdsRole(model, roles);
}

/**
 * Whether the signed-in user holds this permission (false until the model loads). True for every
 * user of an app without role grants; the server enforces every permission regardless.
 */
export function useHasPermission(permission: PermissionName): boolean {
  const model = useModel();
  return !!model && holdsPermission(model, permission);
}

// ---- page building blocks ---------------------------------------------------------------------

export const { Page, Row, DefaultView } = blocks;
export const RecordPage = recordBlocks.RecordPage as unknown as <E extends EntityName>(props: RecordPageLayoutProps<E>) => El;
export const RecordFields = recordBlocks.RecordFields as unknown as <E extends EntityName>(props: RecordFieldsProps<E>) => El;
export const RecordLines = recordBlocks.RecordLines as unknown as <E extends EntityName, S extends SectionOf<E>>(props: RecordLinesProps<E, S>) => El;
export { PageHeader } from "./views/route-view";
export { DefaultHome } from "./views/default-home";
/** An entity's full interactive list, defaulted from the model; props only for what differs. */
export const EntityList = blocks.EntityList as unknown as <E extends EntityName>(props: EntityListProps<E>) => El;

/** Widgets, typed per entity: `<Stat title="Revenue" measure={sum("Orders", "total", { posted: true })} />`. */
export const Stat = StatImpl as <E extends EntityName>(props: StatProps<E>) => El;
export const Sparkline = SparklineImpl as <E extends EntityName>(props: SparklineProps<E>) => El;
export const Gauge = GaugeImpl as <E extends EntityName>(props: GaugeProps<E>) => El;
export const Chart = ChartImpl as <E extends EntityName>(props: ChartProps<E>) => El;
export const Calendar = CalendarImpl as <E extends EntityName>(props: CalendarProps<E>) => El;
export const RecentRecords = RecentRecordsImpl as <E extends EntityName>(props: RecentRecordsProps<E>) => El;
export const Kanban = KanbanImpl as <E extends EntityName>(props: KanbanProps<E>) => El;
export const EntityMap = EntityMapImpl as <E extends EntityName>(props: EntityMapProps<E>) => El;
/** The time-range picker every stat and chart on the page follows. */
export { TimeRange } from "./components/widgets/chart";
/** An editor for one `@Constant` setting: `<Setting constant="Store Name" />`. */
export const Setting = SettingImpl as (props: SettingProps) => El;
/** Period-close administration: closes in force, close/reopen forms and audit (managers only). */
export { PeriodClose, type PeriodCloseProps } from "./components/widgets/period-close";
/** The signed-in user's business-process task inbox. */
export { TaskInbox } from "./components/widgets/task-inbox";
/**
 * The login page and its parts, for an app's own login page (`defineApp({ login: MyLogin })`):
 * `useSignIn()` signs in by password, an application `LoginMethod` (one-time code, …) or an SSO
 * redirect; `useLoginMethods()` reads what the server offers.
 */
export {
  DefaultLogin, LoginLayout, SignInMethods, PasswordLogin, SsoButtons, LoginButton,
  useSignIn, useLoginMethods, SignInError,
  type LoginPageProps, type LoginMethods, type SignIn, type SsoOption,
} from "./components/auth/sign-in";
/** User administration and the signed-in user's password change. */
export { UserAccounts, ChangePassword } from "./components/auth/user-accounts";

// ---- controls (the shell's own design system) -------------------------------------------------

export { Button } from "./components/ui/button";
export { Badge } from "./components/ui/badge";
export { Input } from "./components/ui/input";
export { Label } from "./components/ui/label";
export { Textarea } from "./components/ui/textarea";
export { Checkbox } from "./components/ui/checkbox";
export { Switch } from "./components/ui/switch";
export { Segmented } from "./components/ui/segmented";
export { Card, CardHeader, CardTitle, CardDescription, CardContent } from "./components/ui/card";
export { Popover, PopoverAnchor, PopoverTrigger, PopoverContent } from "./components/ui/popover";
export { Select, SelectContent, SelectGroup, SelectItem, SelectTrigger, SelectValue } from "./components/ui/select";
export { ContextMenuContent, ContextMenuItem, ContextMenuSub } from "./components/ui/context-menu";
export { DialogShell } from "./components/ui/dialog-shell";
export { FloatingWindow, windows, useWindow, useOpenWindows } from "./components/ui/window-manager";
export type { WindowSpec, WindowState, WindowOptions, WindowHandle, WindowControls, WindowInfo } from "./components/ui/window-manager";
export { ResponsiveActionPanel } from "./components/ui/responsive-action-panel";
export { WorkspaceToolbar } from "./components/ui/workspace-toolbar";
export type { WorkspaceToolbarLayout, WorkspaceToolbarProps } from "./components/ui/workspace-toolbar";
export { DatePicker } from "./components/fields/date-picker";
export { DateTimePicker } from "./components/fields/date-time-picker";
/** A wall-clock `LocalTime` field ("HH:mm[:ss]"). */
export { TimePicker } from "./components/fields/time-picker";
/** An `Instant` field: edited as wall time in the display zone (`onno.ui.time-zone`), stored as UTC. */
export { InstantPicker } from "./components/fields/instant-picker";
export { ColorPicker } from "./components/fields/color-picker";
export { RefSelect } from "./components/fields/ref-select";
export { EntityTags, EntityTagMenu } from "./components/entities/entity-tags";
export { CommentComposer, CommentBody } from "./components/collaboration/comments";
/** A record's change history (entities with `history = true`); also `<RecordPage form={form} history>`. */
export { RecordHistory, type RecordHistoryProps, type HistoryTarget } from "./components/collaboration/record-history";
/** A real link to an app page or `onno://` record link that opens it in the workspace. */
export { RouteLink, type RouteLinkProps } from "./components/pages/route-link";
/** The list itself over a {@link ListDescriptor} an app supplies (its own feed, scoped constraints) — {@link EntityList} builds that descriptor from the model. */
export { EntityListView, OptionsFacet, type ListDescriptor } from "./components/lists/entity-list";
/** Apply list options (an entity's `list` customization) to a list descriptor, for a component that composes `EntityListView` over its own feed. */
export { applyListOptions } from "./lib/entity-customizations";
/**
 * How the app presents an entity's commands (the `commands` of its customizations, merged): for a
 * surface that lists commands itself, so it shows the icons and placements the entity's own menus do.
 */
export const useCommandPresentations = useCommandPresentationsImpl as unknown as
  (kind: string, name: string) => Record<string, import("./public-types").CommandPresentation>;
export { toast } from "./components/ui/toast";
export { HintIcon } from "./components/ui/hint-icon";
export { Icon, type IconProps } from "./icons";
export { usePageSlide } from "./lib/use-page-slide";

// ---- data ---------------------------------------------------------------------------------------

/** The REST client (same-origin; session and CSRF handled). Prefer the typed hooks below. */
export { api } from "./lib/api";
/** The shell's TanStack Query client and hooks — use these, not a second copy of the library. */
export { queryClient } from "./lib/query";
export { useQuery, useMutation, useQueryClient, useInfiniteQuery } from "@tanstack/react-query";

/** An entity's records, live (refetched whenever the server reports a change to the entity). */
export const useRecords = data.useRecords as <E extends EntityName>(
  entity: E,
  options?: { where?: Where<E>; sort?: TopField<E> | `-${TopField<E>}`; limit?: number },
) => EntityQuery<RecordOf<E>[]>;
/** One record by id, live. */
export const useRecord = data.useRecord as <E extends EntityName>(entity: E, id: string | null | undefined) => EntityQuery<RecordOf<E>>;
/** A measure over an entity's records, optionally grouped: `useAggregate(sum("Orders", "total"), "status")`. */
export const useAggregate = data.useAggregate as unknown as <E extends EntityName>(measure: Measure<E>, by?: By<E>) => EntityQuery<AggregateBuckets>;

// ---- typed data questions ---------------------------------------------------------------------

/** How many records: `count("Orders", { open: true })`. */
export const count = measures.count as <E extends EntityName>(entity: E, where?: MeasureWhere<E>) => Measure<E>;
/** The total of a numeric field: `sum("Orders", "total", { posted: true })`. */
export const sum = measures.sum as <E extends EntityName>(entity: E, field: NumericField<E>, where?: MeasureWhere<E>) => Measure<E>;
/** The average of a numeric field. */
export const avg = measures.avg as <E extends EntityName>(entity: E, field: NumericField<E>, where?: MeasureWhere<E>) => Measure<E>;
/** The smallest value of a numeric field. */
export const min = measures.min as <E extends EntityName>(entity: E, field: NumericField<E>, where?: MeasureWhere<E>) => Measure<E>;
/** The largest value of a numeric field. */
export const max = measures.max as <E extends EntityName>(entity: E, field: NumericField<E>, where?: MeasureWhere<E>) => Measure<E>;
/** A register's balances — current, or `asOf` a date — the rows `where` selects (dimensions, resource balances). */
export const useBalance = data.useBalance as <E extends RegisterName>(register: E, options?: { where?: Where<E>; asOf?: string }) => EntityQuery<RecordOf<E>[]>;
/** A register's turnover between two ISO dates over the movements `where` selects. */
export const useTurnover = data.useTurnover as <E extends RegisterName>(register: E, options: { from: string; to: string; where?: MovementWhere<E> }) => EntityQuery<RecordOf<E>[]>;
/** A register's movements, newest first, optionally between two ISO dates and narrowed by `where`. */
export const useMovements = data.useMovements as <E extends RegisterName>(register: E, options?: { from?: string; to?: string; where?: MovementWhere<E> }) => EntityQuery<RecordOf<E>[]>;
/** An information register's slice of last values: per dimension tuple, the record in force `at` (default now). */
export const useInformationRegister = data.useInformationRegister as <N extends InformationRegisterName>(register: N, options?: { where?: InformationWhere<N>; at?: string }) => UseQueryResult<InformationRecordOf<N>[]>;
/** One `@Constant` setting's value (holders of `settings.edit` only, like the settings themselves). */
export const useConstant = data.useConstant as <N extends ConstantName>(name: N) => UseQueryResult<ConstantValue<N> | null | undefined>;
/** Set `@Constant` values: `set.mutate({ "Store Name": "Books & Co" })`; every `useConstant` refreshes. */
export const useSetConstants = data.useSetConstants as () => UseMutationResult<void, Error, { [N in ConstantName]?: ConstantValue<N> }>;
/** Create a record through the same server path as the default form; queries over the entity refresh. */
export const useCreateRecord = data.useCreateRecord as unknown as <E extends EntityName>(entity: E) => EntityMutation<ValuesOf<E>, RecordOf<E>>;
/** Partially update a record: `update.mutate({ id, values: { status } })`. */
export const useUpdateRecord = data.useUpdateRecord as unknown as <E extends EntityName>(entity: E) => EntityMutation<{ id: string; values: ValuesOf<E> }, RecordOf<E>>;
/** Delete (soft-delete) a record by id. */
export const useDeleteRecord = data.useDeleteRecord as <E extends EntityName>(entity: E) => EntityMutation<string, void>;
/** Post a document by id (writes its register movements). */
export const usePostDocument = data.usePostDocument as unknown as <E extends DocumentName>(entity: E) => EntityMutation<string, RecordOf<E>>;
/** Unpost a document by id (reverses its movements). */
export const useUnpostDocument = data.useUnpostDocument as unknown as <E extends DocumentName>(entity: E) => EntityMutation<string, RecordOf<E>>;
/**
 * Run one of the entity's commands (a `Commands` bean's) by key: `run.mutate({ id, inputs })` for a
 * record command, `run.mutate({ inputs })` for a collection command. Queries over the entity refresh.
 */
export const useCommand = data.useCommand as unknown as <E extends EntityName>(entity: E, key: string) => EntityMutation<{ id?: string; inputs?: CommandInputs } | void, CommandResult>;

// ---- live events ------------------------------------------------------------------------------

export type UiEvent = { type: string; entityType?: string; entityName?: string; id?: string; [key: string]: unknown };
export type UiEventFilter = { types?: readonly string[]; entityType?: string; entityName?: string; id?: string };

/**
 * Subscribe to the shell's shared live-update stream (never open your own EventSource). Entity data
 * needs none — the data hooks refresh by themselves; this is for comments, presence, tasks and the like.
 */
export const subscribeUiEvents = subscribeUiEventsImpl as unknown as (listener: (event: UiEvent) => void, filter?: UiEventFilter) => () => void;
/** {@link subscribeUiEvents} for a component, cleaned up on unmount. */
export const useUiEvents = useUiEventsImpl as unknown as (listener: (event: UiEvent) => void, filter?: UiEventFilter) => void;

// ---- text and formatting ----------------------------------------------------------------------

/** The app's chrome strings (`onno.ui.messages` over the bundled defaults) as a hook. */
export { useMessages as useTranslate, useAppLocale as useLocale } from "./providers/messages-provider";
/** A chrome string outside React (module scope, handlers). */
export const text = (key: string, params?: Record<string, string | number>): string => translateOutsideReact()(key, params);
/** The configured locale (`onno.ui.locale`), or undefined for the browser's. */
export const locale = (): string | undefined => localeOutsideReact();

/** A money amount in the app's locale: `formatMoney(order.total, "USD")` → `$1,250.00`. Empty for no value. */
export function formatMoney(value: unknown, currency: string): string {
  if (value == null || value === "") return "";
  const n = Number(value);
  if (Number.isNaN(n)) return String(value);
  return new Intl.NumberFormat(locale(), { style: "currency", currency }).format(n);
}

/** A date in the app's locale; "Jul 4" by default, or any `Intl.DateTimeFormat` options. */
export function formatDate(value: unknown, options: Intl.DateTimeFormatOptions = { month: "short", day: "numeric" }): string {
  if (typeof value !== "string" || !value) return "";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "" : new Intl.DateTimeFormat(locale(), options).format(date);
}

/** What a field shows on screen: a reference's or enum's label, else the value itself. */
export function displayOf(record: Record<string, unknown> | null | undefined, field: string): string {
  const value = record?.[`${field}Display`] ?? record?.[field];
  return value == null ? "" : String(value);
}

// ---- mobile -----------------------------------------------------------------------------------

/** Hide the mobile bottom navigation while a component shows a child state with its own Back. */
export { useMobileDetail } from "./components/shell/mobile-navigation-frame";

// ---- extension outlets ----------------------------------------------------------------------------

/** A named outlet for the app's `defineApp({ extensions })` contributions. */
export { ExtensionSlot } from "./lib/ui-extensions";

// ---- navigation composition ------------------------------------------------------------------
export { Navigation, NavigationSection } from "./components/shell/navigation";
export const NavigationLink = navigationBlocks.NavigationLink as (props: import("./public-types").NavigationLinkProps) => El;
export const DefaultNavigation = navigationBlocks.DefaultNavigation as (props: { sections: import("./public-types").NavSection[] }) => El;

export const NavigationGroup = navigationBlocks.NavigationGroup as (props: import("./public-types").NavigationGroupProps) => El;

// ---- shared controllers and replacement primitives --------------------------------------------
/** The exact controller used by the default record host. */
export const useRecordForm = recordBlocks.useRecordForm as unknown as <E extends EntityName = EntityName>(descriptor: import("./components/entities/record-form").FormDescriptor) => import("./public-types").RecordFormState<E>;
export const useRecordField = recordBlocks.useRecordField as unknown as <E extends EntityName, F extends TopField<E>>(
  form: import("./public-types").RecordFormState<E>, field: F, options?: import("./public-types").FieldCustomization<E, F>,
) => import("./public-types").RecordFieldBinding<E, F>;
export const RecordField = recordBlocks.RecordField as unknown as <E extends EntityName, F extends TopField<E>>(props: import("./public-types").RecordFieldProps<E, F>) => El;
export { useDefaultView } from "./components/pages/page-blocks";
export type { FormDescriptor } from "./components/entities/record-form";
export type { AttributeMeta, SystemColumnMeta, TabularSectionMeta, EnumValue, RefOptionSearch, RefOptionDecorations, RefOptionDecorateRequest } from "./lib/types";
export type { ListColumn, ListStateProps, ListSelectMode, PickRowState } from "./components/lists/entity-list";
export { ListCell } from "./components/lists/entity-list";
export { RefPickerDialog, type RefPickerDialogProps } from "./components/fields/ref-picker-dialog";
export type { RefSelectProps } from "./components/fields/ref-select";
export type { DatePickerProps } from "./components/fields/date-picker";
export type { DateTimePickerProps } from "./components/fields/date-time-picker";
export { ApiError, type FormFeedback } from "./lib/api";
export const RecordActions = recordActionsImpl as (props: { items: import("./public-types").RecordAction[] }) => El;
