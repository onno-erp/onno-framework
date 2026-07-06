import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { ArrowDown, ArrowUp, CalendarDays, Check, ChevronLeft, ChevronRight, ChevronsUpDown, Loader2, Map as MapIcon, Plus, PlusCircle, Search, Table2, X } from "lucide-react";
import { CalendarDate, getLocalTimeZone, parseDate, startOfMonth, startOfYear, today } from "@internationalized/date";
import { toast } from "sonner";
import { ListMapView, type ListMapConfig } from "@/components/list-map-view";
import { GroupedList } from "@/components/entity-list-grouped";
import { PresenceAvatars } from "@/components/presence-avatars";
import { useViewersById } from "@/lib/presence-store";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Badge } from "@/components/ui/badge";
import { RangeCalendar } from "@/components/ui/calendar";
import { DatePicker } from "@/components/date-picker";
import { HintIcon } from "@/components/ui/hint-icon";
import { DynamicLucide } from "@/lib/icon-bridge";
import { api } from "@/lib/api";
import { cn, enumPillStyle } from "@/lib/utils";
import { applyFormat, isImageWidget, isAvatarWidget, looksLikeImageUrl } from "@/lib/cell-format";
import type { EntityRecord, UiEvent } from "@/lib/types";
import { useMessages } from "@/providers/messages-provider";
import type { Translate } from "@/lib/messages";

/**
 * The {@code onno-list} React island: a virtualized, server-paged data grid. The DivKit list
 * surface emits only a descriptor (columns, sort, searchability, the open/New routes); this
 * island fetches windows of rows from {@code /api/list/...} as you scroll, sorts and searches
 * on the server, and renders only the visible rows — so a 10k-row entity stays smooth. Rows carry
 * {@code data-onno-row} so the existing right-click Open/Edit/Duplicate menu keeps working.
 */

export type ListColumn = {
  columnName: string;
  label: string;
  width: string;
  /** Display hint: "image"/"avatar" renders the cell value as a thumbnail. */
  widget?: string;
  /** Display format: a date pattern ("dd-MM-yy") or number spec ("currency:EUR", "integer", …). */
  format?: string;
  /** Optional help text; surfaced as a hoverable "?" next to the column header. */
  hint?: string;
};
/**
 * A custom action button declared by an EntityView. {@code scope} is "toolbar" (list-level) or
 * "row" (per-record); a {@code server} action POSTs to /api/actions and applies the returned
 * result, a navigation action routes its {@code url} ({@code {id}} filled with the row id).
 */
export type ListAction = {
  key: string;
  label: string;
  icon: string;
  /** Image URL/path shown instead of the lucide icon — e.g. a brand logo for "Connect with X". */
  logo?: string;
  scope: "toolbar" | "row";
  server: boolean;
  url?: string;
  kind: string;
  name: string;
};
/**
 * Per-row override for a state-aware row action, computed server-side from the row's data and
 * carried on the row under {@code _actions} (keyed by action key). Lets one control vary by row:
 * a different {@code icon}/{@code label} (e.g. pause→play), or {@code visible}/{@code enabled} false
 * to hide/grey it on rows it doesn't apply to. Any field absent falls back to the static descriptor
 * (icon/label) or the default (visible/enabled = true).
 */
export type RowActionState = {
  visible?: boolean;
  enabled?: boolean;
  icon?: string;
  label?: string;
};
/**
 * A custom toolbar input declared by an EntityView. It doesn't filter the list — its current value
 * is sent with whatever server action button is clicked and reaches the handler via ActionContext.
 */
export type ListInput = {
  key: string;
  label: string;
  type: "text" | "date" | "number" | "select";
  placeholder: string;
  options: string[];
  value: string;
};
/**
 * A declarative filter that drives the list query itself (unlike a {@link ListInput}, which feeds
 * action handlers). "options" renders a SELECT matched for equality on {@code column};
 * "multiOptions" renders a multi-select matched as {@code column IN (…)}; "contains"/"startsWith"
 * render a debounced typeahead matched case-insensitively (LIKE %v% / v%) — the high-cardinality
 * answer where enumerating every value is unusable; "dateRange" renders from/to date pickers driving
 * a {@code column >= from AND column <= to} range. The current value is sent to /api/list as
 * eq/in/like/prefix/ge/le params, and several filters narrow the list jointly (AND).
 */
/** One choice of a (multi-)options filter: {@code value} is matched on the column, {@code label} is shown. */
export type FilterOption = { value: string; label: string };
export type ListFilterControl = {
  key: string;
  label: string;
  column: string;
  type: "options" | "multiOptions" | "contains" | "startsWith" | "dateRange";
  options: FilterOption[];
};
/** A column the list can be grouped by; `date` columns bucket by a chosen day/month/year granularity. */
export type GroupableColumn = { columnName: string; label: string; date: boolean };
/** A per-group subtotal: an aggregate `fn` over `columnName`, shown with `format` and headed `label`. */
export type ListAggregate = { columnName: string; fn: string; label: string; format: string };
export type ListDescriptor = {
  kind: "catalogs" | "documents" | "registers";
  name: string;
  title: string;
  columns: ListColumn[];
  searchable: boolean;
  sort: { column: string | null; descending: boolean };
  newUrl: string | null;
  actions?: ListAction[];
  inputs?: ListInput[];
  filters?: ListFilterControl[];
  /** When set, the toolbar offers a Table ⇄ Map toggle that plots the rows as markers. */
  map?: ListMapConfig;
  /**
   * How the grid feeds rows. "infinite" (default) cursor-scrolls a keyset stream — it loads a
   * window, then more as you scroll, and never counts an exact total. "paged" shows discrete
   * numbered offset pages with a Prev/Next pager and an exact total. Absent → "infinite".
   */
  feedMode?: "infinite" | "paged";
  /** Columns offered in the "Group by ▾" picker; a `date` column buckets by day/month/year. */
  groupable?: GroupableColumn[];
  /** Per-group subtotals shown on each group header (and their display format). */
  aggregates?: ListAggregate[];
  pageSize: number;
  // Embedded in an authored page (PageBuilder.list) rather than rendered as its own route surface.
  // The page already pads its content, so the widget drops its horizontal gutter to align its table
  // with the page's other full-width components (constants editor, action sections).
  embedded?: boolean;
  // Override the data feed URL. Catalogs/documents page from /api/list/{kind}/{name}; a register
  // has no such route, so its descriptor points the island at /api/list/registers/{name}/movements
  // (or /balance). When unset, the default {kind}/{name} feed is used.
  feed?: string;
};

export const ROW_H = 40;
// Radix Select forbids an empty-string item value, so the "no selection" / placeholder choice
// uses this sentinel and is mapped back to "" in state.
const SELECT_NONE = "__onno_none__";

function dispatchAction(url: string) {
  window.dispatchEvent(new CustomEvent("onno:action", { detail: url }));
}

/** The underlying cell string: a resolved ref/enum label, the posted badge, or the raw value. */
function rawCellValue(row: EntityRecord, col: ListColumn, t: Translate): string {
  if (col.columnName === "_posted") return t(row["_posted"] === true ? "status.posted" : "status.draft");
  const v = row[`${col.columnName}_display`] ?? row[col.columnName];
  return v == null ? "" : String(v);
}

/** The displayed text: secret mask, image placeholder, or the value run through .format(...). */
function displayCellValue(raw: string, col: ListColumn): string {
  if (raw === "__SECRET_SET__") return "•••• set";
  if (raw.startsWith("data:")) return "🖼 Image"; // a non-image column keeps the placeholder
  return applyFormat(raw, col.format) ?? raw;
}

