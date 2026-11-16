import { useCallback, useEffect, useMemo, useState } from "react";
import { ChevronDown, History } from "lucide-react";
import { api, type HistoryChangeView, type HistoryEntryView } from "@/lib/api";
import type { UiEvent } from "@/lib/types";
import { formatTimestampDefault } from "@/lib/cell-format";
import { useSurfaceRefresh } from "@/lib/surface-refresh";
import { useMessages } from "@/providers/messages-provider";
import type { Translate } from "@/lib/messages";

export type HistoryTarget = { kind: "catalogs" | "documents"; name: string; id: string };

/** The display text of one side of a change: resolved ref/enum display, else the typed raw value. */
function valueText(change: HistoryChangeView, side: "old" | "new", t: Translate): string | null {
  const display = side === "old" ? change.oldDisplay : change.newDisplay;
  if (display) return display;
  const raw = side === "old" ? change.old : change.new;
  if (raw === null || raw === undefined || raw === "") return null;
  if (change.type === "boolean") return t(raw === "true" ? "history.value.yes" : "history.value.no");
  if (change.type === "datetime") return formatTimestampDefault(raw) ?? raw;
  return raw;
}

function Value({ text, t }: { text: string | null; t: Translate }) {
  return text === null ? (
    <span className="italic text-muted-foreground">{t("history.value.empty")}</span>
  ) : (
    <span className="break-words font-medium text-foreground">{text}</span>
  );
}

/** "Label: old → new" (or just the value for a created record / added or removed row). */
function ChangeLine({ change, created, t }: { change: HistoryChangeView; created: boolean; t: Translate }) {
  const label = change.label ?? change.field;
  if (!label) return null;
  const oneSided = created || change.rowChange === "ADDED" || change.rowChange === "REMOVED";
  return (
    <li className="text-sm leading-snug text-muted-foreground">
      <span>{label}: </span>
      {change.secret ? (
        <span className="italic">{t("history.secret")}</span>
      ) : oneSided ? (
        <Value text={valueText(change, change.rowChange === "REMOVED" ? "old" : "new", t)} t={t} />
      ) : (
        <>
          <Value text={valueText(change, "old", t)} t={t} />
          <span aria-hidden="true"> → </span>
          <Value text={valueText(change, "new", t)} t={t} />
        </>
      )}
    </li>
  );
}

type Group = { key: string; heading: string | null; changes: HistoryChangeView[] };

/** Header changes first, then one group per touched tabular-section line. */
function groupChanges(changes: HistoryChangeView[], t: Translate): Group[] {
  const groups: Group[] = [];
  const byKey = new Map<string, Group>();
  for (const c of changes) {
    const key = c.section ? `${c.section}#${c.line}#${c.rowChange}` : "";
    let group = byKey.get(key);
    if (!group) {
      group = {
        key,
        heading: c.section
          ? t(`history.row.${c.rowChange ?? "CHANGED"}`, { section: c.section, line: c.line ?? "" })
          : null,
        changes: [],
      };
      byKey.set(key, group);
      groups.push(group);
    }
    group.changes.push(c);
  }
  return groups;
}

function Entry({ entry, t }: { entry: HistoryEntryView; t: Translate }) {
  const groups = useMemo(() => groupChanges(entry.changes, t), [entry.changes, t]);
  const eventKey = `history.event.${entry.event}`;
  const eventLabel = t(eventKey);
  const actor = entry.actor === "system" ? t("history.actor.system") : entry.actor;
  return (
    <li className="relative border-l border-border pb-4 pl-4 last:pb-0">
      <span className="absolute -left-[5px] top-1.5 size-2.5 rounded-full border border-border bg-card" aria-hidden="true" />
      <div className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5">
        <span className="text-sm font-semibold text-foreground">{eventLabel === eventKey ? entry.event : eventLabel}</span>
        <span className="text-sm text-foreground">{actor}</span>
        <time className="text-xs tabular-nums text-muted-foreground" dateTime={entry.at}>
          {formatTimestampDefault(entry.at) ?? entry.at}
        </time>
      </div>
      {groups.map((g) => (
        <div key={g.key} className="mt-1">
          {g.heading ? <p className="text-xs font-medium text-foreground">{g.heading}</p> : null}
          <ul className={g.heading ? "ml-3 space-y-0.5" : "space-y-0.5"}>
            {g.changes.map((c, i) => (
              <ChangeLine key={i} change={c} created={entry.event === "CREATED"} t={t} />
            ))}
          </ul>
        </div>
      ))}
    </li>
  );
}

