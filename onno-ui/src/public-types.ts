import type { ComponentType, ReactNode } from "react";
import type { IconComponent } from "./lib/icons";
import type { LucideIcon } from "lucide-react";
import type { FieldInput, FieldWidth, ValueFormat } from "./lib/cell-format";
export type { LucideIcon };
export type { DateStyle, FieldInput, FieldWidth, ValueFormat } from "./lib/cell-format";
export type { RowWidth } from "./components/pages/page-blocks";
export type { OnnoLink } from "./lib/links";
import type { UseMutationResult, UseQueryResult } from "@tanstack/react-query";
import type { AppModel, ModelEntity } from "./lib/app-model";
import type { EntityRecord } from "./lib/types";
import type { ListDescriptor, ListRendererProps } from "./components/lists/entity-list";
import type { ShellNavItem, ShellNavSection } from "./lib/view-types";
import type { UiExtension } from "./lib/extension-types";
import type { LoginPageProps } from "./components/auth/sign-in";
import type { StatProps as StatImpl } from "./components/widgets/stat";
import type { SparklineProps as SparklineImpl } from "./components/widgets/sparkline";
import type { GaugeProps as GaugeImpl } from "./components/widgets/gauge";
import type { ChartProps as ChartImpl, ChartSeries as ChartSeriesImpl, TimeRangeProps } from "./components/widgets/chart";
import type { CalendarProps as CalendarImpl } from "./components/widgets/calendar";
import type { RecentRecordsProps as RecentRecordsImpl } from "./components/widgets/recent-records";
import type { KanbanProps as KanbanImpl } from "./components/widgets/kanban";
import type { EntityMapProps as EntityMapImpl } from "./components/maps/entity-map";
import type { SettingProps as SettingImpl } from "./components/widgets/setting";
import type { TaskInboxProps } from "./components/widgets/task-inbox";
import type { ChangePasswordProps, UserAccountsProps } from "./components/auth/user-accounts";

/**
 * The typed surface of `@onno/ui`: entity names, records and fields from the app's generated model,
 * and the shapes an app declares (pages, navigation, entity customizations, widget props).
 */

export type NavItem = Omit<ShellNavItem, "icon"> & { icon?: LucideIcon };
export type NavGroup = { title: string; icon?: LucideIcon | null; actions?: ReactNode; items: NavNode[]; path?: never; label?: never };
export type NavNode = NavItem | NavGroup;
export type NavSection = Omit<ShellNavSection, "icon" | "items"> & { icon?: LucideIcon | null; items: NavNode[] };
export type { AppModel, ModelEntity };

/** Props a selection-toolbar component receives while list rows are selected. */
export type ListSelectionProps = { ids: string[]; complete: () => void };

// ---- the typed model ---------------------------------------------------------------------------

/**
 * Every entity of the app, augmented by the generated `model.gen.ts` (the `su.onno.ui` Gradle
 * plugin writes it from the Java model on each build). With it, entity names autocomplete and the
 * hooks/components below are typed per entity; without it everything degrades to plain strings.
 */
// eslint-disable-next-line @typescript-eslint/no-empty-interface
export interface OnnoEntities {}

/** Every information register, augmented by `model.gen.ts`: `{ record; field; periodic }` by logical name. */
// eslint-disable-next-line @typescript-eslint/no-empty-interface
export interface OnnoInformationRegisters {}

/** Every `@Constant` setting, augmented by `model.gen.ts`: `{ value }` (its value type) by name. */
// eslint-disable-next-line @typescript-eslint/no-empty-interface
export interface OnnoConstants {}

/**
 * Every role the app knows, augmented by `model.gen.ts` with the roles its `@AccessControl`
 * annotations name (plus `ADMIN`). A role that appears only at runtime — in a command's
 * `roles(...)` or the configured users — is added by the app:
 * `declare module "@onno/ui" { interface OnnoRoles { SUPPORT: true } }`.
 */
// eslint-disable-next-line @typescript-eslint/no-empty-interface
export interface OnnoRoles {}

/** A role name, checked against `OnnoRoles` when generated. */
export type RoleName = [keyof OnnoRoles] extends [never] ? string : Extract<keyof OnnoRoles, string>;

type Entities = OnnoEntities;
type Typed = [keyof Entities] extends [never] ? false : true;

/** A logical entity name — `"Orders"`, `"Stock Receipts"` — checked against the model when generated. */
export type EntityName = Typed extends true ? Extract<keyof Entities, string> : string;
// `any` (an entity the generated model may not have) reads as untyped, like a missing model.
type EntryOf<E> = 0 extends 1 & E ? never : E extends keyof Entities ? Entities[E] : never;
/** The names of entities of one kind. */
export type EntityNameOfKind<K extends string> = Typed extends true
  ? { [N in keyof Entities]: Entities[N] extends { kind: K } ? N : never }[keyof Entities] & string
  : string;
