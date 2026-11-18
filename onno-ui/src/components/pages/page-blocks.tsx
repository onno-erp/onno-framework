import { useEffect, useMemo, useState, type ReactNode } from "react";
import { EntityListView, type ListDescriptor, type ListStateProps, type ListView } from "../lists/entity-list";
import { findEntity, useModel } from "../../lib/model-store";
import { viewEndpoint } from "../../views/content-pane";
import { PageHeader, RouteView } from "../../views/route-view";
import { Boundary } from "./boundary";
import type { ViewModel } from "../../lib/view-types";
import { cn } from "../../lib/utils";
import { columnFor, fieldFor, type EntityCustomization, type ListCustomization } from "../../lib/entity-customizations";
import { useCustomizedList } from "../../lib/app-config";
import type { WhereClause } from "../../lib/measure";
import { PageGutterContext } from "../../lib/page-gutter";

/**
 * The page-level building blocks an app composes its own pages from. Every
 * component defaults from the business model — an `EntityList` needs only the entity — and takes
 * props only for what differs.
 */

function useViewport(): "mobile" | "desktop" {
  const get = () => (window.innerWidth < 600 ? "mobile" : "desktop");
  const [vp, setVp] = useState<"mobile" | "desktop">(get);
  useEffect(() => {
    const on = () => setVp(get());
    window.addEventListener("resize", on);
    return () => window.removeEventListener("resize", on);
  }, []);
  return vp;
}

/** Fetch the server's default view for a route (`/documents/orders`, `/catalogs/books/<id>`, …). */
export function useDefaultView(path: string | null): { view: ViewModel | null; error: number | null } {
  const [state, setState] = useState<{ path: string; view: ViewModel | null; error: number | null }>({
    path: "",
    view: null,
    error: null,
  });
  useEffect(() => {
    if (!path) return;
    let cancelled = false;
    fetch(viewEndpoint(path), { credentials: "include" })
      .then((r) => (r.ok ? r.json() : Promise.reject(r.status)))
      .then((view: ViewModel) => {
        if (!cancelled) setState({ path, view, error: null });
      })
      .catch((status: unknown) => {
        if (!cancelled) setState({ path, view: null, error: typeof status === "number" ? status : 500 });
      });
    return () => {
      cancelled = true;
    };
  }, [path]);
  return state.path === path ? { view: state.view, error: state.error } : { view: null, error: null };
}

/** A page: the standard content gutter and an optional header. */
export function Page({ title, subtitle, actions, children, className }: {
  title?: string;
  subtitle?: string;
  /** Rendered on the header's right edge (buttons, a time-range picker, …). */
  actions?: ReactNode;
  children?: ReactNode;
  className?: string;
}) {
  return (
    <div className={cn("flex w-full flex-col gap-2 p-4", className)}>
      {title ? <PageHeader title={title} subtitle={subtitle} actions={actions} /> : null}
      <PageGutterContext.Provider value={true}>{children}</PageGutterContext.Provider>
    </div>
  );
}

/**
 * A column's width in a {@link Row}: a share of the row's free space (a flex weight — `[2, 1]` is
 * two thirds and one third) or a fixed width in pixels.
 */
export type RowWidth = number | { px: number };

/** Columns side by side on desktop (stacked on phones). Without `widths` the columns share equally. */
export function Row({ children, widths, className }: { children: ReactNode; widths?: RowWidth[]; className?: string }) {
  const vp = useViewport();
  const items = Array.isArray(children) ? children : [children];
  if (vp === "mobile") return <div className={cn("flex w-full flex-col gap-2", className)}>{items}</div>;
  return (
    <div className={cn("flex w-full gap-3", className)}>
      {items.map((child, i) => {
        const w = widths?.[i] ?? 1;
        const style = typeof w === "number" ? { flex: `${w} 1 0` } : { width: w.px, flexShrink: 0 };
        return (
          <div key={i} className="flex min-w-0 flex-col gap-2" style={style}>
            {child}
          </div>
        );
      })}
    </div>
  );
}

/** The framework's default view of a route — the same thing a route renders with no override. */
export function DefaultView({ path }: { path: string }) {
  const { view, error } = useDefaultView(path);
  if (error) return <p className="p-4 text-sm text-muted-foreground">Not available ({error}).</p>;
  return view ? <RouteView view={view} /> : null;
}

export type EntityListProps = Omit<ListCustomization, "selection"> & Pick<EntityCustomization, "commands" | "rowStyle"> & {
  list?: ListDescriptor | null;
  /** Restyle fields in this list only, on top of the entity's `fields` customization. */
  overrides?: EntityCustomization["fields"];
  selectionToolbar?: ListCustomization["selection"];
  /** The catalog/document, by logical name (`"Orders"`). */
  entity?: string;
  /**
   * How this list presents, on top of what the app declared for the entity (`defineApp`'s
   * `entities`). The props below are shorthands for its `list` options.
   */
  customization?: EntityCustomization;
  /** Show only these fields, in this order. */
  columns?: string[];
  /** Relabel columns by field name. */
  labels?: Record<string, string>;
  /** Which records the list shows, always: `{ open: true }` (the viewer's filters apply on top). */
  where?: WhereClause;
  title?: string;
  /** Grow to fill the page height and scroll internally. */
  fill?: boolean;

  /** Where the list starts; the user changes it from there. */
  defaultView?: ListView;
  defaultGroupBy?: string;
  /** Field name, `-` prefix for descending (`"-date"`). */
  defaultSort?: string;

  /** Controlled state: pass a value and its `on…Change` to own it (like `<input value onChange>`). */
  view?: ListView;
  onViewChange?: (view: ListView) => void;
  /** The grouping field (`""` = flat). */
  groupBy?: string;
  onGroupByChange?: (field: string) => void;
  sort?: string;
  onSortChange?: (sort: string) => void;
  search?: string;
  onSearchChange?: (search: string) => void;
  /** Selected record ids. */
  selection?: string[];
  onSelectionChange?: (ids: string[]) => void;
};