/** One list cell: an image thumbnail for image/avatar columns, otherwise formatted text. */
export function ListCell({ row, col }: { row: EntityRecord; col: ListColumn }) {
  const t = useMessages();
  const raw = rawCellValue(row, col, t);
  if (isImageWidget(col.widget) && raw && looksLikeImageUrl(raw)) {
    const avatar = isAvatarWidget(col.widget);
    return (
      <span className="flex items-center">
        <img
          src={raw}
          alt=""
          loading="lazy"
          className={cn(
            "h-7 shrink-0 border border-border object-cover",
            avatar ? "w-7 rounded-control" : "w-10 rounded"
          )}
        />
      </span>
    );
  }
  // A Ref column whose target carries an avatar (resolved into {col}_avatar by the read API): show
  // the photo as a small round avatar beside the display text (issue #182). The data is already
  // here; only image/avatar-widget columns drew it before, so a plain Ref cell stayed text-only.
  const refAvatar = row[`${col.columnName}_avatar`];
  if (typeof refAvatar === "string" && looksLikeImageUrl(refAvatar)) {
    return (
      <span className="flex min-w-0 items-center gap-2">
        <img
          src={refAvatar}
          alt=""
          loading="lazy"
          className="size-6 shrink-0 rounded-full border border-border object-cover"
        />
        <span className="truncate text-foreground">{displayCellValue(raw, col)}</span>
      </span>
    );
  }
  // A status-coloured enum value: the @EnumLabel(color = …) hex rides as {col}_color (RefResolver),
  // so paint the label as a pill the colour of the spreadsheet cell it replaces.
  const pill = enumPillStyle(row[`${col.columnName}_color`] as string | undefined);
  if (pill && raw) {
    return (
      <span className="flex min-w-0">
        <span
          className="truncate rounded-full px-2 py-0.5 text-xs font-medium"
          style={pill}
        >
          {displayCellValue(raw, col)}
        </span>
      </span>
    );
  }
  return <span className="truncate text-foreground">{displayCellValue(raw, col)}</span>;
}

// Mirror of the server's snake-casing so an SSE event's entity name matches the route name.
function toSnake(name: string): string {
  return name.replace(/ /g, "").replace(/([a-z0-9])([A-Z])/g, "$1_$2").toLowerCase();
}
function eventMatches(event: UiEvent, kind: string, name: string): boolean {
  if (!event || event.type === "ready") return false;
  // catalogs→"catalog", documents→"document", registers→"register" (posting emits register changes).
  const singular = kind === "catalogs" ? "catalog" : kind === "registers" ? "register" : "document";
  return event.entityType === singular && (event.entityName === "*" || toSnake(event.entityName ?? "") === name);
}

/**
 * Shared look for a faceted-filter trigger (a shadcn/Linear-style filter "chip"): a pill that reads
 * as an inert prompt while empty (dashed border, muted, a leading +) and as a committed selection
 * once it carries a value (solid accented border, foreground text, the picked value(s) shown inline
 * as small badges). One consistent control replaces the old "Label: <raw control>" inline pairs.
 */
const facetTriggerCls = (active: boolean) =>
  cn(
    "inline-flex h-8 shrink-0 items-center gap-1.5 rounded-control border px-3 text-xs font-medium outline-none transition-colors focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-1 focus-visible:ring-offset-background",
    active
      ? "border-solid border-primary/40 bg-primary/5 text-foreground hover:bg-primary/10"
      : "border-dashed border-input bg-transparent text-muted-foreground hover:border-solid hover:border-input hover:bg-accent hover:text-foreground"
  );

/** A thin vertical rule between a facet's label and its selected-value badges. */
function FacetDivider() {
  return <span aria-hidden="true" className="mx-0.5 h-3.5 w-px shrink-0 bg-border" />;
}

/** A selected value rendered inside a facet trigger. */
function FacetValueBadge({ children }: { children: ReactNode }) {
  return (
    <Badge variant="secondary" className="rounded-sm border-transparent bg-accent px-1 font-normal text-foreground">
      {children}
    </Badge>
  );
}

/**
 * A single- or multi-select facet. A popover of options with a check on each chosen row; the trigger
 * shows the picked labels inline (up to two, then a "{n} selected" summary). Single-select replaces
 * the prior value and closes; multi-select toggles and stays open. Values are keyed by {@code value}
 * (what the query binds); labels are what the user reads. Empty → no constraint.
 */
function OptionsFacet({
  label,
  options,
  multi,
  selected,
  onChange,
}: {
  label: string;
  options: FilterOption[];
  multi: boolean;
  selected: string[];
  onChange: (next: string[]) => void;
}) {
  const t = useMessages();
  const [open, setOpen] = useState(false);
  const labelOf = (value: string) => options.find((o) => o.value === value)?.label ?? value;
  const active = selected.length > 0;
  const toggle = (value: string) => {
    if (multi) {
      onChange(selected.includes(value) ? selected.filter((v) => v !== value) : [...selected, value]);
    } else {
      onChange(selected.includes(value) ? [] : [value]);
      setOpen(false);
    }
  };
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button type="button" className={facetTriggerCls(active)} title={label}>
          <PlusCircle className="size-3.5 shrink-0 opacity-60" />
          <span className="whitespace-nowrap">{label}</span>
          {active ? (
            <>
              <FacetDivider />
              {selected.length <= 2 ? (
                selected.map((v) => <FacetValueBadge key={v}>{labelOf(v)}</FacetValueBadge>)
              ) : (
                <FacetValueBadge>{t("list.selected", { count: selected.length })}</FacetValueBadge>
              )}
            </>
          ) : null}
        </button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-56 p-1">
        <div className="max-h-72 overflow-y-auto">
          {options.map((o) => {
            const on = selected.includes(o.value);
            return (
              <button
                key={o.value}
                type="button"
                onClick={() => toggle(o.value)}
                className="flex w-full cursor-pointer items-center gap-2 rounded px-2 py-1.5 text-left text-sm hover:bg-accent"
              >
                <span
                  className={cn(
                    "flex size-4 shrink-0 items-center justify-center rounded-[4px] border transition-colors",
                    on ? "border-primary bg-primary text-primary-foreground" : "border-input"
                  )}
                >
                  {on ? <Check className="size-3" /> : null}
                </span>
                <span className="truncate">{o.label}</span>
              </button>
            );
          })}
        </div>
        {active ? (
          <>
            <div className="my-1 h-px bg-border" />
            <button
              type="button"
              onClick={() => onChange([])}
              className="w-full rounded px-2 py-1.5 text-center text-xs text-muted-foreground hover:bg-accent"
            >
              {t("list.clear")}
            </button>
          </>
        ) : null}
      </PopoverContent>
    </Popover>
  );
}

/**
 * A field-scoped typeahead facet: a debounced text input styled to sit in the filter bar. Keystrokes
 * update the box immediately but only commit (and re-query) after a short pause, so a contains/
 * starts-with filter over a high-cardinality column doesn't refetch on every character. The label is
 * the placeholder (no separate inline label), and a clear ✕ appears once there's text.
 */