export type CatalogName = EntityNameOfKind<"catalogs">;
export type DocumentName = EntityNameOfKind<"documents">;
export type RegisterName = EntityNameOfKind<"registers">;

/** The record type of an entity (what reads return), e.g. `RecordOf<"Orders">` is `Order`. */
export type RecordOf<E> = [EntryOf<E>] extends [never] ? EntityRecord : EntryOf<E> extends { record: infer R } ? R : EntityRecord;
/** An entity's field names (`"section.field"` for tabular-section columns). */
export type FieldOf<E> = [EntryOf<E>] extends [never] ? string : EntryOf<E> extends { field: infer F } ? F & string : string;
/** An entity's own fields (not tabular-section columns). */
export type TopField<E> = Exclude<FieldOf<E>, `${string}.${string}`>;
/**
 * The fields a record form lays out: the entity's own fields, minus its tabular sections (they
 * render below) and a document's `number`, `date` and `posted` (the form header shows those).
 */
export type FormField<E> = [EntryOf<E>] extends [never]
  ? string
  : Exclude<TopField<E>, SectionOf<E> | (EntryOf<E> extends { kind: "documents" } ? "number" | "date" | "posted" : never)>;
/** What a create/update body may carry: the record's own fields (companions like `…Display` excluded). */
export type ValuesOf<E> = [EntryOf<E>] extends [never]
  ? EntityRecord
  : Partial<{ [K in TopField<E> & keyof RecordOf<E>]: RecordOf<E>[K] }>;

/** Common keys of every catalog row. */
export type CatalogRecord = {
  id: string;
  code: string | null;
  description: string | null;
  deletionMark: boolean;
  folder?: boolean;
  parent?: string | null;
  version: number;
};
/** Common keys of every document row. */
export type DocumentRecord = {
  id: string;
  number: string | null;
  date: string | null;
  posted: boolean;
  deletionMark: boolean;
  version: number;
};
/** Common keys of every tabular-section row. */
export type TabularRow = { id: string; parentId: string; lineNumber: number };
/** Common keys of register movement/balance rows (`recorder` is the posting document's id). */
export type RegisterRow = { id?: string; period?: string; movementType?: "RECEIPT" | "EXPENSE"; recorder?: string; active?: boolean };
/** Common keys of information-register rows (`period` on a periodic register only). */
export type InformationRow = { id: string; period?: string };
/** A resolved reference (`<field>Ref`). `T` is the target's logical name. */
export type RefInfo<T extends string = string> = {
  id: string | null;
  type: T;
  display: string;
  kind?: "catalog" | "document";
  javaType?: string;
  code?: string;
  avatarUrl?: string;
  color?: string;
  restricted?: boolean;
};

// ---- components and customization --------------------------------------------------------------

/** The list body: the grid, or an alternate view the list declares (`map`, `calendar`, `timeline`, `custom`). */
export type ListView = "table" | "map" | "custom" | "calendar" | "timeline";

/**
 * An entity's list. Pass an injected `list` or an `entity` to fetch its descriptor.
 * It starts from the app's customization of the entity (`defineApp({ entities })`); props change
 * this list only. `defaultX` sets where it starts; `x` with `onXChange` hands that state to the
 * parent (controlled, like `<input value onChange>`).
 */
interface EntityListOptions<E extends EntityName = EntityName> extends Omit<ListCustomization<E>, "sort" | "groupBy" | "selection"> {
  selectionToolbar?: ComponentType<ListSelectionProps>;
  /** Descriptor injected into an entity list page; no second view request. */
  list?: ListPageProps<E>["list"];
  /** Restyle fields in this list only, on top of the entity's `fields` customization. */
  overrides?: EntityCustomization<E>["fields"];
  commands?: EntityCustomization<E>["commands"];
  rowStyle?: EntityCustomization<E>["rowStyle"];
  /** The entity, by logical name (`"Orders"`). */
  entity?: E;
  /** How this list presents, on top of the app's customization of the entity. */
  customization?: EntityCustomization<E>;
  /** Show only these fields, in order. */
  columns?: TopField<E>[];
  labels?: Partial<Record<TopField<E>, string>>;
  /** Which records the list shows, always: `{ open: true }` (the viewer's filters apply on top). */
  where?: Where<E>;
  title?: string;
  /** Grow to fill the page height and scroll internally. */
  fill?: boolean;

