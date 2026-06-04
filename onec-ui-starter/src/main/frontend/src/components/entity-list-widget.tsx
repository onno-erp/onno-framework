import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import { ArrowDown, ArrowUp, ChevronsUpDown, Plus, Search } from "lucide-react";
import { toast } from "sonner";
import { Input } from "@/components/ui/input";
import { DynamicLucide } from "@/lib/icon-bridge";
import { api } from "@/lib/api";
import { cn } from "@/lib/utils";
import type { EntityRecord, UiEvent } from "@/lib/types";

/**
 * The {@code onec-list} React island: a virtualized, server-paged data grid. The DivKit list
 * surface emits only a descriptor (columns, sort, searchability, the open/New routes); this
 * island fetches windows of rows from {@code /api/list/...} as you scroll, sorts and searches
 * on the server, and renders only the visible rows — so a 10k-row entity stays smooth. Rows carry
 * {@code data-onec-row} so the existing right-click Open/Edit/Duplicate menu keeps working.
 */

export type ListColumn = { columnName: string; label: string; width: string };
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
export type ListDescriptor = {
  kind: "catalogs" | "documents";
  name: string;
  title: string;
  columns: ListColumn[];
  searchable: boolean;
  sort: { column: string | null; descending: boolean };
  newUrl: string | null;
  actions?: ListAction[];
  pageSize: number;
};

const ROW_H = 40;
const OVERSCAN = 8;

function dispatchAction(url: string) {
  window.dispatchEvent(new CustomEvent("onec:action", { detail: url }));
}

