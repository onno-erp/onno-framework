import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { ArrowDown, ArrowUp, ChevronDown, ChevronsUpDown, Loader2, Plus, Search } from "lucide-react";
import { toast } from "sonner";
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
import { DynamicLucide } from "@/lib/icon-bridge";
import { api } from "@/lib/api";
import { cn } from "@/lib/utils";
import { applyFormat, isImageWidget, isAvatarWidget, looksLikeImageUrl } from "@/lib/cell-format";
import type { EntityRecord, UiEvent } from "@/lib/types";

/**
 * The {@code onec-list} React island: a virtualized, server-paged data grid. The DivKit list
 * surface emits only a descriptor (columns, sort, searchability, the open/New routes); this
 * island fetches windows of rows from {@code /api/list/...} as you scroll, sorts and searches
 * on the server, and renders only the visible rows — so a 10k-row entity stays smooth. Rows carry
 * {@code data-onec-row} so the existing right-click Open/Edit/Duplicate menu keeps working.
 */

export type ListColumn = {
  columnName: string;
  label: string;
  width: string;
  /** Display hint: "image"/"avatar" renders the cell value as a thumbnail. */
  widget?: string;
  /** Display format: a date pattern ("dd-MM-yy") or number spec ("currency:EUR", "integer", …). */
  format?: string;
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
  scope: "toolbar" | "row";
  server: boolean;
  url?: string;
  kind: string;
  name: string;
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
export type ListFilterControl = {
  key: string;
  label: string;
  column: string;
  type: "options" | "multiOptions" | "contains" | "startsWith" | "dateRange";
  options: string[];
};
export type ListDescriptor = {
  kind: "catalogs" | "documents";
  name: string;
  title: string;
  columns: ListColumn[];
  searchable: boolean;
  sort: { column: string | null; descending: boolean };
  newUrl: string | null;
  actions?: ListAction[];
  inputs?: ListInput[];
  filters?: ListFilterControl[];
  pageSize: number;
  // Embedded in an authored page (PageBuilder.list) rather than rendered as its own route surface.
  // The page already pads its content, so the widget drops its horizontal gutter to align its table
  // with the page's other full-width components (constants editor, action sections).
  embedded?: boolean;
};

const ROW_H = 40;
const OVERSCAN = 8;
// Radix Select forbids an empty-string item value, so the "no selection" / placeholder choice
// uses this sentinel and is mapped back to "" in state.
const SELECT_NONE = "__onec_none__";

function dispatchAction(url: string) {
  window.dispatchEvent(new CustomEvent("onec:action", { detail: url }));
}

/** The underlying cell string: a resolved ref/enum label, the posted badge, or the raw value. */
function rawCellValue(row: EntityRecord, col: ListColumn): string {
  if (col.columnName === "_posted") return row["_posted"] === true ? "Posted" : "Draft";
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
  const raw = rawCellValue(row, col);
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
  return <span className="truncate text-foreground">{displayCellValue(raw, col)}</span>;
}

// Mirror of the server's snake-casing so an SSE event's entity name matches the route name.
function toSnake(name: string): string {
  return name.replace(/ /g, "").replace(/([a-z0-9])([A-Z])/g, "$1_$2").toLowerCase();
}
function eventMatches(event: UiEvent, kind: string, name: string): boolean {
  if (!event || event.type === "ready") return false;
  const singular = kind === "catalogs" ? "catalog" : "document";
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
  options: string[];
  selected: string[];
  onChange: (next: string[]) => void;
}) {
  const summary =
    selected.length === 0 ? "All" : selected.length === 1 ? selected[0] : `${selected.length} selected`;
  const toggle = (option: string) =>
    onChange(selected.includes(option) ? selected.filter((v) => v !== option) : [...selected, option]);
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
            key={o}
            className="flex cursor-pointer items-center gap-2 rounded px-2 py-1.5 text-sm hover:bg-accent"
          >
            <Checkbox checked={selected.includes(o)} onCheckedChange={() => toggle(o)} />
            <span className="truncate">{o}</span>
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
  const rootRef = useRef<HTMLDivElement | null>(null);
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const rows = useRef<Map<number, EntityRecord>>(new Map());
  const loadedPages = useRef<Set<number>>(new Set());
  const inflight = useRef<Set<number>>(new Set());
  const gen = useRef(0); // bumped on sort/search/refresh so stale fetches are ignored

  const [total, setTotal] = useState<number | null>(null);
  const [, force] = useState(0);
  const rerender = useCallback(() => force((n) => n + 1), []);
  const [scrollTop, setScrollTop] = useState(0);
  // The space from the scroller's top to the window bottom — the *most* the viewport may grow to.
  // The actual height hugs the content (see bodyH below), so a short list isn't a full-height card.
  const [maxBodyH, setMaxBodyH] = useState(420);
  // The island can be squeezed narrow (e.g. a master-detail split) while the viewport stays wide,
  // so the toolbar layout is driven by the measured container width, not a media query.
  const [toolbarWidth, setToolbarWidth] = useState<number | null>(null);
  const [sort, setSort] = useState<{ column: string | null; descending: boolean }>(list.sort);
  const [query, setQuery] = useState("");
  const [debounced, setDebounced] = useState("");
  // Keys of in-flight server actions (`key` for toolbar, `key:id` for a row) → spinner + disabled.
  const [pending, setPending] = useState<Set<string>>(new Set());

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

  const fetchPage = useCallback(
    (page: number) => {
      if (loadedPages.current.has(page) || inflight.current.has(page)) return;
      inflight.current.add(page);
      const myGen = gen.current;
      const params = new URLSearchParams({
        offset: String(page * pageSize),
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
      fetch(`/api/list/${kind}/${name}?${params.toString()}`, { credentials: "include" })
        .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
        .then((data: { total: number; offset: number; rows: EntityRecord[] }) => {
          if (myGen !== gen.current) return; // sort/search changed under us
          inflight.current.delete(page);
          loadedPages.current.add(page);
          setTotal(data.total);
          data.rows.forEach((row, i) => rows.current.set(data.offset + i, row));
          rerender();
        })
        .catch(() => {
          inflight.current.delete(page);
        });
    },
    [kind, name, pageSize, sort.column, sort.descending, debounced, filters, filterSig, rerender]
  );

  // Reset and reload from the top whenever the sort, the (debounced) search, or a filter changes.
  useEffect(() => {
    gen.current += 1;
    rows.current.clear();
    loadedPages.current.clear();
    inflight.current.clear();
    setTotal(null);
    if (scrollRef.current) scrollRef.current.scrollTop = 0;
    setScrollTop(0);
    fetchPage(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sort.column, sort.descending, debounced, filterSig]);

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

  // Size the scroll viewport to fill from its top to the bottom of the window.
  useLayoutEffect(() => {
    const measure = () => {
      const el = scrollRef.current;
      if (!el) return;
      const top = el.getBoundingClientRect().top;
      setMaxBodyH(Math.max(160, Math.floor(window.innerHeight - top - 16)));
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

  // Reload the visible window in place (keeps scroll position rather than jumping to the top).
  const reload = useCallback(() => {
    gen.current += 1;
    rows.current.clear();
    loadedPages.current.clear();
    inflight.current.clear();
    fetchPage(Math.floor(scrollTop / ROW_H / pageSize));
    rerender();
  }, [fetchPage, scrollTop, pageSize, rerender]);

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

  // Live updates: divkit-view fans out every SSE row change as an "onec:dataevent" window event
  // (one shared stream). A write to this entity reloads the visible window in place.
  useEffect(() => {
    const onData = (e: Event) => {
      const event = (e as CustomEvent).detail as UiEvent;
      if (!eventMatches(event, kind, name)) return;
      reload();
    };
    window.addEventListener("onec:dataevent", onData);
    return () => window.removeEventListener("onec:dataevent", onData);
  }, [kind, name, reload]);

  // Hug the content: the scroll viewport grows to fit the rows it actually holds, capped at the
  // space to the window bottom — so a 1-row list reads as a short card instead of a full-height one
  // that leaves most of the screen empty (#77). Beyond the cap it stays put and the rows scroll.
  // While the first page is loading (total unknown) it fills the available space for the skeleton.
  const HEADER_H = 41; // sticky header: py-2.5 + text-xs row + 1px border
  const EMPTY_BODY_H = 120; // the "No records" / "No matches" placeholder block
  const contentHeight = HEADER_H + (total === 0 ? EMPTY_BODY_H : (total ?? 0) * ROW_H);
  const bodyH = total == null ? maxBodyH : Math.min(maxBodyH, contentHeight);

  // Ensure every page covering the visible window is loaded.
  const startIndex = Math.max(0, Math.floor(scrollTop / ROW_H) - OVERSCAN);
  const endIndex = total == null
    ? Math.ceil(bodyH / ROW_H) + OVERSCAN
    : Math.min(total, Math.ceil((scrollTop + bodyH) / ROW_H) + OVERSCAN);
  useEffect(() => {
    const firstPage = Math.floor(startIndex / pageSize);
    const lastPage = Math.floor(Math.max(startIndex, endIndex - 1) / pageSize);
    for (let p = firstPage; p <= lastPage; p++) fetchPage(p);
  }, [startIndex, endIndex, pageSize, fetchPage]);

  // Cycle a column through three states on repeated clicks: ascending → descending → off
  // (off clears the sort entirely, so the list falls back to the server's default order).
  const toggleSort = (col: string) => {
    setSort((s) => {
      if (s.column !== col) return { column: col, descending: false }; // new column → ascending
      if (!s.descending) return { column: col, descending: true }; // ascending → descending
      return { column: null, descending: false }; // descending → off
    });
  };

  const visible: number[] = [];
  for (let i = startIndex; i < endIndex; i++) visible.push(i);

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
            {total == null ? "…" : `${total} ${total === 1 ? "row" : "rows"}`}
          </p>
        </div>
        <div className={cn("flex flex-wrap items-center gap-2", stacked ? "justify-start" : "ml-auto justify-end")}>
          {filters.map((f) => {
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
                    <SelectItem value={SELECT_NONE}>All</SelectItem>
                    {f.options.map((o) => (
                      <SelectItem key={o} value={o}>
                        {o}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </label>
            );
          })}
          {toolbarInputs.map((inp) => (
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
          {list.searchable ? (
            <div className={cn("relative", stacked ? "min-w-[8rem] flex-1" : "")}>
              <Search className="pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
              <Input
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="Search…"
                className={cn("h-9 pl-8", stacked ? "w-full" : "w-44 sm:w-60")}
              />
            </div>
          ) : null}
          {toolbarActions.map((a) => {
            const busy = pending.has(a.key);
            // Compact + has an icon → icon-only (label drops to the tooltip), so the button can't
            // shrink below its content and wrap its text per-character.
            const iconOnly = compact && !!a.icon;
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
              title="New"
              aria-label="New"
            >
              <Plus className="size-4" aria-hidden="true" />
              {compact ? null : "New"}
            </button>
          ) : null}
        </div>
      </div>

      {/* table card — one scroller for both axes; the header sticks to the top (so it scrolls
          horizontally in lock-step with the rows) and the inner min-width drives horizontal
          scroll when the card is narrower than the columns need. */}
      <div className="overflow-hidden rounded-2xl border border-border bg-card">
        <div ref={scrollRef} className="overflow-auto" style={{ height: bodyH }} onScroll={(e) => setScrollTop(e.currentTarget.scrollTop)}>
          <div style={{ minWidth: minTableWidth }}>
            {/* sticky header */}
            <div
              className="sticky top-0 z-10 grid items-center gap-3 border-b border-border bg-card px-4 py-2.5"
              style={{ gridTemplateColumns: template }}
            >
              {columns.map((c) => {
                const active = sort.column === c.columnName;
                return (
                  <button
                    key={c.columnName}
                    type="button"
                    onClick={() => toggleSort(c.columnName)}
                    className={cn(
                      "flex items-center gap-1 truncate text-left text-xs font-medium transition-colors",
                      // The sorted column carries the brand (accent = state): which column orders
                      // the list. Inactive headers stay muted and brighten on hover.
                      active ? "text-primary" : "text-muted-foreground hover:text-foreground"
                    )}
                    title={`Sort by ${c.label}`}
                  >
                    <span className="truncate">{c.label}</span>
                    {active ? (
                      sort.descending ? <ArrowDown className="size-3 shrink-0" /> : <ArrowUp className="size-3 shrink-0" />
                    ) : (
                      <ChevronsUpDown className="size-3 shrink-0 opacity-30" />
                    )}
                  </button>
                );
              })}
              {rowActions.length ? <span aria-hidden="true" /> : null}
            </div>

            {/* virtualized body */}
            {total === 0 ? (
              <div className="px-4 py-10 text-center text-sm text-muted-foreground">
                {debounced ? "No matches." : "No records."}
              </div>
            ) : (
              <div style={{ height: (total ?? 0) * ROW_H, position: "relative" }}>
                {visible.map((i) => {
                const row = rows.current.get(i);
                const url = row ? `onec://${kind}/${name}/${row._id}` : undefined;
                return (
                  <div
                    key={i}
                    data-onec-row={url}
                    onClick={() => url && dispatchAction(url)}
                    className={cn(
                      // Hover highlight is owned by the [data-onec-row]:hover rule in index.css
                      // (a brand-primary wash with !important, to beat DivKit's inline zebra bg);
                      // here we only set the resting alt-row stripe.
                      "absolute left-0 right-0 grid cursor-pointer items-center gap-3 px-4 text-sm transition-colors",
                      i % 2 === 1 && "bg-muted/20"
                    )}
                    style={{ top: i * ROW_H, height: ROW_H, gridTemplateColumns: template }}
                  >
                    {row
                      ? columns.map((c) => <ListCell key={c.columnName} row={row} col={c} />)
                      : columns.map((c) => (
                          <span key={c.columnName} className="h-3.5 w-2/3 animate-pulse rounded bg-muted" />
                        ))}
                    {rowActions.length ? (
                      <div className="flex items-center justify-end gap-1">
                        {row
                          ? rowActions.map((a) => {
                              const busy = pending.has(`${a.key}:${row._id}`);
                              return (
                                <button
                                  key={a.key}
                                  type="button"
                                  disabled={busy}
                                  onClick={(e) => {
                                    e.stopPropagation();
                                    runAction(a, String(row._id));
                                  }}
                                  className="inline-flex size-7 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-accent hover:text-foreground disabled:cursor-not-allowed disabled:opacity-60"
                                  title={a.label}
                                  aria-label={a.label}
                                >
                                  {busy ? (
                                    <Loader2 className="size-[15px] animate-spin" aria-hidden="true" />
                                  ) : (
                                    <DynamicLucide name={a.icon || "zap"} size={15} />
                                  )}
                                </button>
                              );
                            })
                          : null}
                      </div>
                    ) : null}
                  </div>
                );
              })}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