  defaultView?: ListView;
  defaultGroupBy?: TopField<E>;
  /** `-` prefix for descending. */
  defaultSort?: TopField<E> | `-${TopField<E>}`;

  view?: ListView;
  onViewChange?: (view: ListView) => void;
  /** The grouping field (`""` = flat). */
  groupBy?: TopField<E> | "";
  onGroupByChange?: (field: string) => void;
  sort?: TopField<E> | `-${TopField<E>}` | "";
  onSortChange?: (sort: string) => void;
  search?: string;
  onSearchChange?: (search: string) => void;
  /** Selected record ids. */
  selection?: string[];
  onSelectionChange?: (ids: string[]) => void;
}

/** Supply a descriptor injected by the router, or an entity whose descriptor should be fetched. */
export type EntityListProps<E extends EntityName = EntityName> = EntityListOptions<E> & (
  | { list: ListPageProps<E>["list"]; entity?: never }
  | { entity: E; list?: never }
);

/** A tabular-section column's name within its row (`"book"` of `"items.book"`). */
type RowColumnOf<E> = FieldOf<E> extends infer F ? (F extends `${string}.${infer C}` ? C : never) : never;

/**
 * How one field presents (key = field name, `"section.field"` for line columns). `format`, `input`
 * and `width` are typed values, never pattern strings.
 */
export interface FieldCustomization<E extends EntityName = EntityName, F extends string = string> {
  /** A command group (server `group("Change status")`) offered by right-clicking this field's list cell. */
  commandGroup?: string;
  label?: string;
  hidden?: boolean;
  hideInList?: boolean;
  hideInForm?: boolean;
  group?: string;
  /** The field's share of the form row (default `"full"`); two `"half"` fields pair up. */
  width?: FieldWidth;
  /** The control the field edits with — and, for media and colors, how its list cell shows. */
  input?: FieldInput;
  /** How the value displays in lists and group subtotals: `{ money: "USD" }`, `{ date: "medium" }`. */
  format?: ValueFormat;
  /** The list cell, drawn by the app; wins over `format` and `input`. */
  render?: (value: F extends keyof RecordOf<E> ? RecordOf<E>[F] : unknown, record: RecordOf<E>) => ReactNode;
  placeholder?: string;
  hint?: string;
  /** Ref picker: a field of the target shown under each option's name. */
  refSecondary?: string;
  /**
   * Picker cascade: the picker offers only target records whose field equals one of this form's
   * fields — target field → this form's field (a line column may name a column of its own row):
   * `{ supplier: "supplier" }`. Changing the source field clears the pick.
   */
  filterBy?: Record<string, TopField<E> | RowColumnOf<E>>;
  openButton?: boolean;
  clearButton?: boolean;
  uniqueWithinSection?: boolean;
}

export type RowTone = "danger" | "warning" | "success" | "accent" | "muted" | null | undefined;

export interface ListFilterOption { value: string; label: string; color?: string; avatarUrl?: string }

/** The control a list filter shows. */
export type ListFilterType = "options" | "multiOptions" | "contains" | "startsWith" | "dateRange" | "numberRange";

/** A list toolbar filter (by field name). */
export interface ListFilter<F extends string = string> {
  field: F;
  label: string;
  /**
   * The control; omitted, it follows the field's type — an enum or reference: `"multiOptions"`, a
   * date: `"dateRange"`, a number: `"numberRange"`, text: `"contains"`.
   */
  type?: ListFilterType;
  /** Fixed choices; omit for an enum field (labels/colors come from the model). */
  options?: ListFilterOption[] | string[];
  /** Load choices from a catalog's records (its logical name, e.g. `"Employees"`). */
  optionsFrom?: CatalogName;
}

/** How the list's "Group by" picker buckets a date field. */
export type ListDateBucket = "day" | "month" | "year";

/** A grouping a list offers: a field, or a date field with its starting bucket — `"status"`, `{ date: "month" }`. */
export type ListGrouping<E> = TopField<E> | { [F in DateField<E>]?: ListDateBucket };

/** A per-group subtotal, measured like a widget: `{ sum: "total", label: "Total", format: { money: "USD" } }`. */
export type ListAggregate<E> =
  & ({ sum: NumericField<E> } | { avg: NumericField<E> } | { min: NumericField<E> } | { max: NumericField<E> })
  & { label: string; format?: ValueFormat };

/** A list's map view: coordinate fields, and each marker's label (default: the record's name). */
export interface ListMap<E extends EntityName = EntityName> {
  lat: NumericField<E>;
  lng: NumericField<E>;
  label?: (record: RecordOf<E>) => string | null | undefined;
  defaultView?: boolean;
}

