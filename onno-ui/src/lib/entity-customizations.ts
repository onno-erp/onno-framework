import type { IconSource } from "./icons";
import type { ComponentType, ReactNode } from "react";
import type { ListDescriptor, ListRendererProps } from "../components/lists/entity-list";
import type { FormDescriptor } from "../components/entities/record-form";
import type { AttributeMeta, EntityRecord } from "./types";
import type { WhereClause } from "./measure";
import type { FieldInput, FieldWidth, ValueFormat } from "./cell-format";
import {
  mergePresentations,
  type CommandCustomization,
  type CommandPresentation,
} from "./commands";

/**
 * Per-entity tweaks of the model's default surfaces, written in React. Everything is optional; an
 * entity with no customization renders its defaults. Pure functions: a customization is plain data
 * handed in by the caller (the app's `defineApp({ entities })` through context, or an `EntityList`
 * prop), applied to the server's descriptor.
 *
 *   const orders: EntityCustomization = {
 *     list: { columns: ["number", "date", "customer", "total"], groupBy: "status" },
 *     form: { fields: ["customer", "date", "status", "note"] },
 *     fields: { note: { input: "textarea" }, total: { format: { money: "USD" } } },
 *     rowStyle: (row) => (row.status === "CANCELLED" ? "muted" : null),
 *     commands: { advance: { placement: "row", icon: ArrowRight }, cancel: { icon: Ban } },
 *   };
 *
 * Which fields show, and in what order, is always an explicit array (`list.columns`,
 * `form.fields`); `fields` only says how each one looks, so its key order means nothing.
 * The server still decides what exists and what the user may do; customizations only change
 * presentation (and which predicate a ref picker asks the server for).
 * `public-types.ts` declares the same shapes typed per entity from the generated model.
 */
export type FieldCustomization = {
  /**
   * A command group (`group("Change status")` on the server) offered by right-clicking this field's
   * list cell — the cell becomes a shortcut to just those commands.
   */
  commandGroup?: string;
  label?: string;
  hidden?: boolean;
  /** Hide in the list only / in the form only. */
  hideInList?: boolean;
  hideInForm?: boolean;
  group?: string;
  /** The field's share of the form row (default `"full"`); two `"half"` fields pair up. */
  width?: FieldWidth;
  /** The control the field edits with (and, for media/colors, how its list cell shows). */
  input?: FieldInput;
  /** How the value displays in lists and group subtotals. */
  format?: ValueFormat;
  /** The list cell, drawn by the app: wins over `format` and `input`. */
  render?: (value: unknown, record: EntityRecord) => ReactNode;
  placeholder?: string;
  hint?: string;
  /** Ref picker: secondary column shown under each option. */
  refSecondary?: string;
  /**
   * Picker cascade: the picker offers only records whose field equals one of this form's fields,
   * `{ supplier: "supplier" }` (target field → this form's field; a row column reads its row first).
   * Changing the form field clears the pick.
   */
  filterBy?: Record<string, string>;
  openButton?: boolean;
  clearButton?: boolean;
  /** Tabular-section column: values must be unique across rows. */
  uniqueWithinSection?: boolean;
};

export type RowTone = "danger" | "warning" | "success" | "accent" | "muted" | null | undefined;

export type EntityCustomization = {
  /** List defaults: the same options as `<EntityList>`. */
  list?: ListCustomization;
  /** Record-form defaults: which header fields the form shows, in order. */
  form?: FormCustomization;
  /**
   * How each field presents, keyed by field name (`"section.field"` for tabular-section columns).
   * Presentation only: listing a field here neither shows it nor orders it.
   */
  fields?: Record<string, FieldCustomization>;
  /** Conditional row tint, evaluated per list row. */
  rowStyle?: (row: EntityRecord) => RowTone;
  /**
   * How each command is offered, by key: the entity's server commands and the built-ins (`post`,
   * `unpost`, `duplicate`, `delete`, `openInList`). A placement alone is shorthand for
   * `{ placement }`; see {@link CommandPresentation}.
   */
  commands?: Record<string, CommandCustomization>;
  /** Components shown under a saved record's form; each receives the record. */
  recordPanels?: RecordPanel[];
  /** The entity's icon in navigation and on the home screen (an imported Lucide component; default by kind). */
  icon?: IconSource;
  /** Show the record's comments thread under its form (needs the comments module; default off). */
  comments?: boolean;
};

