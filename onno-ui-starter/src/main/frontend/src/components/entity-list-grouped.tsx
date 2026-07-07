import { useCallback, useEffect, useState } from "react";
import { ChevronDown, ChevronRight, Loader2 } from "lucide-react";
import { cn, enumPillStyle } from "@/lib/utils";
import { applyFormat } from "@/lib/cell-format";
import type { EntityRecord } from "@/lib/types";
import { useMessages } from "@/providers/messages-provider";
import { ListCell, ROW_H, type ListAggregate, type ListColumn } from "@/components/entity-list-widget";

/** One expand filter param a group header carries; the flat feed replays it to load the group's rows. */
type ExpandParam = { op: string; column: string; value: string };
/** One group header from `/groups`: its label (+ optional enum colour), row count, subtotals, expand. */
type GroupHeader = {
  label: string;
  color?: string;
  count: number;
  values: (number | string | null)[];
  expand: ExpandParam[];
};
/** Per-group lazy row state: the loaded rows, the cursor for the next window, and load flags. */
type GroupRows = { rows: EntityRecord[]; cursor: string | null; hasMore: boolean; loading: boolean };

function dispatchAction(url: string) {
  window.dispatchEvent(new CustomEvent("onno:action", { detail: url }));
}

/**
 * The grouped rendering of a list: one collapsible header per {@code GROUP BY} value (or date
 * bucket) fetched from {@code /api/list/{kind}/{name}/groups}, each showing the group's row count and
 * any declared subtotals. Expanding a header lazily loads that group's rows through the normal feed —
 * the server hands each group the exact filter (an {@code eq}, or a {@code ge}/{@code le} range for a
 * date bucket) to replay — and a "Show more" appends the next window. The same search/filters/sort as
 * the flat list are carried in {@code paramsBase}, so grouping narrows the same result set.
 */