/**
 * A list's calendar view: each record is an event from `start` (to `end`, when set). Dragging an
 * event (or resizing it) saves the record's new dates when the viewer may write.
 */
export interface ListCalendar<E extends EntityName = EntityName> {
  start: DateField<E>;
  end?: DateField<E>;
  /** The event's caption (default: the record's name). */
  title?: TopField<E>;
  /** An enumeration or reference field whose colour tints the event. */
  color?: TopField<E>;
  /** The period the calendar opens on (default `"month"`). */
  mode?: "month" | "week" | "day";
  defaultView?: boolean;
}

/**
 * A list's timeline (resource scheduler) view: one lane per value of the reference field `lane`,
 * each record a block from `start` to `end` on the visible day.
 */
export interface ListTimeline<E extends EntityName = EntityName> {
  start: DateField<E>;
  end: DateField<E>;
  /** The reference field that assigns a record to a lane. */
  lane?: TopField<E>;
  /** Show every record of this entity as a lane, including empty ones (default: lanes in use). */
  laneEntity?: EntityName;
  /** Heading of the lane column. */
  laneLabel?: string;
  title?: TopField<E>;
  color?: TopField<E>;
  /** Grid slot length in minutes (default 30). */
  slotMinutes?: number;
  /** First and last visible hour (default 8–20). */
  startHour?: number;
  endHour?: number;
  defaultView?: boolean;
}

/** Declarative list defaults, all by field name. */
export interface ListCustomization<E extends EntityName = EntityName> {
  columns?: TopField<E>[];
  /** List-only header labels. */
  labels?: Partial<Record<TopField<E>, string>>;
  /** Which records the list shows, always: `{ open: true }`. */
  where?: Where<E>;
  groupBy?: TopField<E>;
  /** `-` prefix for descending. */
  sort?: TopField<E> | `-${TopField<E>}`;
  title?: string;
  /** What the "Group by" picker offers: `["status", { date: "month" }]`. */
  groupable?: ListGrouping<E>[];
  /** Per-group subtotals. */
  aggregates?: ListAggregate<E>[];
  filters?: ListFilter<TopField<E>>[];
  /** A Table ⇄ Map toggle plotting each record. */
  map?: ListMap<E>;
  /** A Table ⇄ Calendar toggle placing each record by its dates. */
  calendar?: ListCalendar<E>;
  /** A Table ⇄ Timeline toggle: records as blocks in lanes (rooms, staff, machines). */
  timeline?: ListTimeline<E>;
  /** A Table ⇄ custom view toggle: your component renders the same rows. */
  custom?: { component: ComponentType<ListRendererProps>; label?: string; defaultView?: boolean };
  /** A component shown in the toolbar while rows are selected. */
  selection?: ComponentType<ListSelectionProps>;
  selectionCheckboxes?: boolean;
  /** Open every group band expanded instead of collapsed when the list is grouped. */
  groupsExpanded?: boolean;
}

/**
 * Where a command is offered: `"primary"` a prominent record-header button (for a collection
 * command a prominent toolbar button); `"menu"` the record header's overflow menu and the list
 * row's context menu (the default for record commands); `"row"` an inline icon button on each list
 * row, plus the menus; `"hidden"` not offered (still runnable through `useCommand`).
 */
export type CommandPlacement = "primary" | "menu" | "row" | "hidden";

/** How one command (a server `Commands` command or a built-in) is offered. */
export interface CommandPresentation<E extends EntityName = EntityName> {
  placement?: CommandPlacement;
  /** A lucide icon, or one per record (`(order) => order.paused ? Play : Pause`). */
  icon?: IconComponent | ((record: RecordOf<E>) => LucideIcon | null | undefined);
  /** Replaces the server's title. */
  title?: string;
}

/** Record-form defaults, by field name. */
export interface FormCustomization<E extends EntityName = EntityName> {
  /** Show only these header fields, in order (tabular sections render below them). */
  fields?: FormField<E>[];
}

/**
 * Per-entity tweaks of the model's default surfaces — one per entity, usually its own file
 * (`entities/Orders.ts`: `export const orders = { … } satisfies EntityCustomization<"Orders">`),
 * applied wherever the entity shows: its default list and form, embedded lists, custom pages.
 * Which fields show is an explicit array (`list.columns`, `form.fields`); `fields` only says how
 * each looks, so its key order means nothing.
 */