const sortString = (s: { column: string | null; descending: boolean }, list: ListDescriptor) =>
  s.column ? (s.descending ? "-" : "") + fieldFor(list, s.column) : "";

/**
 * The full interactive list of a catalog/document — search, filters, grouping, selection, actions,
 * live updates — defaulted from the model and the app's customization of the entity. Pass props
 * only for what differs.
 */
export function EntityList(props: EntityListProps) {
  const { entity, customization, columns, labels, where, title, fill, defaultView, defaultGroupBy, defaultSort } = props;
  const model = useModel();
  const route = useMemo(() => findEntity(model, entity ?? "")?.route ?? null, [model, entity]);
  const { view: defaults, error } = useDefaultView(props.list ? null : route);
  const base = props.list ?? (defaults && "list" in defaults ? (defaults.list as unknown as ListDescriptor) : null);

  // The shorthand props are one more customization, applied after the app's (`where` keyed by
  // content: pages pass a fresh object literal each render).
  const whereKey = where ? JSON.stringify(where) : "";
  const own = useMemo<EntityCustomization>(() => {
    const listOptions: ListCustomization = {
      columns, labels, where, title,
      sort: props.onSortChange ? undefined : props.sort,
      groupBy: props.onGroupByChange ? undefined : props.groupBy,
      groupable: props.groupable,
      aggregates: props.aggregates,
      filters: props.filters,
      map: props.map,
      calendar: props.calendar,
      timeline: props.timeline,
      custom: props.custom,
      selection: props.selectionToolbar,
      selectionCheckboxes: props.selectionCheckboxes,
      groupsExpanded: props.groupsExpanded,
    };
    return {
      ...customization, fields: props.overrides ?? customization?.fields, commands: props.commands ?? customization?.commands, rowStyle: props.rowStyle ?? customization?.rowStyle,
      list: { ...customization?.list, ...Object.fromEntries(Object.entries(listOptions).filter(([, v]) => v !== undefined)) },
    };
  }, [customization, columns, labels, whereKey, title, props.overrides, props.commands, props.rowStyle, props.sort, props.groupBy, props.groupable, props.aggregates, props.filters, props.map, props.calendar, props.timeline, props.custom, props.selectionCheckboxes, props.groupsExpanded, props.selectionToolbar, props.onSortChange, props.onGroupByChange]);
  const customized = useCustomizedList(base, own);

  const list = useMemo(() => {
    if (!customized) return null;
    const d: ListDescriptor = { ...customized, embedded: props.list ? customized.embedded : true };
    if (fill) d.fill = true;
    if (defaultGroupBy) d.defaultGroupBy = columnFor(d, defaultGroupBy);
    if (defaultSort) d.sort = { column: columnFor(d, defaultSort.replace(/^-/, "")), descending: defaultSort.startsWith("-") };
    if (defaultView === "custom" && d.custom) d.custom = { ...d.custom, defaultView: true };
    if (defaultView === "map" && d.map) d.map = { ...d.map, defaultView: true };
    if (defaultView === "table") {
      if (d.custom) d.custom = { ...d.custom, defaultView: false };
      if (d.map) d.map = { ...d.map, defaultView: false };
    }
    return d;
  }, [customized, fill, defaultGroupBy, defaultSort, defaultView, props.list]);

  // Controlled state, translated between field names (the app's) and data columns (the list's).
  const state = useMemo<ListStateProps | undefined>(() => {
    if (!list) return undefined;
    const p = props;
    return {
      view: p.view,
      onViewChange: p.onViewChange,
      groupBy: !p.onGroupByChange || p.groupBy === undefined ? undefined : p.groupBy && columnFor(list, p.groupBy),
      onGroupByChange: p.onGroupByChange && ((column) => p.onGroupByChange!(column && fieldFor(list, column))),
      sort: !p.onSortChange || p.sort === undefined ? undefined : p.sort
        ? { column: columnFor(list, p.sort.replace(/^-/, "")), descending: p.sort.startsWith("-") }
        : { column: null, descending: false },
      onSortChange: p.onSortChange && ((s) => p.onSortChange!(sortString(s, list))),
      search: p.search,
      onSearchChange: p.onSearchChange,
      selection: p.selection,
      onSelectionChange: p.onSelectionChange,
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [list, props.view, props.onViewChange, props.groupBy, props.onGroupByChange, props.sort, props.onSortChange,
      props.search, props.onSearchChange, props.selection, props.onSelectionChange]);

  if (error) return <p className="text-sm text-muted-foreground">{entity}: not available ({error}).</p>;
  if (!list) return null;
  return <Boundary label={list.title || list.name}><EntityListView list={list} state={state} /></Boundary>;
}
