import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { ArrowDown, ArrowUp, ChevronDown, ChevronLeft, ChevronRight, ChevronsUpDown, Loader2, Map as MapIcon, Plus, Search, Table2 } from "lucide-react";
import { toast } from "sonner";
import { ListMapView, type ListMapConfig } from "@/components/list-map-view";
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
import { Checkbox } from "@/components/ui/checkbox";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { DatePicker } from "@/components/date-picker";
import { HintIcon } from "@/components/ui/hint-icon";
import { DynamicLucide } from "@/lib/icon-bridge";
import { api } from "@/lib/api";
import { cn } from "@/lib/utils";
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

const ROW_H = 40;
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
function ListCell({ row, col }: { row: EntityRecord; col: ListColumn }) {
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
            avatar ? "w-7 rounded-full" : "w-10 rounded"
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
 * A multi-select filter control: a popover of checkboxes over the declared options. The trigger
 * summarizes the selection ("All" → "3 selected") so a long list of picks doesn't overflow the
 * toolbar. The picked values are sent as repeated `in` params → `column IN (…)`.
 */
function MultiOptionsFilter({
  options,
  selected,
  onChange,
}: {
  options: FilterOption[];
  selected: string[];
  onChange: (next: string[]) => void;
}) {
  // The popover renders labels but the selection (and the query) is keyed by value.
  const labelOf = (value: string) => options.find((o) => o.value === value)?.label ?? value;
  const summary =
    selected.length === 0
      ? "All"
      : selected.length === 1
        ? labelOf(selected[0])
        : `${selected.length} selected`;
  const toggle = (value: string) =>
    onChange(selected.includes(value) ? selected.filter((v) => v !== value) : [...selected, value]);
  return (
    <Popover>
      <PopoverTrigger asChild>
        <button
          type="button"
          className="flex h-9 w-36 items-center justify-between gap-1 rounded-md border border-input bg-muted px-3 text-sm text-foreground"
        >
          <span className="truncate">{summary}</span>
          <ChevronDown className="h-4 w-4 shrink-0 opacity-50" />
        </button>
      </PopoverTrigger>
      <PopoverContent align="start" className="max-h-72 w-56 overflow-y-auto p-1">
        {selected.length > 0 ? (
          <button
            type="button"
            className="mb-1 w-full rounded px-2 py-1 text-left text-xs text-muted-foreground hover:bg-accent"
            onClick={() => onChange([])}
          >
            Clear
          </button>
        ) : null}
        {options.map((o) => (
          <label
            key={o.value}
            className="flex cursor-pointer items-center gap-2 rounded px-2 py-1.5 text-sm hover:bg-accent"
          >
            <Checkbox checked={selected.includes(o.value)} onCheckedChange={() => toggle(o.value)} />
            <span className="truncate">{o.label}</span>
          </label>
        ))}
      </PopoverContent>
    </Popover>
  );
}

/**
 * A field-scoped typeahead filter: a debounced text input. Keystrokes update the input immediately
 * but only commit (and re-query) after a short pause, so a contains/starts-with filter over a
 * high-cardinality column doesn't refetch on every character.
 */
function ContainsFilter({
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
    <Input
      value={text}
      placeholder={label}
      onChange={(e) => setText(e.target.value)}
      className="h-9 w-36"
    />
  );
}

export function EntityListWidget({ list }: { list: ListDescriptor }) {
  const { kind, name, columns, pageSize } = list;
  const t = useMessages();
  // Ambient presence: other users viewing each row's record, looked up by row id at render time.
  const viewersById = useViewersById();
  const rootRef = useRef<HTMLDivElement | null>(null);
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const gen = useRef(0); // bumped on each query so a stale page fetch is ignored

  // Explicit pagination: one page of rows at a time (null = still loading), the live total, and the
  // 0-based page index. No virtualization — a page is small enough to render whole, and discrete
  // pages make "there's more" obvious (the old infinite scroll didn't).
  const [pageRows, setPageRows] = useState<EntityRecord[] | null>(null);
  const [total, setTotal] = useState<number | null>(null);
  const [page, setPage] = useState(0);
  // Cap the scroll body at the space to the window bottom (minus room for the pager footer); a page
  // taller than that scrolls inside the cap so the footer stays visible.
  const [maxBodyH, setMaxBodyH] = useState(420);
  // The island can be squeezed narrow (e.g. a master-detail split) while the viewport stays wide,
  // so the toolbar layout is driven by the measured container width, not a media query.
  const [toolbarWidth, setToolbarWidth] = useState<number | null>(null);
  const [sort, setSort] = useState<{ column: string | null; descending: boolean }>(list.sort);
  const [query, setQuery] = useState("");
  const [debounced, setDebounced] = useState("");
  // Keys of in-flight server actions (`key` for toolbar, `key:id` for a row) → spinner + disabled.
  const [pending, setPending] = useState<Set<string>>(new Set());
  // Table vs map view (only offered when the list declares a map config). The map fetches its own
  // rows, so the grid's search/filters/sort are hidden while it's shown.
  const [view, setView] = useState<"table" | "map">(list.map?.defaultView ? "map" : "table");
  const mapMode = !!list.map && view === "map";
  // Catalog/document rows open their record; register rows (movements/balance) have no detail route,
  // so they aren't clickable. Also gates the single-row live patch (registers have no row identity).
  const openable = kind === "catalogs" || kind === "documents";
  // Where pages are fetched from: a register points the island at its own feed; others use the
  // standard /api/list/{kind}/{name} route.
  const feedBase = list.feed ?? `/api/list/${kind}/${name}`;

  const allActions = list.actions ?? [];
  const toolbarActions = allActions.filter((a) => a.scope === "toolbar");
  const rowActions = allActions.filter((a) => a.scope === "row");
  const toolbarInputs = list.inputs ?? [];
  // Stable reference so the fetch/reset effects don't churn when there are no filters.
  const filters = useMemo(() => list.filters ?? [], [list.filters]);

  // Current value of each declarative filter: {eq} for an options filter, {in} (picked values) for a
  // multi-select, {text} for a contains/starts-with typeahead, {from,to} for a date range. Changing
  // one resets the grid and re-queries (see the reset effect below) — it narrows the rows, not just
  // the toolbar. Serialized into filterSig so effects can depend on its content.
  const [filterState, setFilterState] = useState<
    Record<string, { eq?: string; in?: string[]; text?: string; from?: string; to?: string }>
  >(() => Object.fromEntries(filters.map((f) => [f.key, {}])));
  const filterSig = JSON.stringify(filterState);
  const filterStateRef = useRef(filterState);
  filterStateRef.current = filterState;

  // Responsive toolbar, driven by the measured island width and the actual controls present
  // (not a fixed media query). `stacked` puts the title on its own row and the controls below
  // the moment they would no longer fit beside it — so the title never truncates mid-toolbar.
  // `compact` (much narrower) additionally drops the button labels to icons.
  const controlsWidthEstimate =
    filters.reduce((w, f) => w + (f.type === "dateRange" ? 320 : 200), 0) +
    toolbarInputs.length * 210 +
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

  // Fetch one page (the sole data path now). Bumps the generation so a slow page that lands after a
  // newer query/page request is discarded. Sort/search/filters are applied server-side, same as before.
  const loadPage = useCallback(
    (p: number, soft = false) => {
      const myGen = ++gen.current;
      // A hard load (first paint, query change, page turn) blanks to the skeleton; a soft load (a
      // live refresh after some entity changed) keeps the current rows on screen and swaps them in
      // place when the new data lands — so an unaffected register doesn't flash empty on every post.
      if (!soft) setPageRows(null);
      const params = new URLSearchParams({
        offset: String(p * pageSize),
        limit: String(pageSize),
      });
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
    },
    [feedBase, pageSize, sort.column, sort.descending, debounced, filters, filterSig]
  );

  // Snap back to the first page whenever the sort, the (debounced) search, or a filter changes.
  useEffect(() => {
    setPage(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sort.column, sort.descending, debounced, filterSig]);

  // Fetch the active page whenever it (or the query, via loadPage's identity) changes.
  useEffect(() => {
    loadPage(page);
  }, [page, loadPage]);

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

  // Cap the scroll body at the space from its top to the window bottom, leaving room for the pager
  // footer (~56px) so it never gets pushed off-screen.
  useLayoutEffect(() => {
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
  }, []);

  // Re-fetch the current page in place (after a server action or a live event) — a soft refresh
  // that keeps the visible rows until the new data lands, so it never flashes the skeleton.
  const reload = useCallback(() => {
    loadPage(page, true);
  }, [loadPage, page]);

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

  // Pager math: how many pages, and the 1-based "from–to of total" range for the current page.
  const pageCount = total == null ? 1 : Math.max(1, Math.ceil(total / pageSize));
  const rangeFrom = total ? page * pageSize + 1 : 0;
  const rangeTo = total == null ? 0 : Math.min(total, page * pageSize + pageSize);
  // Clamp the page if the row count shrank under us (a filter/delete) and left us past the last page.
  useEffect(() => {
    if (total != null && page > 0 && page > pageCount - 1) setPage(pageCount - 1);
  }, [total, page, pageCount]);

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
    // Embedded in a page, the table aligns with the sibling cards (no horizontal gutter); as its own
    // route surface it carries the page gutter itself (the surface root has no content padding).
    <div
      ref={rootRef}
      className={cn(
        "pointer-events-auto flex flex-col py-4",
        list.embedded ? "" : "px-4 sm:px-6"
      )}
    >
      {/* toolbar — stacks (title over controls) the moment the controls won't fit beside it */}
      <div className={cn("mb-3 flex gap-x-3 gap-y-2", stacked ? "flex-col items-stretch" : "items-center")}>
        <div className="min-w-0">
          <h1 className="truncate text-xl font-semibold text-foreground">{list.title}</h1>
          <p className="whitespace-nowrap text-xs text-muted-foreground">
            {total == null ? "…" : t("list.count", { count: total })}
          </p>
        </div>
        <div className={cn("flex flex-wrap items-center gap-2", stacked ? "justify-start" : "ml-auto justify-end")}>
          {list.map ? (
            <div className="inline-flex h-9 shrink-0 items-center rounded-lg border border-input bg-muted p-0.5">
              <button
                type="button"
                onClick={() => setView("table")}
                className={cn(
                  "inline-flex h-8 items-center gap-1.5 rounded-md px-2.5 text-sm font-medium",
                  view === "table"
                    ? "bg-card text-foreground shadow-sm"
                    : "text-muted-foreground hover:text-foreground"
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
                  "inline-flex h-8 items-center gap-1.5 rounded-md px-2.5 text-sm font-medium",
                  view === "map"
                    ? "bg-card text-foreground shadow-sm"
                    : "text-muted-foreground hover:text-foreground"
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
          {!mapMode && filters.map((f) => {
            // A date-range filter is two pickers. Each needs ~160px so the dd/mm/yyyy segments +
            // calendar icon stay on one line (w-36 squished them onto two). On a wide toolbar they
            // sit inline at that fixed width; on a stacked/narrow one (where two fixed pickers would
            // overflow) the label drops to its own line and the pickers share the full width.
            if (f.type === "dateRange") {
              return (
                <div
                  key={f.key}
                  className={cn(
                    "flex gap-1.5 text-xs text-muted-foreground",
                    stacked ? "w-full flex-col items-stretch" : "shrink-0 items-center"
                  )}
                >
                  <span className={cn("whitespace-nowrap", stacked ? "" : "self-center")}>{f.label}</span>
                  <div className="flex items-center gap-1">
                    <div className={cn(stacked ? "min-w-0 flex-1" : "w-40 shrink-0")}>
                      <DatePicker
                        value={filterState[f.key]?.from || undefined}
                        onChange={(val) =>
                          setFilterState((s) => ({ ...s, [f.key]: { ...s[f.key], from: val } }))
                        }
                      />
                    </div>
                    <span className="text-muted-foreground">–</span>
                    <div className={cn(stacked ? "min-w-0 flex-1" : "w-40 shrink-0")}>
                      <DatePicker
                        value={filterState[f.key]?.to || undefined}
                        onChange={(val) =>
                          setFilterState((s) => ({ ...s, [f.key]: { ...s[f.key], to: val } }))
                        }
                      />
                    </div>
                  </div>
                </div>
              );
            }
            if (f.type === "multiOptions") {
              return (
                <label key={f.key} className="flex shrink-0 items-center gap-1.5 text-xs text-muted-foreground">
                  <span className="whitespace-nowrap">{f.label}</span>
                  <MultiOptionsFilter
                    options={f.options}
                    selected={filterState[f.key]?.in ?? []}
                    onChange={(next) => setFilterState((s) => ({ ...s, [f.key]: { in: next } }))}
                  />
                </label>
              );
            }
            if (f.type === "contains" || f.type === "startsWith") {
              return (
                <label key={f.key} className="flex shrink-0 items-center gap-1.5 text-xs text-muted-foreground">
                  <span className="whitespace-nowrap">{f.label}</span>
                  <ContainsFilter
                    label={f.label}
                    value={filterState[f.key]?.text ?? ""}
                    onCommit={(next) => setFilterState((s) => ({ ...s, [f.key]: { text: next } }))}
                  />
                </label>
              );
            }
            return (
              <label key={f.key} className="flex shrink-0 items-center gap-1.5 text-xs text-muted-foreground">
                <span className="whitespace-nowrap">{f.label}</span>
                <Select
                  value={filterState[f.key]?.eq || SELECT_NONE}
                  onValueChange={(val) =>
                    setFilterState((s) => ({ ...s, [f.key]: { eq: val === SELECT_NONE ? "" : val } }))
                  }
                >
                  <SelectTrigger className="h-9 w-36">
                    <SelectValue placeholder={f.label} />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value={SELECT_NONE}>{t("list.all")}</SelectItem>
                    {f.options.map((o) => (
                      <SelectItem key={o.value} value={o.value}>
                        {o.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </label>
            );
          })}
          {!mapMode && toolbarInputs.map((inp) => (
            <label key={inp.key} className="flex shrink-0 items-center gap-1.5 text-xs text-muted-foreground">
              <span className="whitespace-nowrap">{inp.label}</span>
              {inp.type === "select" ? (
                <Select
                  value={inputValues[inp.key] ?? ""}
                  onValueChange={(val) =>
                    setInputValues((v) => ({ ...v, [inp.key]: val === SELECT_NONE ? "" : val }))
                  }
                >
                  <SelectTrigger className="h-9 w-36">
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
                  className="h-9 w-36"
                />
              )}
            </label>
          ))}
          {!mapMode && list.searchable ? (
            <div className={cn("relative", stacked ? "min-w-[8rem] flex-1" : "")}>
              <Search className="pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
              <Input
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder={t("list.search")}
                className={cn("h-9 pl-8", stacked ? "w-full" : "w-44 sm:w-60")}
              />
            </div>
          ) : null}
          {toolbarActions.map((a) => {
            const busy = pending.has(a.key);
            // Compact + has an icon → icon-only (label drops to the tooltip), so the button can't
            // shrink below its content and wrap its text per-character.
            const iconOnly = compact && (!!a.icon || !!a.logo);
            return (
              <button
                key={a.key}
                type="button"
                onClick={() => runAction(a)}
                disabled={busy}
                className={cn(
                  "inline-flex h-9 shrink-0 items-center gap-1.5 whitespace-nowrap rounded-lg bg-secondary text-sm font-medium text-foreground transition-colors hover:bg-accent disabled:cursor-not-allowed disabled:opacity-60",
                  iconOnly ? "w-9 justify-center px-0" : "px-3"
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
                // New is the surface's primary create action, so it carries the brand (neutral
                // near-black when unbranded); the sibling toolbar actions stay quiet/secondary.
                "inline-flex h-9 shrink-0 items-center gap-1.5 whitespace-nowrap rounded-lg bg-primary text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90",
                compact ? "w-9 justify-center px-0" : "px-3"
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
        <ListMapView kind={kind as "catalogs" | "documents"} name={name} config={list.map!} />
      ) : (
      /* table card — one scroller for both axes; the header sticks to the top (so it scrolls
         horizontally in lock-step with the rows) and the inner min-width drives horizontal
         scroll when the card is narrower than the columns need. */
      <div className="overflow-hidden rounded-2xl border border-border bg-card">
        {/* one scroller for both axes; the header sticks to the top; the inner min-width drives
            horizontal scroll when the card is narrower than the columns need. The body is capped at
            maxBodyH and scrolls inside, so the pager footer below always stays visible. */}
        <div ref={scrollRef} className="overflow-auto" style={{ maxHeight: maxBodyH }}>
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

            {/* body — one page of rows, rendered whole (the page is small; no virtualization) */}
            {total === 0 ? (
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
              pageRows.map((row, i) => {
                const url = openable ? `onno://${kind}/${name}/${row._id}` : undefined;
                const rowViewers = openable ? viewersById.get(String(row._id)) : undefined;
                return (
                  // Key by position within the page: register (balance) rows have no _id, so an
                  // id-based key collides to the same value for every row and React mis-reconciles
                  // into ghost/duplicate rows on reload. The page is a fresh fixed list each load,
                  // so the index is a stable, correct key here.
                  <div
                    key={i}
                    data-onno-row={url}
                    onClick={() => url && dispatchAction(url)}
                    className={cn(
                      // Hover highlight is owned by the [data-onno-row]:hover rule in index.css.
                      // Register rows aren't clickable (no detail route), so no pointer cursor.
                      "relative grid items-center gap-3 border-b border-border/50 text-sm last:border-b-0",
                      url && "cursor-pointer",
                      leftPad,
                      i % 2 === 1 && "bg-muted/20"
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
              })
            )}
          </div>
        </div>
        {/* pager footer — only when there's more than one page; the toolbar already shows the count */}
        {total != null && total > pageSize ? (
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