export interface EntityCustomization<E extends EntityName = EntityName> {
  list?: ListCustomization<E>;
  form?: FormCustomization<E>;
  /** How each field presents, keyed by field name (`"section.field"` for line columns). */
  fields?: { [F in FieldOf<E>]?: FieldCustomization<E, F> };
  rowStyle?: (row: RecordOf<E>) => RowTone;
  /**
   * How each command is offered, by key: the entity's server commands, the built-ins (`post`,
   * `unpost`, `duplicate`, `delete`, `openInList`) and extension modules' record actions and
   * selection downloads (`print-invoice`). A placement alone is shorthand:
   * `{ advance: { placement: "row", icon: ArrowRight }, requestApproval: "primary" }`.
   */
  commands?: Record<string, CommandPresentation<E> | CommandPlacement>;
  /** Components shown under a saved record's form; each receives the record. */
  recordPanels?: RecordPanel<E>[];
  /** The entity's icon in navigation and on the home screen (default: one per model kind). */
  icon?: LucideIcon;
  /** Show the record's comments thread under its form (needs the comments module; default off). */
  comments?: boolean;
}

// ---- typed data questions --------------------------------------------------------------------------

type ValueAt<E, F> = F extends keyof RecordOf<E> ? NonNullable<RecordOf<E>[F]> : unknown;

/** The entity's numeric fields (what `sum`/`avg`/`min`/`max` accept). */
export type NumericField<E> = [EntryOf<E>] extends [never]
  ? string
  : { [F in TopField<E>]: ValueAt<E, F> extends number ? F : never }[TopField<E>];

/** The entity's date/time fields (what a date grouping or a calendar accepts). */
export type DateField<E> = [EntryOf<E>] extends [never] ? string : EntryOf<E> extends { dateField: infer D } ? D & string : string;

/** A comparison on one field: a value (equality), `null` (no value), or an operator object. */
export type FieldPredicate<T> =
  | T
  | null
  | { eq?: T | null; not?: T | null; in?: T[]; notIn?: T[]; gt?: T; gte?: T; lt?: T; lte?: T; contains?: string; startsWith?: string };

/**
 * Which records, as a typed value: `{ posted: true, total: { gte: 100 }, status: { in: [A, B] } }`.
 * Field names and value types come from the model; enum values are the generated constants.
 */
export type Where<E> = { [F in TopField<E>]?: FieldPredicate<ValueAt<E, F>> } & {
  /** The record's id: `{ id: { in: ids } }`. */
  id?: FieldPredicate<string>;
  and?: Where<E>[];
  or?: Where<E>[];
  not?: Where<E>;
};

/**
 * Which register movements: the dimensions and resources (a {@link Where} over the register), plus
 * `period`, `recorder` (the posting document's id) and `movementType`.
 */
export type MovementWhere<E> = { [F in TopField<E>]?: FieldPredicate<ValueAt<E, F>> } & {
  period?: FieldPredicate<string>;
  recorder?: FieldPredicate<string>;
  movementType?: FieldPredicate<"RECEIPT" | "EXPENSE">;
  and?: MovementWhere<E>[];
  or?: MovementWhere<E>[];
  not?: MovementWhere<E>;
};

type InfoEntry<N> = N extends keyof OnnoInformationRegisters ? OnnoInformationRegisters[N] : never;
/** An information register's logical name, checked against the model when generated. */
export type InformationRegisterName = [keyof OnnoInformationRegisters] extends [never]
  ? string
  : Extract<keyof OnnoInformationRegisters, string>;
/** An information register's row type. */
export type InformationRecordOf<N> = [InfoEntry<N>] extends [never]
  ? EntityRecord
  : InfoEntry<N> extends { record: infer R } ? R : EntityRecord;
type InfoField<N> = [InfoEntry<N>] extends [never] ? string : InfoEntry<N> extends { field: infer F } ? F & string : string;
type InfoValueAt<N, F> = F extends keyof InformationRecordOf<N> ? NonNullable<InformationRecordOf<N>[F]> : unknown;
/** Which information-register rows: its dimensions, resources and attributes, `id` and `period`. */
export type InformationWhere<N> = { [F in InfoField<N>]?: FieldPredicate<InfoValueAt<N, F>> } & {
  id?: FieldPredicate<string>;
  period?: FieldPredicate<string>;
  and?: InformationWhere<N>[];
  or?: InformationWhere<N>[];
  not?: InformationWhere<N>;
};

/** A `@Constant`'s name, checked against the model when generated. */
export type ConstantName = [keyof OnnoConstants] extends [never] ? string : Extract<keyof OnnoConstants, string>;
/** A `@Constant`'s value type. */
export type ConstantValue<N> = N extends keyof OnnoConstants
  ? OnnoConstants[N] extends { value: infer V } ? V : unknown
  : unknown;

/** The rows a measure counts: an entity's records, or a register's movements. */
export type MeasureWhere<E> = EntryOf<E> extends { kind: "registers" } ? MovementWhere<E> : Where<E>;