function cellValue(row: EntityRecord, col: ListColumn): string {
  if (col.columnName === "_posted") return row["_posted"] === true ? "Posted" : "Draft";
  const v = row[`${col.columnName}_display`] ?? row[col.columnName];
  if (v == null) return "";
  const s = String(v);
  if (s.startsWith("data:")) return "🖼 Image";
  if (s === "__SECRET_SET__") return "•••• set";
  return s;
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

export function EntityListWidget({ list }: { list: ListDescriptor }) {
  const { kind, name, columns, pageSize } = list;
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const rows = useRef<Map<number, EntityRecord>>(new Map());
  const loadedPages = useRef<Set<number>>(new Set());
  const inflight = useRef<Set<number>>(new Set());
  const gen = useRef(0); // bumped on sort/search/refresh so stale fetches are ignored

  const [total, setTotal] = useState<number | null>(null);
  const [, force] = useState(0);
  const rerender = useCallback(() => force((n) => n + 1), []);
  const [scrollTop, setScrollTop] = useState(0);
  const [bodyH, setBodyH] = useState(420);
  const [sort, setSort] = useState<{ column: string | null; descending: boolean }>(list.sort);
  const [query, setQuery] = useState("");
  const [debounced, setDebounced] = useState("");

  const allActions = list.actions ?? [];
  const toolbarActions = allActions.filter((a) => a.scope === "toolbar");
  const rowActions = allActions.filter((a) => a.scope === "row");

  // Column grid template: an authored px width, else a flexible min/auto column. A trailing
  // fixed column holds the per-row action buttons when any are declared.
  const template =
    columns
      .map((c) => {
        const px = parseInt(c.width, 10);
        return Number.isFinite(px) && px > 0 ? `${px}px` : "minmax(120px,1fr)";
      })
      .concat(rowActions.length ? [`${rowActions.length * 36 + 8}px`] : [])
      .join(" ");

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
    [kind, name, pageSize, sort.column, sort.descending, debounced, rerender]
  );

  // Reset and reload from the top whenever the sort or the (debounced) search changes.
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
  }, [sort.column, sort.descending, debounced]);

  // Size the scroll viewport to fill from its top to the bottom of the window.
  useLayoutEffect(() => {
    const measure = () => {
      const el = scrollRef.current;
      if (!el) return;
      const top = el.getBoundingClientRect().top;
      setBodyH(Math.max(160, Math.floor(window.innerHeight - top - 16)));
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
  // (api.runAction is CSRF-aware and already toasts failures.)
  const runAction = useCallback(
    (action: ListAction, id?: string) => {
      if (!action.server) {
        if (action.url) dispatchAction(id ? action.url.replace("{id}", id) : action.url);
        return;
      }
      api
        .runAction(action.kind, action.name, action.key, id)
        .then((result) => {
          if (result?.message) toast.success(result.message);
          if (result?.navigate) dispatchAction(result.navigate);
          if (result?.refresh) reload();
        })
        .catch(() => {});
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

  const toggleSort = (col: string) => {
    setSort((s) => (s.column === col ? { column: col, descending: !s.descending } : { column: col, descending: false }));
  };

  const visible: number[] = [];
  for (let i = startIndex; i < endIndex; i++) visible.push(i);

  return (
    <div className="flex flex-col px-4 py-4 sm:px-6">
      {/* toolbar */}
      <div className="mb-3 flex items-center gap-3">
        <div className="min-w-0">
          <h1 className="truncate text-xl font-semibold text-foreground">{list.title}</h1>
          <p className="text-xs text-muted-foreground">{total == null ? "…" : `${total} ${total === 1 ? "row" : "rows"}`}</p>
        </div>
        <div className="ml-auto flex items-center gap-2">
          {list.searchable ? (
            <div className="relative">
              <Search className="pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
              <Input
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="Search…"
                className="h-9 w-44 pl-8 sm:w-60"
              />
            </div>
          ) : null}
          {toolbarActions.map((a) => (
            <button
              key={a.key}
              type="button"
              onClick={() => runAction(a)}
              className="inline-flex h-9 items-center gap-1.5 rounded-lg bg-secondary px-3 text-sm font-medium text-foreground transition-colors hover:bg-accent"
              title={a.label}
            >
              {a.icon ? <DynamicLucide name={a.icon} size={16} /> : null}
              {a.label}
            </button>
          ))}
          {list.newUrl ? (
            <button
              type="button"
              onClick={() => dispatchAction(list.newUrl!)}
              className="inline-flex h-9 items-center gap-1.5 rounded-lg bg-secondary px-3 text-sm font-medium text-foreground transition-colors hover:bg-accent"
            >
              <Plus className="size-4" aria-hidden="true" />
              New
            </button>
          ) : null}
        </div>
      </div>

      {/* table card */}
      <div className="overflow-hidden rounded-2xl border border-border bg-card">
        {/* header */}
        <div
          className="grid items-center gap-3 border-b border-border px-4 py-2.5"
          style={{ gridTemplateColumns: template }}
        >
          {columns.map((c) => {
            const active = sort.column === c.columnName;
            return (
              <button
                key={c.columnName}
                type="button"
                onClick={() => toggleSort(c.columnName)}
                className="flex items-center gap-1 truncate text-left text-xs font-medium text-muted-foreground transition-colors hover:text-foreground"
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
        <div ref={scrollRef} className="overflow-auto" style={{ height: bodyH }} onScroll={(e) => setScrollTop(e.currentTarget.scrollTop)}>
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
                      "absolute left-0 right-0 grid cursor-pointer items-center gap-3 px-4 text-sm transition-colors hover:bg-muted/50",
                      i % 2 === 1 && "bg-muted/20"
                    )}
                    style={{ top: i * ROW_H, height: ROW_H, gridTemplateColumns: template }}
                  >
                    {row
                      ? columns.map((c) => (
                          <span key={c.columnName} className="truncate text-foreground">
                            {cellValue(row, c)}
                          </span>
                        ))
                      : columns.map((c) => (
                          <span key={c.columnName} className="h-3.5 w-2/3 animate-pulse rounded bg-muted" />
                        ))}
                    {rowActions.length ? (
                      <div className="flex items-center justify-end gap-1">
                        {row
                          ? rowActions.map((a) => (
                              <button
                                key={a.key}
                                type="button"
                                onClick={(e) => {
                                  e.stopPropagation();
                                  runAction(a, String(row._id));
                                }}
                                className="inline-flex size-7 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
                                title={a.label}
                                aria-label={a.label}
                              >
                                <DynamicLucide name={a.icon || "zap"} size={15} />
                              </button>
                            ))
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
  );
}