/**
 * The change-history panel on a record surface (the `onno-history` div-custom), shown for entities
 * that opted in with `history = true`. Collapsed by default and loaded on first expand from
 * `/api/history/...`, newest event first, with keyset "show older" paging. While open it refetches
 * when the surface refreshes or the server signals a change to this record, so a save, post or
 * someone else's edit appears without a reload. Read-only: the server applies the record's own
 * read access, masks unreadable references and withholds secret values.
 */
export function EntityHistoryWidget({ target }: { target: HistoryTarget }) {
  const { kind, name, id } = target;
  const t = useMessages();
  const [open, setOpen] = useState(false);
  const [entries, setEntries] = useState<HistoryEntryView[] | null>(null);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);

  const load = useCallback(() => {
    if (!open) return;
    let cancelled = false;
    api
      .listHistory(kind, name, id)
      .then((page) => {
        if (cancelled) return;
        setEntries(page.rows);
        setNextCursor(page.hasMore ? page.nextCursor : null);
        setFailed(false);
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });
    return () => {
      cancelled = true;
    };
  }, [kind, name, id, open]);

  useEffect(() => load(), [load]);
  useSurfaceRefresh(load);

  // Live-sync on the shared SSE fan-out: any change event for this record refreshes the open panel.
  useEffect(() => {
    if (!open) return;
    const onData = (e: Event) => {
      const ev = (e as CustomEvent<UiEvent>).detail;
      if (ev?.id === id && ev.entityType !== "comment") load();
    };
    window.addEventListener("onno:dataevent", onData);
    return () => window.removeEventListener("onno:dataevent", onData);
  }, [load, id, open]);

  const loadMore = () => {
    if (!nextCursor || loadingMore) return;
    setLoadingMore(true);
    api
      .listHistory(kind, name, id, nextCursor)
      .then((page) => {
        setEntries((prev) => [...(prev ?? []), ...page.rows]);
        // A cursor that comes back unchanged would refetch the same window forever.
        setNextCursor(page.hasMore && page.nextCursor !== nextCursor ? page.nextCursor : null);
      })
      .catch(() => setFailed(true))
      .finally(() => setLoadingMore(false));
  };

  return (
    // pointer-events-auto: portaled into a DivKit div-custom whose containers are pointer-events:none.
    <div className="pointer-events-auto mt-4 rounded-card border border-border bg-card">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        className="flex w-full items-center gap-2 rounded-card px-4 py-3 text-left transition-colors hover:bg-accent/50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring sm:px-5"
      >
        <History className="size-4 text-muted-foreground" aria-hidden="true" />
        <h2 className="flex-1 text-sm font-semibold text-foreground">{t("history.title")}</h2>
        <ChevronDown
          className={`size-4 text-muted-foreground transition-transform ${open ? "rotate-180" : ""}`}
          aria-hidden="true"
        />
      </button>
      {open ? (
        <div className="px-4 pb-4 sm:px-5 sm:pb-5">
          {failed && entries === null ? (
            <p className="text-sm text-muted-foreground">{t("history.loadError")}</p>
          ) : entries === null ? (
            <p className="text-sm text-muted-foreground">{t("history.loading")}</p>
          ) : entries.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t("history.empty")}</p>
          ) : (
            <>
              <ol className="ml-1">
                {entries.map((entry) => (
                  <Entry key={entry.id} entry={entry} t={t} />
                ))}
              </ol>
              {nextCursor ? (
                <button
                  type="button"
                  onClick={loadMore}
                  disabled={loadingMore}
                  className="mt-3 rounded-control border border-border px-3 py-1.5 text-sm text-foreground transition-colors hover:bg-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-50"
                >
                  {loadingMore ? t("history.loading") : t("history.more")}
                </button>
              ) : null}
            </>
          )}
        </div>
      ) : null}
    </div>
  );
}