/**
 * A number over an entity's records (a register's: over its movements, a resource summed signed —
 * receipts add, expenses subtract): build with `count`, `sum`, `avg`, `min`, `max`.
 */
export type Measure<E extends EntityName = EntityName> = {
  entity: E;
  aggregation: "count" | "sum" | "avg" | "min" | "max";
  field?: string;
  where?: MeasureWhere<E>;
  /** The date field the page's time range windows by (default: the entity's date / period). */
  dateField?: DateField<E>;
};

export type DateBucket = "minute" | "hour" | "day" | "week" | "month";

/** Group by a field, or by a date field in buckets: `"status"`, `{ date: "week" }`. */
export type By<E> = TopField<E> | { [F in DateField<E>]?: DateBucket };

// ---- widget components ------------------------------------------------------------------------

/** Replace keys of `P` with typed versions from `T` (keys `T` doesn't name stay as they are). */
type Retyped<P, T> = Omit<P, keyof T> & T;

/** Typed per entity: measures, groupings, `where` and render functions over the entity's records. */
export type StatProps<E extends EntityName = EntityName> = Retyped<StatImpl, {
  measure: Measure<E>;
  ratioTo?: Measure<any>;
  sparkline?: boolean | By<E>;
}>;
export type SparklineProps<E extends EntityName = EntityName> = Retyped<SparklineImpl, { measure: Measure<E>; by?: By<E> }>;
export type GaugeProps<E extends EntityName = EntityName> = Retyped<GaugeImpl, { measure: Measure<E>; target?: number | Measure<any> }>;
/** One series of a chart; each may measure a different entity. */
export type ChartSeries<E extends EntityName = EntityName> = Retyped<ChartSeriesImpl, { measure: Measure<E> }>;
export type ChartProps<E extends EntityName = EntityName> = Retyped<ChartImpl, {
  measure?: Measure<E>;
  series?: ChartSeries<any>[];
  by?: By<E>;
  splitBy?: TopField<E>;
}>;
type RecordRender<E, R> = (record: RecordOf<E>) => R;
export type RecentRecordsProps<E extends EntityName = EntityName> = Retyped<RecentRecordsImpl, {
  entity: E;
  where?: Where<E>;
  dateField?: DateField<E>;
  primary?: RecordRender<E, import("react").ReactNode>;
  secondary?: RecordRender<E, import("react").ReactNode>;
  amount?: RecordRender<E, number | null | undefined>;
  currency?: string | RecordRender<E, string | null | undefined>;
}>;
/** The fields a board can group by: enumerations, references, and a document's `posted`. */
export type GroupableField<E> = [EntryOf<E>] extends [never]
  ? string
  : Extract<TopField<E>, "posted"> | {
      [F in TopField<E>]: `${F}Ref` extends keyof RecordOf<E> ? F : `${F}Color` extends keyof RecordOf<E> ? F : never;
    }[TopField<E>];
export type KanbanProps<E extends EntityName = EntityName> = Retyped<KanbanImpl, {
  entity: E;
  where?: Where<E>;
  by?: GroupableField<E>;
  dateField?: DateField<E>;
  caption?: RecordRender<E, import("react").ReactNode>;
  primary?: RecordRender<E, import("react").ReactNode>;
  secondary?: RecordRender<E, import("react").ReactNode>;
  amount?: RecordRender<E, number | null | undefined>;
  currency?: string | RecordRender<E, string | null | undefined>;
}>;
export type CalendarProps<E extends EntityName = EntityName> = Retyped<CalendarImpl, {
  entity: E;
  where?: Where<E>;
  start?: DateField<E>;
  end?: DateField<E>;
  duration?: NumericField<E>;
  itemTitle?: RecordRender<E, string | null | undefined>;
  subtitle?: RecordRender<E, string | null | undefined>;
  color?: RecordRender<E, string | null | undefined>;
  amount?: RecordRender<E, number | null | undefined>;
  currency?: string | RecordRender<E, string | null | undefined>;
}>;
export type EntityMapProps<E extends EntityName = EntityName> = Retyped<EntityMapImpl, {
  entity: E;
  where?: Where<E>;
  lat?: NumericField<E>;
  lng?: NumericField<E>;
  geoJson?: TopField<E>;
  label?: RecordRender<E, string | null | undefined>;
}>;
export type { ChartAxis, ChartThreshold, ChartType } from "./components/widgets/chart";
/** An editor for one `@Constant`, by its name. */
export type SettingProps = Retyped<SettingImpl, { constant: ConstantName }>;
export type { TimeRangeProps, TaskInboxProps, UserAccountsProps, ChangePasswordProps };