export type FormCustomization = {
  /** Show only these header fields, in order (tabular sections render below them). */
  fields?: string[];
};

/** How the list's "Group by" picker buckets a date field. */
export type ListDateBucket = "day" | "month" | "year";

/** A grouping the list offers: a field, or a date field with its starting bucket (`{ date: "month" }`). */
export type ListGrouping = string | Record<string, ListDateBucket>;

/** A per-group subtotal: one of `sum`/`avg`/`min`/`max` naming the numeric field, plus its label. */
export type ListAggregate =
  & ({ sum: string } | { avg: string } | { min: string } | { max: string })
  & { label: string; format?: ValueFormat };

export type ListCustomization = {
  /** Show only these fields, in order. */
  columns?: string[];
  /** List-only header labels by field name (`fields[…].label` relabels list and form). */
  labels?: Record<string, string>;
  /** Which records the list shows, always (the viewer's filters apply on top): `{ open: true }`. */
  where?: WhereClause;
  /** Open grouped by this field. */
  groupBy?: string;
  /** Initial sort; `-` prefix for descending. */
  sort?: string;
  title?: string;
  /** What the "Group by" picker offers: `["status", { date: "month" }]`. */
  groupable?: ListGrouping[];
  /** Per-group subtotals: `{ sum: "total", label: "Total", format: { money: "USD" } }`. */
  aggregates?: ListAggregate[];
  /** Toolbar filters, replacing the defaults. Enum filters keep their server-resolved options. */
  filters?: ListFilter[];
  /** A Table ⇄ Map toggle plotting rows from coordinate fields. */
  map?: ListMap;
  /** A Table ⇄ Calendar toggle placing each record by its date fields. */
  calendar?: {
    start: string; end?: string; title?: string; color?: string;
    mode?: "month" | "week" | "day"; defaultView?: boolean;
  };
  /** A Table ⇄ Timeline toggle: records as blocks in lanes of a reference field. */
  timeline?: {
    start: string; end: string; lane?: string; laneEntity?: string; laneLabel?: string;
    title?: string; color?: string; slotMinutes?: number; startHour?: number; endHour?: number;
    defaultView?: boolean;
  };
  /** A Table ⇄ custom view toggle: your component renders the same searched/filtered/sorted rows. */
  custom?: { component: ComponentType<ListRendererProps>; label?: string; defaultView?: boolean };
  /** A component shown in the toolbar while rows are selected (batch work over `ids`). */
  selection?: ComponentType<{ ids: string[]; complete: () => void }>;
  /** Visible selection checkboxes for batch actions. */
  selectionCheckboxes?: boolean;
  /** Open every group band expanded instead of collapsed when the list is grouped. */
  groupsExpanded?: boolean;
};

/** A list's map view: the coordinate fields, and each marker's label (default: the record's name). */
export type ListMap = {
  lat: string;
  lng: string;
  label?: (record: EntityRecord) => string | null | undefined;
  defaultView?: boolean;
};

/** The saved record a record panel renders under. */
export type RecordContext = {
  kind: "catalogs" | "documents";
  name: string;
  id: string;
  /** The record's current values (logical keys). */
  data: EntityRecord;
  /** The viewer cannot edit this record. */
  readOnly: boolean;
};

export type RecordPanel = {
  component: ComponentType<{ record: RecordContext; title?: string }>;
  title?: string;
  /** The panel's share of the form row (default `"full"`). */
  width?: FieldWidth;
  hint?: string;
};

export type ListFilterOption = { value: string; label: string; color?: string; avatarUrl?: string };