function TextFacet({
  label,
  value,
  onCommit,
}: {
  label: string;
  value: string;
  onCommit: (next: string) => void;
}) {
  const [text, setText] = useState(value);
  // Keep the input in sync if the committed value is reset externally (e.g. a clear-all).
  useEffect(() => setText(value), [value]);
  useEffect(() => {
    const t = window.setTimeout(() => {
      if (text !== value) onCommit(text);
    }, 300);
    return () => window.clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [text]);
  return (
    <div className="relative shrink-0">
      <Search className="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
      <Input
        value={text}
        placeholder={label}
        onChange={(e) => setText(e.target.value)}
        className="h-8 w-40 rounded-control pl-8 pr-7 text-xs"
      />
      {text ? (
        <button
          type="button"
          onClick={() => setText("")}
          className="absolute right-1.5 top-1/2 flex size-5 -translate-y-1/2 items-center justify-center rounded-control text-muted-foreground hover:bg-accent hover:text-foreground"
          aria-label="Clear"
        >
          <X className="size-3.5" />
        </button>
      ) : null}
    </div>
  );
}

/** Short, locale-aware day label ("May 9") for a stored ISO date, shown inside the date-range chip. */
function fmtDay(iso: string): string {
  try {
    return new Intl.DateTimeFormat(undefined, { month: "short", day: "numeric" }).format(
      new Date(`${iso}T00:00`)
    );
  } catch {
    return iso;
  }
}

/**
 * A date-range facet: one chip that opens a popover with quick presets (Today, Last 7 days, This
 * month, …) beside a two-month range calendar — the pattern every analytics tool (Grafana, Stripe,
 * Linear) uses. Replaces the old two bare date inputs. State is the same {from,to} ISO pair the
 * query binds to {@code ge}/{@code le}; presets and the calendar both write both ends at once.
 */
function DateRangeFacet({
  label,
  from,
  to,
  onChange,
}: {
  label: string;
  from: string;
  to: string;
  onChange: (next: { from: string; to: string }) => void;
}) {
  const t = useMessages();
  const [open, setOpen] = useState(false);
  const active = !!from && !!to;
  // filterState ISO strings ↔ the calendar's {start,end} CalendarDate range.
  const range = (() => {
    if (!from || !to) return null;
    try {
      return { start: parseDate(from), end: parseDate(to) };
    } catch {
      return null;
    }
  })();
  const setRange = (start: CalendarDate, end: CalendarDate) =>
    onChange({ from: start.toString(), to: end.toString() });
  const now = today(getLocalTimeZone());
  const presets: { label: string; start: CalendarDate; end: CalendarDate }[] = [
    { label: t("list.dateToday"), start: now, end: now },
    { label: t("list.dateYesterday"), start: now.subtract({ days: 1 }), end: now.subtract({ days: 1 }) },
    { label: t("list.dateLast7"), start: now.subtract({ days: 6 }), end: now },
    { label: t("list.dateLast30"), start: now.subtract({ days: 29 }), end: now },
    { label: t("list.dateThisMonth"), start: startOfMonth(now), end: now },
    { label: t("list.dateThisYear"), start: startOfYear(now), end: now },
  ];
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button type="button" className={facetTriggerCls(active)} title={label}>
          <CalendarDays className="size-3.5 shrink-0 opacity-60" />
          <span className="whitespace-nowrap">{label}</span>
          {active ? (
            <>
              <FacetDivider />
              <FacetValueBadge>
                {fmtDay(from)} – {fmtDay(to)}
              </FacetValueBadge>
            </>
          ) : null}
        </button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-auto p-0">
        <div className="flex flex-col sm:flex-row">
          <div className="flex flex-row gap-1 border-b p-2 sm:flex-col sm:border-b-0 sm:border-r">
            {presets.map((p) => (
              <button
                key={p.label}
                type="button"
                onClick={() => {
                  setRange(p.start, p.end);
                  setOpen(false);
                }}
                className="whitespace-nowrap rounded px-2.5 py-1.5 text-left text-xs text-foreground hover:bg-accent"
              >
                {p.label}
              </button>
            ))}
          </div>
          <div>
            <RangeCalendar
              aria-label={label}
              value={range}
              onChange={(v) => {
                if (v) setRange(v.start as CalendarDate, v.end as CalendarDate);
              }}
            />
            {active ? (
              <div className="flex justify-end border-t px-3 py-2">
                <button
                  type="button"
                  onClick={() => {
                    onChange({ from: "", to: "" });
                    setOpen(false);
                  }}
                  className="rounded px-2 py-1 text-xs text-muted-foreground hover:bg-accent hover:text-foreground"
                >
                  {t("list.clear")}
                </button>
              </div>
            ) : null}
          </div>
        </div>
      </PopoverContent>
    </Popover>
  );
}

// A window bigger than one page cannot exceed the server's list ceiling.
const MAX_WINDOW = 500;
// Extra rows rendered above/below the viewport in infinite mode, so a fast scroll never flashes blank.
const OVERSCAN = 8;