// ---- defineApp ---------------------------------------------------------------------------------

/** A page's route: `"/ops"`, `"/"` for home. */
export type PagePath = `/${string}`;

/** Shared registration options; presentation lives in the page component. */
export interface PageOptions {
  label?: string;
  icon?: LucideIcon;
  roles?: RoleName[];
  when?: (model: AppModel) => boolean | Promise<boolean>;
  view?: boolean;
}

/** The descriptor injected into an entity list page. */
export type ListPageProps<E extends EntityName = EntityName> = {
  list: ListDescriptor & { readonly __entity?: E };
};

/** The live form shared by standard controls and custom React components. */
export type RecordFormState<E extends EntityName = EntityName> = {
  readonly __entity?: E;
  id: string | null;
  kind: "catalogs" | "documents";
  name: string;
  title: string;
  meta: import("./components/entities/record-form").FormDescriptor["meta"];
  canPost: boolean;
  posted: boolean;
  dirty: boolean;
  stale: boolean;
  actions: RecordAction[];
  cancel: () => void;
  /** Background refresh preserves unsaved edits and sets `stale` when the record changed. */
  refresh: () => Promise<void>;
  /** Explicit reload discards the draft and adopts the stored record. */
  reload: () => Promise<void>;
  values: Partial<RecordOf<E>>;
  errors: Partial<Record<FieldOf<E>, string>>;
  formErrors: string[];
  feedback: import("./lib/api").FormFeedback[];
  isNew: boolean;
  readOnly: boolean;
  saving: boolean;
  set: <F extends TopField<E> & keyof RecordOf<E>>(field: F, value: RecordOf<E>[F]) => void;
  save: (post?: boolean) => Promise<void>;
  defaultLayout: ReactNode;
};
/** Binding for an ordinary React editor, including metadata and the default field fallback. */
export type RecordFieldBinding<E extends EntityName, F extends TopField<E>> = {
  name: F;
  label: string;
  value: F extends keyof RecordOf<E> ? RecordOf<E>[F] | undefined : unknown;
  onChange: (value: F extends keyof RecordOf<E> ? RecordOf<E>[F] : unknown) => void;
  disabled: boolean;
  restricted: boolean;
  required: boolean;
  error?: string;
  feedback: import("./lib/api").FormFeedback[];
  attribute: import("./lib/types").AttributeMeta | null;
  section: import("./lib/types").TabularSectionMeta | null;
  /** Resolved picker context and cascading constraints, also consumed by the standard picker. */
  reference: import("./components/fields/ref-select").RefSelectProps | null;
  renderDefault: () => ReactNode;
};
export type RecordFieldProps<E extends EntityName, F extends TopField<E>> = {
  form: RecordFormState<E>; field: F; options?: FieldCustomization<E, F>;
  children?: (binding: RecordFieldBinding<E, F>) => ReactNode;
};
/** Resolved action metadata shared by a custom header and the built-in action cluster. */
export type RecordAction = Omit<import("./components/actions/record-actions").RecordAction, "icon"> & { icon?: LucideIcon };

export type RecordPageProps<E extends EntityName = EntityName> = { form: RecordFormState<E> };
export type RecordPageLayoutProps<E extends EntityName = EntityName> = RecordPageProps<E> & { children?: ReactNode; className?: string; commands?: EntityCustomization<E>["commands"]; panels?: RecordPanel<E>[]; comments?: boolean;
  /** The record's change history under the form (default: when the entity records history). */
  history?: boolean };
export type RecordFieldsProps<E extends EntityName = EntityName> = RecordPageProps<E> & {
  /** Which header fields show, in order (default: the entity's `form.fields`, else all visible). */
  fields?: NoInfer<FormField<E>>[];
  /** Restyle fields on this page only, on top of the entity's `fields` customization. */
  overrides?: { [F in NoInfer<TopField<E>>]?: FieldCustomization<E, F> };
};
export type SectionOf<E> = [EntryOf<E>] extends [never] ? string : FieldOf<E> extends infer F ? F extends `${infer S}.${string}` ? S : never : never;
export type SectionField<E, S extends string> = [EntryOf<E>] extends [never] ? string : FieldOf<E> extends infer F ? F extends `${S}.${infer C}` ? C : never : never;
export type RecordLinesProps<E extends EntityName = EntityName, S extends SectionOf<E> = SectionOf<E>> = RecordPageProps<E> & {
  section: S;
  /** Restyle this section's columns on this page only, keyed by column. */
  overrides?: { [F in SectionField<E, NoInfer<S>>]?: FieldCustomization<E> };
};