/** The control a list filter shows. */
export type ListFilterType = "options" | "multiOptions" | "contains" | "startsWith" | "dateRange" | "numberRange";

export type ListFilter = {
  field: string;
  label: string;
  /**
   * The control; omitted, it follows the field's type: an enum or reference → `"multiOptions"`, a
   * date → `"dateRange"`, a number → `"numberRange"`, text → `"contains"`.
   */
  type?: ListFilterType;
  /** Fixed choices. Omit for an enum field (its labels/colors come from the model). */
  options?: ListFilterOption[] | string[];
  /**
   * Load the choices from a catalog's records (its logical name, `"Employees"`): value = record id,
   * label = its description, with the record's photo when it has a photo-named field.
   */
  optionsFrom?: string;
};

/** The customizations that apply to an entity, from a map keyed by logical name (or route key). */
export function customizationsFor(
  entities: Record<string, EntityCustomization | undefined> | undefined,
  entity: { kind: string; name: string; logicalName?: string },
): EntityCustomization[] {
  if (!entities) return [];
  const keys = [`${entity.kind}/${entity.name}`, `/${entity.kind}/${entity.name}`, entity.name, entity.logicalName];
  const out: EntityCustomization[] = [];
  for (const k of keys) {
    const c = k ? entities[k] : undefined;
    if (c && !out.includes(c)) out.push(c);
  }
  return out;
}

/** Apply customizations to an entity's default list descriptor. */
export function applyList(list: ListDescriptor, customizations: EntityCustomization[]): ListDescriptor {
  let d = list;
  for (const c of customizations) {
    if (c.list) d = applyListOptions(d, c.list);
    if (c.fields) d = applyListFields(d, c.fields);
    if (c.rowStyle) d = { ...d, rowStyle: c.rowStyle };
    if (c.commands) d = { ...d, commandPresentation: mergePresentations(d.commandPresentation, c.commands) };
  }
  return d;
}

export function applyListOptions(list: ListDescriptor, o: ListCustomization): ListDescriptor {
  const raw = { ...list } as unknown as Record<string, unknown>;
  let columns = list.columns;
  if (o.columns?.length) {
    columns = o.columns
      .map((f) => list.columns.find((c) => c.fieldName === f || c.columnName === f))
      .filter((c): c is ListDescriptor["columns"][number] => !!c);
  }
  if (o.labels) {
    columns = columns.map((c) => (c.fieldName && o.labels![c.fieldName] ? { ...c, label: o.labels![c.fieldName] } : c));
  }
  raw.columns = columns;
  if (o.where && Object.keys(o.where).length) raw.baseWhere = list.baseWhere ? { and: [list.baseWhere, o.where] } : o.where;
  if (o.groupBy) raw.defaultGroupBy = columnFor(list, o.groupBy);
  if (o.sort) raw.sort = { column: columnFor(list, o.sort.replace(/^-/, "")), descending: o.sort.startsWith("-") };
  if (o.title) raw.title = o.title;
  if (o.groupable) {
    raw.groupable = o.groupable.map((g) => {
      const field = typeof g === "string" ? g : Object.keys(g)[0] ?? "";
      return { columnName: columnFor(list, field), label: labelFor(list, field), date: typeof g !== "string" };
    });
    // The list buckets every date grouping alike; it starts on the first one's bucket.
    const bucket = o.groupable.map((g) => (typeof g === "string" ? undefined : Object.values(g)[0])).find(Boolean);
    if (bucket) raw.defaultGroupByDateGranularity = bucket;
  }
  if (o.aggregates) {
    raw.aggregates = o.aggregates.map((a) => {
      const fn = AGGREGATE_FNS.find((f) => f in a)!;
      const field = (a as Record<string, unknown>)[fn] as string;
      return { columnName: columnFor(list, field), fn, label: a.label, format: a.format };
    });
  }
  if (o.filters) {
    const defaults = list.filters ?? [];
    raw.filters = o.filters.map((f) => {
      const fallback = defaults.find((d) => d.key === f.field);
      return {
        key: f.field,
        label: f.label,
        column: columnFor(list, f.field),
        type: f.type ?? fallback?.type ?? inferFilterType(list, f),
        // Choices loaded from a catalog's records by the list itself.
        ...(f.optionsFrom ? { optionsFrom: f.optionsFrom } : {}),
        options: f.optionsFrom
          ? []
          : f.options
            ? f.options.map((opt) => (typeof opt === "string" ? { value: opt, label: opt } : opt))
            : fallback?.options ?? [],
      };
    });
  }
  if (o.map) {
    raw.map = { latField: o.map.lat, lngField: o.map.lng, label: o.map.label, defaultView: o.map.defaultView };
  }
  if (o.calendar) {
    const c = o.calendar;
    raw.calendar = {
      startField: c.start, startColumn: columnFor(list, c.start),
      ...(c.end ? { endField: c.end, endColumn: columnFor(list, c.end) } : {}),
      titleField: c.title, colorField: c.color, mode: c.mode, defaultView: c.defaultView,
    };
  }
  if (o.timeline) {
    const tl = o.timeline;
    raw.timeline = {
      startField: tl.start, startColumn: columnFor(list, tl.start),
      endField: tl.end, endColumn: columnFor(list, tl.end),
      ...(tl.lane ? { laneField: tl.lane, laneColumn: columnFor(list, tl.lane) } : {}),
      laneEntity: tl.laneEntity, laneLabel: tl.laneLabel, titleField: tl.title, colorField: tl.color,
      slotMinutes: tl.slotMinutes, startHour: tl.startHour, endHour: tl.endHour, defaultView: tl.defaultView,
    };
  }
  if (o.custom) raw.custom = { ...o.custom };
  if (o.selection) raw.selectionComponent = o.selection;
  if (o.selectionCheckboxes) raw.selectionCheckboxes = true;
  if (o.groupsExpanded) raw.groupsExpanded = true;
  return raw as unknown as ListDescriptor;
}