export function EntityListWidget({ list }: { list: ListDescriptor }) {
  const { kind, name, columns, pageSize } = list;
  // Feed mode: "infinite" cursor-scrolls a keyset stream (the default); "paged" shows numbered
  // offset pages. The two drive different server engines (cursor vs offset) — see loadInitial.
  const feedMode = list.feedMode === "paged" ? "paged" : "infinite";
  const t = useMessages();
  // Ambient presence: other users viewing each row's record, looked up by row id at render time.
  const viewersById = useViewersById();
  const rootRef = useRef<HTMLDivElement | null>(null);
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const gen = useRef(0); // bumped on each query so a stale window fetch is ignored

  // ---- URL-persisted view state (standalone list routes only) ----
  // A non-embedded list owns its route, so we mirror its search / sort / filters / view into the
  // query string: the view then survives an island remount (a theme switch re-inits DivKit and
  // remounts every island, which used to wipe the filters) and a filtered list becomes a shareable,
  // bookmarkable link. Embedded lists (dashboard cards, master-detail panes) keep purely local state
  // — several of them on one page would collide on the single shared URL.
  const urlSynced = !list.embedded;
  // Captured once at mount; used only to seed the initial state below.
  const initialParams = useMemo(
    () => new URLSearchParams(urlSynced ? window.location.search : ""),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    []
  );

  // The currently displayed rows (null = first load, still blank). In paged mode this is exactly the
  // active page; in infinite mode windows are appended here as you scroll.
  const [pageRows, setPageRows] = useState<EntityRecord[] | null>(null);
  const rowsRef = useRef<EntityRecord[] | null>(null);
  rowsRef.current = pageRows;
  // The exact total (paged; also a cheap estimate in infinite mode when the server can supply one),
  // or null when unknown — infinite mode never blocks on a COUNT.
  const [total, setTotal] = useState<number | null>(null);
  const [page, setPage] = useState(0); // paged mode only
  const pageRef = useRef(0);
  pageRef.current = page;
  // Infinite mode: the opaque cursor for the next window, whether more remain, and whether a
  // load-more is in flight (the ref guards against firing a second fetch before the first lands).
  const cursorRef = useRef<string | null>(null);
  const [hasMore, setHasMore] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const loadingMoreRef = useRef(false);
  // The island can be squeezed narrow (e.g. a master-detail split) while the viewport stays wide,
  // so the toolbar layout is driven by the measured container width, not a media query.
  const [toolbarWidth, setToolbarWidth] = useState<number | null>(null);
  // Route-surface height: the island fills the space to the window bottom and scrolls internally,
  // so the page (its DivKit container) never scrolls — only the list body does. Null until measured
  // (and unused in embedded mode, where the host page owns scrolling).
  const [surfaceH, setSurfaceH] = useState<number | null>(null);
  // Embedded-mode fallback: cap the scroll body so a big in-page list scrolls internally instead of
  // stretching the host page unboundedly.
  const [maxBodyH, setMaxBodyH] = useState(420);
  // Scroll position + viewport height of the body, for the infinite-mode virtual window.
  const [scrollTop, setScrollTop] = useState(0);
  const [viewportH, setViewportH] = useState(0);
  // Sort + search seed from the URL on a standalone list (see urlSynced), so a shared/bookmarked link
  // restores them; an embedded list starts from the descriptor default.
  const [sort, setSort] = useState<{ column: string | null; descending: boolean }>(() => {
    const col = urlSynced ? initialParams.get("sort") : null;
    return col ? { column: col, descending: initialParams.get("dir") === "desc" } : list.sort;
  });
  const [query, setQuery] = useState(() => (urlSynced ? initialParams.get("q") ?? "" : ""));
  const [debounced, setDebounced] = useState(() => (urlSynced ? initialParams.get("q") ?? "" : ""));
  // Grouping: which column the list is grouped by ("" = flat), and — for a date column — the bucket
  // granularity. Picking a column swaps the flat table for the collapsible grouped view.
  const groupable = useMemo(() => list.groupable ?? [], [list.groupable]);
  const aggregates = useMemo(() => list.aggregates ?? [], [list.aggregates]);
  const [groupBy, setGroupBy] = useState("");
  const [granularity, setGranularity] = useState("month");
  const groupCol = groupable.find((g) => g.columnName === groupBy);
  // Keys of in-flight server actions (`key` for toolbar, `key:id` for a row) → spinner + disabled.
  const [pending, setPending] = useState<Set<string>>(new Set());
  // Table vs map view (only offered when the list declares a map config). The map fetches its own
  // rows, so the grid's search/filters/sort are hidden while it's shown.
  const [view, setView] = useState<"table" | "map">(() => {
    if (urlSynced && list.map) {
      const v = initialParams.get("view");
      if (v === "map") return "map";
      if (v === "table") return "table";
    }
    return list.map?.defaultView ? "map" : "table";
  });
  const mapMode = !!list.map && view === "map";
  // Grouped view replaces the flat table (never shown together with the map).
  const grouped = !!groupBy && !mapMode;
  // Catalog/document rows open their record; register rows (movements/balance) have no detail route,
  // so they aren't clickable. Also gates the single-row live patch (registers have no row identity).
  const openable = kind === "catalogs" || kind === "documents";
  // Where windows are fetched from: a register points the island at its own feed; others use the
  // standard /api/list/{kind}/{name} route.
  const feedBase = list.feed ?? `/api/list/${kind}/${name}`;
  // Route surface (own page) vs embedded in an authored dashboard page. Only the route surface takes
  // over the viewport height and scrolls internally; embedded flows with the host page.
  const surfaceMode = !list.embedded;

  const allActions = list.actions ?? [];
  const toolbarActions = allActions.filter((a) => a.scope === "toolbar");
  const rowActions = allActions.filter((a) => a.scope === "row");
  const toolbarInputs = list.inputs ?? [];
  // Stable reference so the fetch/reset effects don't churn when there are no filters.
  const filters = useMemo(() => list.filters ?? [], [list.filters]);

  // Rebuild filter state from the URL (each filter's `f.<key>` param, mirroring the write below).
  // Unknown/blank params yield an empty {} for that filter, i.e. no constraint.
  const readFiltersFromUrl = useCallback(
    (params: URLSearchParams) =>
      Object.fromEntries(
        filters.map((f) => {
          const p = `f.${f.key}`;
          if (f.type === "dateRange") {
            const from = params.get(`${p}.from`) ?? "";
            const to = params.get(`${p}.to`) ?? "";
            return [f.key, from || to ? { from, to } : {}];
          }
          if (f.type === "multiOptions") {
            const vals = params.getAll(p);
            return [f.key, vals.length ? { in: vals } : {}];
          }
          if (f.type === "contains" || f.type === "startsWith") {
            const text = params.get(p) ?? "";
            return [f.key, text ? { text } : {}];
          }
          const eq = params.get(p) ?? "";
          return [f.key, eq ? { eq } : {}];
        })
      ),
    [filters]
  );

  // Current value of each declarative filter: {eq} for an options filter, {in} (picked values) for a
  // multi-select, {text} for a contains/starts-with typeahead, {from,to} for a date range. Changing
  // one resets the grid and re-queries (see the reset effect below) — it narrows the rows, not just
  // the toolbar. Serialized into filterSig so effects can depend on its content.
  const [filterState, setFilterState] = useState<
    Record<string, { eq?: string; in?: string[]; text?: string; from?: string; to?: string }>
  >(() =>
    urlSynced ? readFiltersFromUrl(initialParams) : Object.fromEntries(filters.map((f) => [f.key, {}]))
  );
  const filterSig = JSON.stringify(filterState);
  const filterStateRef = useRef(filterState);
  filterStateRef.current = filterState;

  // Mirror the applied view state back into the URL with replaceState (no history entry per keystroke,
  // but the browser back button still works at the route level, and the URL is copy-shareable). Only
  // non-default values are written, so an untouched list keeps a clean path. Unrelated query params
  // (anything not `q`/`sort`/`dir`/`view`/`f.*`) are preserved. Uses `debounced` (the applied search),
  // not the raw box, so the URL reflects what's actually queried.
  useEffect(() => {
    if (!urlSynced) return;
    const params = new URLSearchParams(window.location.search);
    const managed = (k: string) =>
      k === "q" || k === "sort" || k === "dir" || k === "view" || k.startsWith("f.");
    for (const k of new Set([...params.keys()].filter(managed))) params.delete(k);
    if (debounced) params.set("q", debounced);
    if (sort.column && (sort.column !== list.sort.column || sort.descending !== list.sort.descending)) {
      params.set("sort", sort.column);
      params.set("dir", sort.descending ? "desc" : "asc");
    }
    if (mapMode !== !!list.map?.defaultView) params.set("view", mapMode ? "map" : "table");
    for (const f of filters) {
      const st = filterStateRef.current[f.key];
      if (!st) continue;
      const p = `f.${f.key}`;
      if (f.type === "dateRange") {
        if (st.from) params.set(`${p}.from`, st.from);
        if (st.to) params.set(`${p}.to`, st.to);
      } else if (f.type === "multiOptions") {
        for (const v of st.in ?? []) params.append(p, v);
      } else if (f.type === "contains" || f.type === "startsWith") {
        if (st.text?.trim()) params.set(p, st.text.trim());
      } else if (st.eq) {
        params.set(p, st.eq);
      }
    }
    const qs = params.toString();
    window.history.replaceState(
      window.history.state,
      "",
      qs ? `${window.location.pathname}?${qs}` : window.location.pathname
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [urlSynced, filterSig, debounced, sort.column, sort.descending, mapMode]);

  // How many filters currently constrain the query — drives the "Clear all" affordance on the
  // filter bar. A filter counts as active only when it actually narrows rows (a picked option, some
  // typed text, at least one end of a date range), not merely because it's declared.
  const activeFilterCount = useMemo(
    () =>
      filters.reduce((n, f) => {
        const st = filterState[f.key];
        if (!st) return n;
        if (f.type === "dateRange") return n + (st.from || st.to ? 1 : 0);
        if (f.type === "multiOptions") return n + ((st.in?.length ?? 0) > 0 ? 1 : 0);
        if (f.type === "contains" || f.type === "startsWith") return n + (st.text?.trim() ? 1 : 0);
        return n + (st.eq ? 1 : 0);
      }, 0),
    [filters, filterState]
  );
  const clearAllFilters = () =>
    setFilterState(Object.fromEntries(filters.map((f) => [f.key, {}])));

  // Responsive header row, driven by the measured island width and the controls actually present
  // (not a fixed media query). `stacked` puts the title on its own row and the controls below the
  // moment they would no longer fit beside it — so the title never truncates. `compact` (much
  // narrower) additionally drops the button labels to icons. Filters live on their own wrapping bar
  // below, so only the header-row controls (view toggle, search, actions, New) count here.
  const controlsWidthEstimate =
    (list.map ? 150 : 0) +
    (list.searchable ? 220 : 0) +
    toolbarActions.length * 100 +
    (list.newUrl ? 90 : 0);
  const stacked = toolbarWidth != null && toolbarWidth < controlsWidthEstimate + 170;
  const compact = toolbarWidth != null && toolbarWidth < 560;

  // Live values of the custom toolbar inputs, seeded from their declared defaults. These are sent
  // with every server action so the handler can read them via ActionContext.input(key).
  const [inputValues, setInputValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(toolbarInputs.map((i) => [i.key, i.value ?? ""]))
  );
  const inputValuesRef = useRef(inputValues);
  inputValuesRef.current = inputValues;

  // Column grid template: an authored px width, else a flexible track. A trailing fixed column
  // holds the per-row action buttons when any are declared. The floor is 0 here because the
  // table's overall min-width (below) keeps the columns readable — when the card is narrower
  // than that, the table scrolls horizontally instead of cramming the columns.
  const DATA_COL_MIN = 150;
  const ACTION_COL_W = rowActions.length ? rowActions.length * 36 + 8 : 0;
  const template =
    columns
      .map((c) => {
        const px = parseInt(c.width, 10);
        return Number.isFinite(px) && px > 0 ? `${px}px` : "minmax(0,1fr)";
      })
      .concat(ACTION_COL_W ? [`${ACTION_COL_W}px`] : [])
      .join(" ");

  // When any loaded row has viewers, widen the whole list's left padding so the presence face-pile sits
  // in its own gutter instead of over the first column. Applied to the header too, so columns stay
  // aligned; it collapses back when nobody is viewing (a rare, gentle shift).
  const listHasPresence = openable && (pageRows ?? []).some((r) => r && viewersById.has(String(r._id)));
  const leftPad = listHasPresence ? "pl-11 pr-4" : "px-4";

  // Natural minimum width of the table: each column at its authored width (or DATA_COL_MIN),
  // plus the action column, the 12px inter-column gaps and the 32px (px-4) row padding. When the
  // card is wider, the grid fills it (1fr expands); when narrower, this drives a horizontal scroll.
  const trackCount = columns.length + (ACTION_COL_W ? 1 : 0);
  const minTableWidth =
    columns.reduce((sum, c) => {
      const px = parseInt(c.width, 10);
      return sum + (Number.isFinite(px) && px > 0 ? px : DATA_COL_MIN);
    }, 0) +
    ACTION_COL_W +
    Math.max(0, trackCount - 1) * 12 +
    32;

  // Debounce the search box.
  useEffect(() => {
    const t = window.setTimeout(() => setDebounced(query.trim()), 250);
    return () => window.clearTimeout(t);
  }, [query]);

  // Build the shared query params (sort + search + declarative filters). The pagination params
  // (offset/limit or cursor/limit) are added by the caller, since they differ per feed mode.
  const buildParams = useCallback(() => {
    const params = new URLSearchParams();
    if (sort.column) {
      params.set("sort", sort.column);
      params.set("dir", sort.descending ? "desc" : "asc");
    }
    if (debounced) params.set("q", debounced);
    // Declarative filters → eq/in/like/prefix/ge/le params (column,value); the server re-validates
    // the column. Several filters AND together; a multi-select sends one `in` per picked value.
    for (const f of filters) {
      const st = filterStateRef.current[f.key];
      if (!st) continue;
      if (f.type === "dateRange") {
        if (st.from) params.append("ge", `${f.column},${st.from}`);
        if (st.to) params.append("le", `${f.column},${st.to}`);
      } else if (f.type === "multiOptions") {
        for (const v of st.in ?? []) params.append("in", `${f.column},${v}`);
      } else if (f.type === "contains") {
        if (st.text?.trim()) params.append("like", `${f.column},${st.text.trim()}`);
      } else if (f.type === "startsWith") {
        if (st.text?.trim()) params.append("prefix", `${f.column},${st.text.trim()}`);
      } else if (st.eq) {
        params.append("eq", `${f.column},${st.eq}`);
      }
    }
    return params;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sort.column, sort.descending, debounced, filters, filterSig]);

  // The shared query (sort + search + filters) the grouped view fetches groups/rows with, as a
  // string so its effects can depend on it.
  const groupParamsBase = useMemo(() => buildParams().toString(), [buildParams]);

  // Load the first window (infinite) or the active page (paged). Bumps the generation so a slow
  // fetch that lands after a newer query is discarded. A hard load blanks to the skeleton; a soft
  // load (live refresh) keeps the current rows on screen and swaps them in place when data lands.
  const loadInitial = useCallback(
    (soft = false) => {
      const myGen = ++gen.current;
      loadingMoreRef.current = false;
      if (!soft) setPageRows(null);
      const params = buildParams();
      if (feedMode === "paged") {
        params.set("offset", String(pageRef.current * pageSize));
        params.set("limit", String(pageSize));
        fetch(`${feedBase}?${params.toString()}`, { credentials: "include" })
          .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
          .then((data: { total: number; offset: number; rows: EntityRecord[] }) => {
            if (myGen !== gen.current) return; // a newer query/page superseded this fetch
            setTotal(data.total);
            setPageRows(data.rows);
            if (!soft && scrollRef.current) scrollRef.current.scrollTop = 0;
          })
          .catch(() => {
            if (myGen === gen.current) setPageRows([]);
          });
        return;
      }
      // Infinite: a soft reload re-fetches everything already loaded in one window (so a live change
      // doesn't yank the user back to the top); a hard load fetches just the first window.
      const loaded = rowsRef.current?.length ?? 0;
      const want = soft ? Math.min(MAX_WINDOW, Math.max(pageSize, loaded)) : pageSize;
      params.set("limit", String(want));
      params.set("count", "estimate"); // cheap approximate total for the header; may be absent
      fetch(`${feedBase}?${params.toString()}`, { credentials: "include" })
        .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
        .then((data: { rows: EntityRecord[]; nextCursor: string | null; hasMore: boolean; total?: number }) => {
          if (myGen !== gen.current) return;
          setTotal(typeof data.total === "number" ? data.total : null);
          cursorRef.current = data.nextCursor ?? null;
          setHasMore(!!data.hasMore);
          setPageRows(data.rows ?? []);
          if (!soft && scrollRef.current) scrollRef.current.scrollTop = 0;
        })
        .catch(() => {
          if (myGen === gen.current) setPageRows([]);
        });
    },
    [feedMode, feedBase, pageSize, buildParams]
  );

  // Infinite mode: append the next window, seeking from the current cursor. Guarded so only one
  // load-more runs at a time and a window from a superseded query is dropped.
  const loadMore = useCallback(() => {
    if (feedMode !== "infinite" || loadingMoreRef.current || !cursorRef.current) return;
    loadingMoreRef.current = true;
    setLoadingMore(true);
    const myGen = gen.current;
    const params = buildParams();
    params.set("limit", String(pageSize));
    params.set("cursor", cursorRef.current);
    fetch(`${feedBase}?${params.toString()}`, { credentials: "include" })
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
      .then((data: { rows: EntityRecord[]; nextCursor: string | null; hasMore: boolean }) => {
        if (myGen !== gen.current) return; // a newer query superseded this window
        cursorRef.current = data.nextCursor ?? null;
        setHasMore(!!data.hasMore);
        setPageRows((cur) => (cur ? [...cur, ...(data.rows ?? [])] : data.rows ?? []));
      })
      .catch(() => {})
      .finally(() => {
        loadingMoreRef.current = false;
        if (myGen === gen.current) setLoadingMore(false);
      });
  }, [feedMode, feedBase, pageSize, buildParams]);

  // Snap back to the first page whenever the sort, the (debounced) search, or a filter changes
  // (paged mode; infinite mode stays at page 0 and resets via the load effect below).
  useEffect(() => {
    setPage(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sort.column, sort.descending, debounced, filterSig]);

  // Load the initial window/page: on first mount, on any query change, and (paged) on a page turn.
  // Reset the infinite cursor so a query change re-seeks from the top rather than an old cursor.
  useEffect(() => {
    cursorRef.current = null;
    setHasMore(true);
    loadInitial(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [feedMode, feedBase, pageSize, sort.column, sort.descending, debounced, filterSig, page]);

  // Track the island width so the toolbar can stack / collapse responsively (see below).
  useLayoutEffect(() => {
    const el = rootRef.current;
    if (!el) return;
    const apply = () => setToolbarWidth(el.clientWidth);
    apply();
    const ro = new ResizeObserver(apply);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  // Route surface: fill the space from the island's top to the window bottom, so the list body
  // scrolls internally and the page (DivKit container) never scrolls. The root then carries its own
  // padding, so the gap under the card matches the top/left gap.
  //
  // We must measure our top live, not just once: the workspace tab bar, the sidebar and web-font
  // loading all shift it *after* mount, and the DivKit wrappers around us are `overflow: clip` and
  // sized to our fixed height — so a stale (too-small) top overshoots the viewport and the clip eats
  // the bottom padding (the card then runs flush to the window edge). A re-measure on the next frame
  // and on any shell reflow keeps the height correct, so the bottom gap stays equal to the top.
  useLayoutEffect(() => {
    if (!surfaceMode) {
      setSurfaceH(null);
      return;
    }
    const el = rootRef.current;
    if (!el) return;
    const measure = () => {
      const top = el.getBoundingClientRect().top;
      setSurfaceH(Math.max(240, Math.floor(window.innerHeight - top)));
    };
    measure();
    const raf = requestAnimationFrame(measure); // re-measure once layout has settled
    window.addEventListener("resize", measure);
    // Any reflow in the shell can move our top; re-measure. It converges (same top → same height),
    // so this doesn't loop.
    const ro = new ResizeObserver(measure);
    ro.observe(document.body);
    return () => {
      cancelAnimationFrame(raf);
      window.removeEventListener("resize", measure);
      ro.disconnect();
    };
  }, [surfaceMode, stacked]);

  // Embedded mode only: cap the scroll body at the space to the window bottom (leaving room for the
  // pager footer) so a big in-page list scrolls internally rather than stretching the host page.
  useLayoutEffect(() => {
    if (surfaceMode) return;
    const measure = () => {
      const el = scrollRef.current;
      if (!el) return;
      const top = el.getBoundingClientRect().top;
      setMaxBodyH(Math.max(160, Math.floor(window.innerHeight - top - 72)));
    };
    measure();
    window.addEventListener("resize", measure);
    const ro = new ResizeObserver(measure);
    if (scrollRef.current?.parentElement) ro.observe(scrollRef.current.parentElement);
    return () => {
      window.removeEventListener("resize", measure);
      ro.disconnect();
    };
  }, [surfaceMode]);

  // Keep the virtual-window viewport height in sync with the body's client height.
  useLayoutEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    const apply = () => setViewportH(el.clientHeight);
    apply();
    const ro = new ResizeObserver(apply);
    ro.observe(el);
    return () => ro.disconnect();
  }, [surfaceMode]);

  // Body scroll: drive the virtual window, and (infinite) fetch the next window as the end nears.
  const onScroll = useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    setScrollTop(el.scrollTop);
    if (
      feedMode === "infinite" &&
      hasMore &&
      !loadingMoreRef.current &&
      el.scrollTop + el.clientHeight >= el.scrollHeight - ROW_H * OVERSCAN
    ) {
      loadMore();
    }
  }, [feedMode, hasMore, loadMore]);

  // Re-fetch the loaded rows in place (after a server action or a live event) — a soft refresh that
  // keeps the visible rows until the new data lands, so it never flashes the skeleton.
  const reload = useCallback(() => {
    loadInitial(true);
  }, [loadInitial]);

  // Run a custom action button. A navigation action just routes (filling {id} for a row); a
  // server action POSTs to /api/actions and applies the ActionResult — toast, navigate, refresh.
  // While the (possibly slow/async) handler runs, the button shows a spinner and is disabled, so
  // there's feedback and no double-submit. (api.runAction is CSRF-aware and toasts failures.)
  const runAction = useCallback(
    (action: ListAction, id?: string) => {
      if (!action.server) {
        if (action.url) dispatchAction(id ? action.url.replace("{id}", id) : action.url);
        return;
      }
      const k = id ? `${action.key}:${id}` : action.key;
      setPending((s) => {
        if (s.has(k)) return s; // already running — ignore the re-click
        const n = new Set(s);
        n.add(k);
        return n;
      });
      api
        .runAction(action.kind, action.name, action.key, id, inputValuesRef.current)
        .then((result) => {
          if (result?.message) toast.success(result.message);
          if (result?.navigate) dispatchAction(result.navigate);
          if (result?.refresh) reload();
        })
        .catch(() => {})
        .finally(() =>
          setPending((s) => {
            if (!s.has(k)) return s;
            const n = new Set(s);
            n.delete(k);
            return n;
          })
        );
    },
    [reload]
  );

  // Surgically refresh a single changed row in place: fetch just that id (same decorated shape as a
  // page) and swap it where it sits — no cache wipe, no skeleton flash, no scroll jump. If the row
  // isn't in the loaded window we ignore it (it'll load fresh on scroll); if it has left the result
  // set (e.g. deletion-marked via an update) we fall back to a window reload.
  const patchRow = useCallback(
    (id: string) => {
      setPageRows((current) => {
        if (!current || !current.some((r) => r && String(r._id) === id)) return current; // not on this page
        // Fetch just this row (same decorated shape as a page) and splice it in place.
        fetch(`${feedBase}?ids=${encodeURIComponent(id)}`, { credentials: "include" })
          .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
          .then((data: { rows: EntityRecord[] }) => {
            const fresh = data.rows?.[0];
            if (fresh) {
              setPageRows((rows) =>
                rows ? rows.map((r) => (String(r._id) === id ? fresh : r)) : rows
              );
            } else {
              reload(); // row dropped out of the set — refresh the page
            }
          })
          .catch(() => {});
        return current;
      });
    },
    [feedBase, reload]
  );

  // Live updates: divkit-view fans out every SSE row change as an "onno:dataevent" window event
  // (one shared stream). An in-place update to an already-loaded row patches just that row; a
  // create/delete (or a wildcard event, or a register change) reloads the visible window — the row
  // count and ordering shift, so a window refresh is the correct, cheap response.
  useEffect(() => {
    const onData = (e: Event) => {
      const event = (e as CustomEvent).detail as UiEvent;
      if (!eventMatches(event, kind, name)) return;
      const surgical =
        openable &&
        !!event.id &&
        event.entityName !== "*" &&
        (event.type === "updated" ||
          event.type === "posted" ||
          event.type === "unposted" ||
          event.type === "changed");
      if (surgical) patchRow(event.id!);
      else reload();
    };
    window.addEventListener("onno:dataevent", onData);
    return () => window.removeEventListener("onno:dataevent", onData);
  }, [kind, name, openable, reload, patchRow]);

  // Pager math (paged mode): how many pages, and the 1-based "from–to of total" range for the page.
  const paged = feedMode === "paged";
  const pageCount = total == null ? 1 : Math.max(1, Math.ceil(total / pageSize));
  const rangeFrom = total ? page * pageSize + 1 : 0;
  const rangeTo = total == null ? 0 : Math.min(total, page * pageSize + pageSize);
  // Clamp the page if the row count shrank under us (a filter/delete) and left us past the last page.
  useEffect(() => {
    if (paged && total != null && page > 0 && page > pageCount - 1) setPage(pageCount - 1);
  }, [paged, total, page, pageCount]);

  // Rows currently loaded, and — the header count — the exact/estimated total when known, else the
  // loaded count (infinite mode without a server estimate, e.g. on H2).
  const loadedRows = pageRows ?? [];
  const countValue = total ?? (pageRows == null ? null : loadedRows.length);

  // Virtual window (infinite mode): render only the rows overlapping the viewport (± overscan),
  // padding the scroller above/below with the height of the rows not drawn. Paged mode renders the
  // whole page (it's small), so the window spans everything.
  const virtual = feedMode === "infinite" && pageRows != null;
  const startIdx = virtual ? Math.max(0, Math.floor(scrollTop / ROW_H) - OVERSCAN) : 0;
  const endIdx = virtual
    ? Math.min(loadedRows.length, Math.ceil((scrollTop + viewportH) / ROW_H) + OVERSCAN)
    : loadedRows.length;
  const visibleRows = pageRows == null ? [] : loadedRows.slice(startIdx, endIdx);
  const padTop = startIdx * ROW_H;
  const padBottom = Math.max(0, (loadedRows.length - endIdx) * ROW_H);

  // Cycle a column through three states on repeated clicks: ascending → descending → off
  // (off clears the sort entirely, so the list falls back to the server's default order).
  const toggleSort = (col: string) => {
    setSort((s) => {
      if (s.column !== col) return { column: col, descending: false }; // new column → ascending
      if (!s.descending) return { column: col, descending: true }; // ascending → descending
      return { column: null, descending: false }; // descending → off
    });
  };

  return (
    // DivKit wraps custom blocks in spans with pointer-events:none, which the island inherits —
    // re-assert pointer-events:auto here so hover, row clicks and the right-click menu all work.
    // As its own route surface the island takes the full height from its top to the window bottom
    // (equal padding all round) and scrolls only its list body inside — so the page never scrolls.
    // Embedded in a page, it flows with the host page (which owns scrolling) and drops its
    // horizontal gutter so the table aligns with the sibling cards.
    <div
      ref={rootRef}
      className={cn(
        "pointer-events-auto flex min-h-0 flex-col",
        surfaceMode ? "overflow-hidden p-4 sm:p-6" : "py-4"
      )}
      style={surfaceMode && surfaceH != null ? { height: surfaceH } : undefined}
    >
      {/* Control island — a single floating bar that replaces the old title/search/actions row AND
          the separate filter bar. Title + row count lead; search, group-by and the filter facets fill
          the middle; the view toggle, custom actions and New pin right. It wraps (flex-wrap) when
          narrow; `shrink-0` keeps the card below flexing to fill the surface. */}
      <div className="mb-3 flex shrink-0 flex-wrap items-center gap-x-2 gap-y-2 rounded-card border border-border/70 bg-muted/30 px-2.5 py-2">
        {/* title + row count */}
        <div className="mr-1 flex min-w-0 items-baseline gap-2">
          <h1 className="truncate text-base font-semibold text-foreground">{list.title}</h1>
          <span className="whitespace-nowrap text-xs tabular-nums text-muted-foreground">
            {countValue == null ? "…" : t("list.count", { count: countValue })}
          </span>
        </div>

        {/* search */}
        {!mapMode && list.searchable ? (
          <div className={cn("relative", stacked ? "min-w-[9rem] flex-1" : "")}>
            <Search className="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
            <Input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder={t("list.search")}
              className={cn("h-8 rounded-control pl-8 text-xs", stacked ? "w-full" : "w-44 sm:w-52")}
            />
          </div>
        ) : null}

        {/* group-by (+ granularity for a date column) */}
        {!mapMode && groupable.length ? (
          <label className="flex shrink-0 items-center gap-1.5 text-xs text-muted-foreground">
            <span className="whitespace-nowrap">{t("list.groupBy")}</span>
            <Select
              value={groupBy || SELECT_NONE}
              onValueChange={(val) => setGroupBy(val === SELECT_NONE ? "" : val)}
            >
              <SelectTrigger className="h-8 w-32 rounded-control text-xs">
                <SelectValue placeholder={t("list.groupNone")} />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={SELECT_NONE}>{t("list.groupNone")}</SelectItem>
                {groupable.map((g) => (
                  <SelectItem key={g.columnName} value={g.columnName}>
                    {g.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            {groupCol?.date ? (
              <Select value={granularity} onValueChange={setGranularity}>
                <SelectTrigger className="h-8 w-24 rounded-control text-xs">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="day">{t("list.granDay")}</SelectItem>
                  <SelectItem value="month">{t("list.granMonth")}</SelectItem>
                  <SelectItem value="year">{t("list.granYear")}</SelectItem>
                </SelectContent>
              </Select>
            ) : null}
          </label>
        ) : null}

        {/* filter facets */}
        {!mapMode &&
          filters.map((f) => {
            if (f.type === "dateRange") {
              return (
                <DateRangeFacet
                  key={f.key}
                  label={f.label}
                  from={filterState[f.key]?.from ?? ""}
                  to={filterState[f.key]?.to ?? ""}
                  onChange={(next) => setFilterState((s) => ({ ...s, [f.key]: next }))}
                />
              );
            }
            if (f.type === "options" || f.type === "multiOptions") {
              const multi = f.type === "multiOptions";
              const st = filterState[f.key];
              const selected = multi ? st?.in ?? [] : st?.eq ? [st.eq] : [];
              return (
                <OptionsFacet
                  key={f.key}
                  label={f.label}
                  options={f.options}
                  multi={multi}
                  selected={selected}
                  onChange={(next) =>
                    setFilterState((s) => ({
                      ...s,
                      [f.key]: multi ? { in: next } : { eq: next[0] ?? "" },
                    }))
                  }
                />
              );
            }
            return (
              <TextFacet
                key={f.key}
                label={f.label}
                value={filterState[f.key]?.text ?? ""}
                onCommit={(next) => setFilterState((s) => ({ ...s, [f.key]: { text: next } }))}
              />
            );
          })}
        {!mapMode &&
          toolbarInputs.map((inp) => (
            <label key={inp.key} className="flex shrink-0 items-center gap-1.5 text-xs text-muted-foreground">
              <span className="whitespace-nowrap">{inp.label}</span>
              {inp.type === "select" ? (
                <Select
                  value={inputValues[inp.key] ?? ""}
                  onValueChange={(val) =>
                    setInputValues((v) => ({ ...v, [inp.key]: val === SELECT_NONE ? "" : val }))
                  }
                >
                  <SelectTrigger className="h-8 w-36 text-xs">
                    <SelectValue placeholder={inp.placeholder || inp.label} />
                  </SelectTrigger>
                  <SelectContent>
                    {inp.placeholder ? <SelectItem value={SELECT_NONE}>{inp.placeholder}</SelectItem> : null}
                    {inp.options.map((o) => (
                      <SelectItem key={o} value={o}>
                        {o}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              ) : inp.type === "date" ? (
                <div className="w-40">
                  <DatePicker
                    value={inputValues[inp.key] || undefined}
                    onChange={(val) => setInputValues((v) => ({ ...v, [inp.key]: val }))}
                  />
                </div>
              ) : (
                <Input
                  type={inp.type === "number" ? "number" : "text"}
                  value={inputValues[inp.key] ?? ""}
                  onChange={(e) => setInputValues((v) => ({ ...v, [inp.key]: e.target.value }))}
                  placeholder={inp.placeholder}
                  className="h-8 w-36 text-xs"
                />
              )}
            </label>
          ))}
        {!mapMode && activeFilterCount > 0 ? (
          <button
            type="button"
            onClick={clearAllFilters}
            className="inline-flex h-8 shrink-0 items-center gap-1 rounded-control px-2.5 text-xs font-medium text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
          >
            <X className="size-3.5" />
            {t("list.clearAll")}
          </button>
        ) : null}

        {/* right cluster — view toggle, custom actions, New */}
        <div className="ml-auto flex flex-wrap items-center gap-2">
          {list.map ? (
            <div className="inline-flex h-8 shrink-0 items-center rounded-control border border-input bg-background/60 p-0.5">
              <button
                type="button"
                onClick={() => setView("table")}
                className={cn(
                  "inline-flex h-7 items-center gap-1.5 rounded-control px-2.5 text-xs font-medium",
                  view === "table" ? "bg-card text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground"
                )}
                title={t("list.tableView")}
                aria-label={t("list.tableView")}
                aria-pressed={view === "table"}
              >
                <Table2 className="size-4" />
                {compact ? null : t("list.tableView")}
              </button>
              <button
                type="button"
                onClick={() => setView("map")}
                className={cn(
                  "inline-flex h-7 items-center gap-1.5 rounded-control px-2.5 text-xs font-medium",
                  view === "map" ? "bg-card text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground"
                )}
                title={t("list.mapView")}
                aria-label={t("list.mapView")}
                aria-pressed={view === "map"}
              >
                <MapIcon className="size-4" />
                {compact ? null : t("list.mapView")}
              </button>
            </div>
          ) : null}
          {toolbarActions.map((a) => {
            const busy = pending.has(a.key);
            const iconOnly = compact && (!!a.icon || !!a.logo);
            return (
              <button
                key={a.key}
                type="button"
                onClick={() => runAction(a)}
                disabled={busy}
                className={cn(
                  "inline-flex h-8 shrink-0 items-center gap-1.5 whitespace-nowrap rounded-control bg-secondary text-xs font-medium text-foreground transition-colors hover:bg-accent disabled:cursor-not-allowed disabled:opacity-60",
                  iconOnly ? "w-8 justify-center px-0" : "px-3"
                )}
                title={a.label}
                aria-label={a.label}
              >
                {busy ? (
                  <Loader2 className="size-4 animate-spin" aria-hidden="true" />
                ) : a.logo ? (
                  <img src={a.logo} alt="" aria-hidden="true" className="size-4 shrink-0 object-contain" />
                ) : a.icon ? (
                  <DynamicLucide name={a.icon} size={16} />
                ) : null}
                {iconOnly ? null : a.label}
              </button>
            );
          })}
          {list.newUrl ? (
            <button
              type="button"
              onClick={() => dispatchAction(list.newUrl!)}
              className={cn(
                "inline-flex h-8 shrink-0 items-center gap-1.5 whitespace-nowrap rounded-control bg-primary text-xs font-medium text-primary-foreground transition-colors hover:bg-primary/90",
                compact ? "w-8 justify-center px-0" : "px-3"
              )}
              title={t("action.new")}
              aria-label={t("action.new")}
            >
              <Plus className="size-4" aria-hidden="true" />
              {compact ? null : t("action.new")}
            </button>
          ) : null}
        </div>
      </div>

      {mapMode ? (
        // Only catalog/document lists ever declare a map config, so mapMode is never true for a
        // register — narrow the kind for the map view, which pages from /api/list/{kind}/{name}.
        <div className={cn(surfaceMode && "min-h-0 flex-1")}>
          <ListMapView kind={kind as "catalogs" | "documents"} name={name} config={list.map!} />
        </div>
      ) : grouped ? (
        // Grouped view: collapsible GROUP BY headers with subtotals; a group's rows lazily load
        // through the same feed. Shares the toolbar's search/filters/sort via groupParamsBase.
        <GroupedList
          feedBase={feedBase}
          kind={kind}
          name={name}
          columns={columns}
          template={template}
          minTableWidth={minTableWidth}
          leftPad={leftPad}
          aggregates={aggregates}
          groupBy={groupBy}
          granularity={groupCol?.date ? granularity : ""}
          paramsBase={groupParamsBase}
          pageSize={pageSize}
          openable={openable}
          surfaceMode={surfaceMode}
          scrollCap={maxBodyH}
        />
      ) : (
      /* table card — one scroller for both axes; the header sticks to the top (so it scrolls
         horizontally in lock-step with the rows) and the inner min-width drives horizontal
         scroll when the card is narrower than the columns need. On a route surface the card flexes
         to fill the island's remaining height so only its body scrolls; embedded, it caps at maxBodyH. */
      <div className={cn(
        "flex flex-col overflow-hidden rounded-card border border-border bg-card",
        surfaceMode && "min-h-0 flex-1"
      )}>
        {/* the body scroller — the only thing that scrolls. Header sticks to its top; the inner
            min-width drives horizontal scroll when the card is narrower than the columns need. */}
        <div
          ref={scrollRef}
          onScroll={onScroll}
          className={cn("overflow-auto", surfaceMode && "min-h-0 flex-1")}
          style={surfaceMode ? undefined : { maxHeight: maxBodyH }}
        >
          <div style={{ minWidth: minTableWidth }}>
            {/* sticky header */}
            <div
              className={cn("sticky top-0 z-10 grid items-center gap-3 border-b border-border bg-card py-2.5", leftPad)}
              style={{ gridTemplateColumns: template }}
            >
              {columns.map((c) => {
                const active = sort.column === c.columnName;
                // The help "?" sits beside (not inside) the sort button — nesting buttons is
                // invalid HTML and would make the hint trigger a sort.
                return (
                  <div key={c.columnName} className="flex min-w-0 items-center gap-1">
                    <button
                      type="button"
                      onClick={() => toggleSort(c.columnName)}
                      className={cn(
                        "flex min-w-0 items-center gap-1 truncate text-left text-xs font-medium",
                        // The sorted column carries the brand (accent = state): which column orders
                        // the list. Inactive headers stay muted and brighten on hover.
                        active ? "text-primary" : "text-muted-foreground hover:text-foreground"
                      )}
                      title={t("list.sortBy", { column: c.label })}
                    >
                      <span className="truncate">{c.label}</span>
                      {active ? (
                        sort.descending ? <ArrowDown className="size-3 shrink-0" /> : <ArrowUp className="size-3 shrink-0" />
                      ) : (
                        <ChevronsUpDown className="size-3 shrink-0 opacity-30" />
                      )}
                    </button>
                    <HintIcon text={c.hint} size={13} />
                  </div>
                );
              })}
              {rowActions.length ? <span aria-hidden="true" /> : null}
            </div>

            {/* body — the virtual window in infinite mode (spacers stand in for off-screen rows);
                the whole page in paged mode. */}
            {pageRows != null && loadedRows.length === 0 ? (
              <div className="px-4 py-10 text-center text-sm text-muted-foreground">
                {debounced ? t("empty.noMatches") : t("empty.noRecords")}
              </div>
            ) : pageRows == null ? (
              // Loading placeholder — static bars (no pulse), one card-row each.
              Array.from({ length: Math.min(pageSize, 10) }).map((_, i) => (
                <div
                  key={i}
                  className={cn("grid items-center gap-3 border-b border-border/50", leftPad)}
                  style={{ minHeight: ROW_H, gridTemplateColumns: template }}
                >
                  {columns.map((c) => (
                    <span key={c.columnName} className="h-3.5 w-2/3 rounded bg-muted/60" />
                  ))}
                  {rowActions.length ? <span aria-hidden="true" /> : null}
                </div>
              ))
            ) : (
              // Pad above/below with the height of the rows outside the window (infinite mode);
              // paged mode renders every row (padTop/padBottom are 0).
              <div style={{ paddingTop: padTop, paddingBottom: padBottom }}>
                {visibleRows.map((row, i) => {
                  const absIdx = startIdx + i;
                  const url = openable ? `onno://${kind}/${name}/${row._id}` : undefined;
                  const rowViewers = openable ? viewersById.get(String(row._id)) : undefined;
                  return (
                    // Key by absolute row index: register (balance) rows have no _id, so an id-based
                    // key collides to the same value for every row and React mis-reconciles into
                    // ghost/duplicate rows. The loaded list is append-only per query, so the absolute
                    // index is a stable, correct key here.
                    <div
                      key={absIdx}
                      data-onno-row={url}
                      onClick={() => url && dispatchAction(url)}
                      className={cn(
                        // Hover highlight is owned by the [data-onno-row]:hover rule in index.css.
                        // Register rows aren't clickable (no detail route), so no pointer cursor.
                        "relative grid items-center gap-3 border-b border-border/50 text-sm",
                        url && "cursor-pointer",
                        leftPad,
                        absIdx % 2 === 1 && "bg-muted/20"
                      )}
                      style={{ minHeight: ROW_H, gridTemplateColumns: template }}
                    >
                      {columns.map((c) => (
                        <ListCell key={c.columnName} row={row} col={c} />
                      ))}
                      {rowViewers ? (
                        <div className="pointer-events-none absolute left-0 top-1/2 z-10 flex w-11 -translate-y-1/2 items-center justify-end pr-1.5">
                          <PresenceAvatars viewers={rowViewers} size={16} max={3} overlap />
                        </div>
                      ) : null}
                      {rowActions.length ? (
                        <div className="flex items-center justify-end gap-1">
                          {rowActions.map((a) => {
                            // State-aware row actions: the server attaches a per-row override
                            // (icon/label/visible/enabled) under `_actions`, computed from the row's data.
                            const st = (row._actions as Record<string, RowActionState> | undefined)?.[a.key];
                            if (st?.visible === false) return null;
                            const icon = st?.icon ?? a.icon;
                            const label = st?.label ?? a.label;
                            const busy = pending.has(`${a.key}:${row._id}`);
                            const disabled = busy || st?.enabled === false;
                            return (
                              <button
                                key={a.key}
                                type="button"
                                disabled={disabled}
                                onClick={(e) => {
                                  e.stopPropagation();
                                  runAction(a, String(row._id));
                                }}
                                className="inline-flex size-7 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground disabled:cursor-not-allowed disabled:opacity-60"
                                title={label}
                                aria-label={label}
                              >
                                {busy ? (
                                  <Loader2 className="size-[15px] animate-spin" aria-hidden="true" />
                                ) : a.logo ? (
                                  <img src={a.logo} alt="" aria-hidden="true" className="size-[15px] shrink-0 object-contain" />
                                ) : (
                                  <DynamicLucide name={icon || "zap"} size={15} />
                                )}
                              </button>
                            );
                          })}
                        </div>
                      ) : null}
                    </div>
                  );
                })}
              </div>
            )}
            {/* infinite mode: a slim "loading more" row while the next window is in flight */}
            {feedMode === "infinite" && loadingMore ? (
              <div className="flex items-center justify-center gap-2 border-t border-border/50 py-3 text-xs text-muted-foreground">
                <Loader2 className="size-4 animate-spin" aria-hidden="true" />
                {t("list.loadingMore")}
              </div>
            ) : null}
          </div>
        </div>
        {/* pager footer — paged mode only, when there's more than one page (infinite mode scrolls
            for more; the toolbar already shows the count). */}
        {paged && total != null && total > pageSize ? (
          <div className="flex items-center justify-between gap-3 border-t border-border bg-card px-4 py-2.5 text-xs text-muted-foreground">
            <span className="tabular-nums">{t("list.pageRange", { from: rangeFrom, to: rangeTo, total })}</span>
            <div className="flex items-center gap-1.5">
              <button
                type="button"
                disabled={page <= 0}
                onClick={() => setPage((p) => Math.max(0, p - 1))}
                className="inline-flex h-8 items-center gap-1 rounded-md border border-input bg-card px-2.5 font-medium text-foreground hover:bg-accent disabled:cursor-not-allowed disabled:opacity-40"
                aria-label={t("list.prev")}
              >
                <ChevronLeft className="size-4" />
                {compact ? null : t("list.prev")}
              </button>
              <span className="px-1.5 tabular-nums">{t("list.pageOf", { page: page + 1, pages: pageCount })}</span>
              <button
                type="button"
                disabled={page >= pageCount - 1}
                onClick={() => setPage((p) => Math.min(pageCount - 1, p + 1))}
                className="inline-flex h-8 items-center gap-1 rounded-md border border-input bg-card px-2.5 font-medium text-foreground hover:bg-accent disabled:cursor-not-allowed disabled:opacity-40"
                aria-label={t("list.next")}
              >
                {compact ? null : t("list.next")}
                <ChevronRight className="size-4" />
              </button>
            </div>
          </div>
        ) : null}
      </div>
      )}
    </div>
  );
}