export function GroupedList({
  feedBase,
  kind,
  name,
  columns,
  template,
  minTableWidth,
  leftPad,
  aggregates,
  groupBy,
  granularity,
  paramsBase,
  pageSize,
  openable,
  canWrite,
  surfaceMode,
  scrollCap,
}: {
  feedBase: string;
  kind: string;
  name: string;
  columns: ListColumn[];
  template: string;
  minTableWidth: number;
  leftPad: string;
  aggregates: ListAggregate[];
  groupBy: string;
  granularity: string;
  paramsBase: string;
  pageSize: number;
  openable: boolean;
  /** Viewer's RBAC write access — stamped onto rows so the shell's fallback row menu hides write actions. */
  canWrite: boolean;
  surfaceMode: boolean;
  scrollCap: number;
}) {
  const t = useMessages();
  const [groups, setGroups] = useState<GroupHeader[] | null>(null);
  const [capped, setCapped] = useState(false);
  // Loaded rows per expanded group, keyed by group index; absent = collapsed.
  const [expanded, setExpanded] = useState<Record<number, GroupRows>>({});
  const aggSig = JSON.stringify(aggregates);

  // (Re)fetch the group headers whenever the grouping, granularity, or the shared query changes.
  useEffect(() => {
    let alive = true;
    setGroups(null);
    setExpanded({});
    const p = new URLSearchParams(paramsBase);
    p.set("groupBy", groupBy);
    if (granularity) p.set("granularity", granularity);
    for (const a of aggregates) p.append("agg", `${a.fn},${a.columnName}`);
    fetch(`${feedBase}/groups?${p.toString()}`, { credentials: "include" })
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
      .then((data: { groups: GroupHeader[]; capped: boolean }) => {
        if (!alive) return;
        setGroups(data.groups ?? []);
        setCapped(!!data.capped);
      })
      .catch(() => {
        if (alive) {
          setGroups([]);
          setCapped(false);
        }
      });
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [feedBase, groupBy, granularity, paramsBase, aggSig]);

  // Fetch a window of a group's rows (first window or, with a cursor, the next) and splice it in.
  const loadGroupRows = useCallback(
    (index: number, group: GroupHeader, cursor: string | null) => {
      const p = new URLSearchParams(paramsBase);
      for (const e of group.expand) p.append(e.op, `${e.column},${e.value}`);
      p.set("limit", String(pageSize));
      if (cursor) p.set("cursor", cursor);
      fetch(`${feedBase}?${p.toString()}`, { credentials: "include" })
        .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
        .then((data: { rows: EntityRecord[]; nextCursor: string | null; hasMore: boolean }) => {
          setExpanded((prev) => {
            const cur = prev[index];
            if (!cur) return prev; // collapsed while the fetch was in flight
            return {
              ...prev,
              [index]: {
                rows: cursor ? [...cur.rows, ...(data.rows ?? [])] : data.rows ?? [],
                cursor: data.nextCursor ?? null,
                hasMore: !!data.hasMore,
                loading: false,
              },
            };
          });
        })
        .catch(() =>
          setExpanded((prev) => {
            const cur = prev[index];
            return cur ? { ...prev, [index]: { ...cur, loading: false, hasMore: false } } : prev;
          })
        );
    },
    [feedBase, paramsBase, pageSize]
  );

  const toggle = useCallback(
    (index: number, group: GroupHeader) => {
      if (group.expand.length === 0) return; // a null group isn't expandable
      setExpanded((prev) => {
        if (prev[index]) {
          const next = { ...prev };
          delete next[index];
          return next;
        }
        loadGroupRows(index, group, null);
        return { ...prev, [index]: { rows: [], cursor: null, hasMore: true, loading: true } };
      });
    },
    [loadGroupRows]
  );

  return (
    <div
      className={cn(
        // On a route surface the card sizes to its groups and only shrinks (min-h-0, no grow)
        // when they overflow — a short list ends at its last row, not the window bottom.
        "flex flex-col overflow-hidden rounded-card border border-border bg-card",
        surfaceMode && "min-h-0"
      )}
    >
      <div
        className={cn("overflow-auto", surfaceMode && "min-h-0 flex-1")}
        style={surfaceMode ? undefined : { maxHeight: scrollCap }}
      >
        <div style={{ minWidth: minTableWidth }}>
          {/* column labels (non-interactive in grouped view — sort still comes from the toolbar) */}
          <div
            className={cn("sticky top-0 z-10 grid items-center gap-3 border-b border-border bg-card py-2.5", leftPad)}
            style={{ gridTemplateColumns: template }}
          >
            {columns.map((c) => (
              <span key={c.columnName} className="truncate text-xs font-medium text-muted-foreground">
                {c.label}
              </span>
            ))}
          </div>

          {groups == null ? (
            Array.from({ length: 6 }).map((_, i) => (
              <div key={i} className={cn("border-b border-border/50 py-3", leftPad)}>
                <span className="block h-3.5 w-40 rounded bg-muted/60" />
              </div>
            ))
          ) : groups.length === 0 ? (
            <div className="px-4 py-10 text-center text-sm text-muted-foreground">{t("empty.noRecords")}</div>
          ) : (
            groups.map((g, i) => {
              const state = expanded[i];
              const open = !!state;
              const expandable = g.expand.length > 0;
              const pill = enumPillStyle(g.color);
              return (
                // Each group is its own block so the sticky band's containing block ends with the
                // group — the band un-sticks (gets pushed out by the next band) when its rows
                // scroll past, instead of every band piling up at the sticky offset forever.
                <div key={i}>
                  {/* group header band */}
                  <button
                    type="button"
                    onClick={() => toggle(i, g)}
                    disabled={!expandable}
                    className={cn(
                      // The band sticks below the column header, so rows scroll underneath it — its
                      // background must be opaque or they show through. color-mix bakes the old
                      // muted/40 (and /70 hover) tints over the card color into solid colors.
                      "flex w-full items-center gap-2 border-b border-border/60 px-4 py-2 text-left text-sm",
                      "bg-[color-mix(in_srgb,hsl(var(--muted))_40%,hsl(var(--card)))]",
                      "hover:bg-[color-mix(in_srgb,hsl(var(--muted))_70%,hsl(var(--card)))]",
                      "disabled:cursor-default disabled:hover:bg-[color-mix(in_srgb,hsl(var(--muted))_40%,hsl(var(--card)))]",
                      // 1px under the 37px column header (which sits above at z-10), so no slit of
                      // scrolling row pixels shows between the two sticky layers.
                      "sticky top-[36px] z-[9]"
                    )}
                    style={{ minHeight: ROW_H }}
                  >
                    {expandable ? (
                      open ? (
                        <ChevronDown className="size-4 shrink-0 text-muted-foreground" />
                      ) : (
                        <ChevronRight className="size-4 shrink-0 text-muted-foreground" />
                      )
                    ) : (
                      <span className="size-4 shrink-0" />
                    )}
                    {pill && g.label ? (
                      <span className="truncate rounded-control px-2 py-0.5 text-xs font-medium" style={pill}>
                        {g.label}
                      </span>
                    ) : (
                      <span className="truncate font-medium text-foreground">{g.label || t("list.all")}</span>
                    )}
                    <span className="shrink-0 rounded-control bg-background px-2 py-0.5 text-xs tabular-nums text-muted-foreground">
                      {g.count}
                    </span>
                    {aggregates.length ? (
                      // Anchored next to the count (not far-right) so it stays visible without
                      // horizontally scrolling a wide table.
                      <span className="flex shrink-0 items-center gap-3 pl-2 text-xs text-muted-foreground">
                        {aggregates.map((a, ai) => {
                          const raw = g.values[ai];
                          if (raw == null) return null;
                          const shown = applyFormat(String(raw), a.format) ?? String(raw);
                          return (
                            <span key={a.columnName + ai} className="tabular-nums">
                              <span className="opacity-70">{a.label}:</span> {shown}
                            </span>
                          );
                        })}
                      </span>
                    ) : null}
                  </button>

                  {/* the group's rows, lazily loaded */}
                  {open && state.rows.map((row, ri) => {
                    const url = openable ? `onno://${kind}/${name}/${row._id}` : undefined;
                    return (
                      <div
                        key={ri}
                        data-onno-row={url}
                        // "0" hides write actions in the shell's fallback row menu / shortcuts.
                        data-onno-row-writable={canWrite ? undefined : "0"}
                        onClick={() => url && dispatchAction(url)}
                        className={cn(
                          "grid items-center gap-3 border-b border-border/50 text-sm",
                          url && "cursor-pointer",
                          leftPad,
                          ri % 2 === 1 && "bg-muted/20"
                        )}
                        style={{ minHeight: ROW_H, gridTemplateColumns: template }}
                      >
                        {columns.map((c) => (
                          <ListCell key={c.columnName} row={row} col={c} />
                        ))}
                      </div>
                    );
                  })}
                  {open && state.loading ? (
                    <div className={cn("flex items-center gap-2 border-b border-border/50 py-2.5 text-xs text-muted-foreground", leftPad)}>
                      <Loader2 className="size-4 animate-spin" /> {t("list.loadingMore")}
                    </div>
                  ) : open && state.hasMore ? (
                    <button
                      type="button"
                      onClick={() => {
                        setExpanded((prev) => (prev[i] ? { ...prev, [i]: { ...prev[i], loading: true } } : prev));
                        loadGroupRows(i, g, state.cursor);
                      }}
                      className={cn("w-full border-b border-border/50 py-2.5 text-left text-xs font-medium text-primary hover:underline", leftPad)}
                    >
                      {t("list.showMore")}
                    </button>
                  ) : null}
                </div>
              );
            })
          )}
        </div>
      </div>
      {capped ? (
        <div className="border-t border-border bg-card px-4 py-2 text-xs text-muted-foreground">
          {t("list.groupsCapped")}
        </div>
      ) : null}
    </div>
  );
}