const AGGREGATE_FNS = ["sum", "avg", "min", "max"] as const;

/**
 * A filter's control from its field's type: an enum or reference picks among values, a date or a
 * number takes a range, text a substring. Fixed or catalog-loaded choices make it a multi-select.
 */
function inferFilterType(list: ListDescriptor, f: ListFilter): ListFilterType {
  if (f.options || f.optionsFrom) return "multiOptions";
  const type = list.columns.find((c) => c.fieldName === f.field || c.columnName === f.field)?.type;
  switch (type) {
    case "enum":
    case "ref":
    case "boolean":
      return "multiOptions";
    case "date":
      return "dateRange";
    case "number":
      return "numberRange";
    default:
      return "contains";
  }
}

function labelFor(list: ListDescriptor, field: string): string {
  return list.columns.find((c) => c.fieldName === field || c.columnName === field)?.label ?? field;
}

// System fields' storage columns: the list feed sorts and groups by column, apps speak field names.
const SYSTEM_COLUMNS: Record<string, string> = {
  date: "_date", number: "_number", code: "_code", description: "_description", posted: "_posted",
};

/** A field name → the list's data column (a column name passes through unchanged). */
export function columnFor(list: ListDescriptor, field: string): string {
  const col = list.columns.find((c) => c.fieldName === field || c.columnName === field);
  if (col) return col.columnName;
  const group = (list as unknown as { groupable?: { columnName: string; fieldName?: string }[] }).groupable
    ?.find((g) => g.columnName === field || g.fieldName === field);
  // Not on screen: system columns are underscored, attributes follow the snake_case naming strategy.
  return group?.columnName ?? SYSTEM_COLUMNS[field] ?? field.replace(/([a-z0-9])([A-Z])/g, "$1_$2").toLowerCase();
}