/** Explicit route or a model-resolved entity list/record screen. */
export type PageDefinition<P extends PagePath = PagePath> = PageOptions & (
  | { path: P; list?: never; record?: never; component: ComponentType<import("./lib/routes").RouteProps> }
  | { [E in CatalogName | DocumentName]: { list: E; view?: true; path?: never; record?: never; component: ComponentType<ListPageProps<E>> } }[CatalogName | DocumentName]
  | { [E in CatalogName | DocumentName]: { record: E; view?: true; path?: never; list?: never; component: ComponentType<RecordPageProps<E>> } }[CatalogName | DocumentName]
);

/**
 * A nav entry: an entity by name (`"Orders"`), one of the app's pages by path (`"/ops"`), or either
 * with overrides. `P` is the app's page paths, so a path that names no page does not type-check.
 */
export type NavItemSpec<P extends PagePath = PagePath> =
  | NavGroupSpec<P>
  | EntityName
  | P
  | { entity: EntityName; label?: string; icon?: LucideIcon; roles?: RoleName[] }
  | { path: P; label?: string; icon?: LucideIcon; roles?: RoleName[] };

export interface NavGroupSpec<P extends PagePath = PagePath> { title: string; icon?: LucideIcon; actions?: ReactNode; roles?: RoleName[]; items: NavItemSpec<P>[] }
export interface NavSectionSpec<P extends PagePath = PagePath> { title?: string | null; icon?: LucideIcon; actions?: ReactNode; roles?: RoleName[]; items: NavItemSpec<P>[] }
export type NavigationProps = Omit<import("./components/shell/navigation").NavigationProps, "navigation"> & { navigation: NavSection[] };
export type NavigationGroupProps = { title: string; icon?: LucideIcon; actions?: ReactNode; children: ReactNode; defaultOpen?: boolean };
export type NavigationLinkProps = ({ entity: EntityName; path?: never } | { path: PagePath; entity?: never }) & {
  label?: string; icon?: LucideIcon; roles?: RoleName[]; className?: string;
};

/**
 * The whole app UI as one declaration; every part is optional. `P` is inferred from `pages`, so
 * the navigation may only link pages the app declares.
 */
export interface AppDefinition<P extends PagePath = PagePath> {
  /** Optional React replacement for the desktop navigation and mobile menu body. */
  navigationComponent?: ComponentType<NavigationProps>;
  /** How each entity's default list and form differ from the framework's, by entity name. */
  entities?: { [E in EntityName]?: EntityCustomization<E> };
  pages?: PageDefinition<P>[];
  /**
   * The sidebar. Omitted: entities grouped by context plus every labelled page. Items the user
   * cannot read or use are dropped, as are empty sections.
   */
  navigation?: NavSectionSpec<NoInfer<P>>[] | ((model: AppModel, defaults: NavSection[]) => NavSection[]);
  /**
   * Components contributed to the UI's named outlets (`ExtensionSlot`s): a record form's actions,
   * a list's context menu, the CRM chat header, … Each declares its slot and, optionally, when it
   * applies.
   */
  extensions?: UiExtension[];
  /**
   * The app's own login page, replacing the framework's at `/login`: a one-time-code form, a
   * single "Sign in with Keycloak" button, a kiosk badge scan, … Compose it from `LoginLayout`,
   * `SignInMethods`, `PasswordLogin`, `SsoButtons` and `LoginButton`, and sign in with `useSignIn()`.
   * It renders before sign-in, so it has no business model and must not use data hooks.
   */
  login?: ComponentType<LoginPageProps>;
}

/** The saved record a record panel renders under. */
export interface RecordContext<E extends EntityName = EntityName> {
  kind: "catalogs" | "documents";
  name: string;
  id: string;
  /** The record's current values (logical keys). */
  data: RecordOf<E>;
  /** The viewer cannot edit this record. */
  readOnly: boolean;
}

/** A component shown under a saved record's form. */
export interface RecordPanel<E extends EntityName = EntityName> {
  component: ComponentType<{ record: RecordContext<E>; title?: string }>;
  title?: string;
  /** The panel's share of the form row (default `"full"`). */
  width?: FieldWidth;
  hint?: string;
}

/** A TanStack `useQuery` result over entity data, plus the resolved model entity. */
export type EntityQuery<T> = UseQueryResult<T> & { entity: ModelEntity | null };

/** A TanStack `useMutation` result over an entity write. */
export type EntityMutation<V, R> = UseMutationResult<R, Error, V> & { entity: ModelEntity | null };

export type AggregateBuckets = { buckets: { key?: unknown; label?: string; value: number; series?: unknown; seriesLabel?: string }[]; truncated: boolean };