/** A list's data column → the field name an app speaks (the inverse of {@link columnFor}). */
export function fieldFor(list: ListDescriptor, column: string): string {
  const col = list.columns.find((c) => c.columnName === column);
  if (col?.fieldName) return col.fieldName;
  const system = Object.entries(SYSTEM_COLUMNS).find(([, c]) => c === column)?.[0];
  return system ?? column.replace(/_([a-z0-9])/g, (_, ch: string) => ch.toUpperCase());
}

function applyListFields(list: ListDescriptor, fields: Record<string, FieldCustomization>): ListDescriptor {
  const columns = list.columns
    .filter((c) => {
      const f = c.fieldName ? fields[c.fieldName] : undefined;
      return !(f?.hidden || f?.hideInList);
    })
    .map((c) => {
      const f = c.fieldName ? fields[c.fieldName] : undefined;
      if (!f) return c;
      return {
        ...c,
        label: f.label ?? c.label,
        input: f.input ?? c.input,
        format: f.format ?? c.format,
        render: f.render ?? c.render,
        hint: f.hint ?? c.hint,
        cellMenu: f.commandGroup ?? c.cellMenu,
      };
    });
  return { ...list, columns };
}

/** Apply customizations' field selection, presentation, command presentation and record panels to an entity's default record form. */
export function applyForm(form: FormDescriptor, customizations: EntityCustomization[]): FormDescriptor {
  let d = form;
  for (const c of customizations) {
    if (c.form?.fields?.length) d = { ...d, fieldOrder: c.form.fields };
    if (c.fields) d = applyFormFields(d, c.fields);
    if (c.commands) d = applyCommandPresentation(d, c.commands);
    if (c.recordPanels?.length) d = { ...d, panels: [...(d.panels ?? []), ...c.recordPanels] };
  }
  return d;
}

function applyAttribute(a: AttributeMeta, f: FieldCustomization | undefined): AttributeMeta {
  if (!f) return a;
  const out: AttributeMeta = { ...a };
  if (f.label !== undefined) out.displayName = f.label;
  if (f.hidden || f.hideInForm) {
    out.visibleInForm = false;
    out.visibleInDetail = false;
  }
  if (f.hidden || f.hideInList) out.visibleInList = false;
  if (f.group !== undefined) out.group = f.group;
  if (f.width !== undefined) out.width = f.width;
  if (f.input !== undefined) out.input = f.input;
  if (f.placeholder !== undefined) out.placeholder = f.placeholder;
  if (f.hint !== undefined) out.hint = f.hint;
  if (f.refSecondary !== undefined) out.refSecondary = f.refSecondary;
  if (f.filterBy !== undefined) out.refFilterBy = Object.keys(f.filterBy).length ? f.filterBy : undefined;
  if (f.openButton !== undefined) out.refOpenButton = f.openButton;
  if (f.clearButton !== undefined) out.refClearButton = f.clearButton && !a.required;
  if (f.uniqueWithinSection !== undefined) out.uniqueWithinSection = f.uniqueWithinSection;
  return out;
}

function applyFormFields(form: FormDescriptor, fields: Record<string, FieldCustomization>): FormDescriptor {
  const meta = form.meta;
  return {
    ...form,
    meta: {
      ...meta,
      attributes: meta.attributes.map((a) => applyAttribute(a, fields[a.fieldName])),
      systemColumns: meta.systemColumns?.map((s) => {
        const f = fields[s.fieldName];
        if (!f) return s;
        return {
          ...s,
          displayName: f.label ?? s.displayName,
          visibleInDetail: f.hidden || f.hideInForm ? false : s.visibleInDetail,
          visibleInList: f.hidden || f.hideInList ? false : s.visibleInList,
        };
      }),
      tabularSections: meta.tabularSections?.map((section) => ({
        ...section,
        label: fields[section.name]?.label ?? section.label,
        attributes: section.attributes.map((a) => applyAttribute(a, fields[`${section.name}.${a.fieldName}`])),
      })),
    },
  };
}

function applyCommandPresentation(form: FormDescriptor, commands: Record<string, CommandCustomization>): FormDescriptor {
  return { ...form, commandPresentation: mergePresentations(form.commandPresentation, commands) };
}
