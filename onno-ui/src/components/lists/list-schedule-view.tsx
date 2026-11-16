import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties, type PointerEvent as ReactPointerEvent, type ReactNode } from "react";
import FullCalendar from "@fullcalendar/react";
import dayGridPlugin from "@fullcalendar/daygrid";
import timeGridPlugin from "@fullcalendar/timegrid";
import interactionPlugin from "@fullcalendar/interaction";
import ruLocale from "@fullcalendar/core/locales/ru";
import type { DatesSetArg, EventDropArg, EventInput } from "@fullcalendar/core";
import type { EventResizeDoneArg } from "@fullcalendar/interaction";
import { addDays, format, isSameDay, startOfDay } from "date-fns";
import { ru } from "date-fns/locale";
import { ChevronLeft, ChevronRight, Loader2 } from "lucide-react";
import { api } from "../../lib/api";
import { notifyError } from "../../lib/notify-error";
import { openRoute } from "../../lib/navigation";
import { useEntityChanges } from "../../lib/query";
import { findEntity, useModel } from "../../lib/model-store";
import { cn, toSnakeCase } from "../../lib/utils";
import type { EntityRecord } from "../../lib/types";
import { Button } from "../ui/button";
import { Segmented } from "../ui/segmented";
import { useAppLocale, useMessages } from "../../providers/messages-provider";
import {
  dateAtMinutes,
  dragResult,
  isDateOnly,
  minutesFromDay,
  overlapLayout,
  parseTemporal,
  refId,
  rowAccent,
  rowTitle,
  windowQueries,
  writeTemporal,
  type ListCalendarConfig,
  type ListTimelineConfig,
} from "../../lib/schedule";
import "../widgets/calendar.css";

/**
 * The calendar and timeline alternatives to the list grid (see {@link EntityListWidget};
 * `ListSpec.calendar` / `ListSpec.timeline`). Unlike the map view, both read through the list's own
 * feed with the list's current query — search, declared filters, a page's base filter and
 * record-level access all apply — narrowed to the visible period with the feed's range filters.
 * Live data events refetch softly (the entries stay on screen). A click opens the record; a viewer
 * with write access can drag an entry, which saves just the changed fields through the ordinary
 * update command, so validation, `beforeWrite` and record access run as for any other edit. A
 * refused move snaps back and reports the server's message once.
 */

type Kind = "catalogs" | "documents";

type ViewProps = {
  kind: Kind;
  name: string;
  /** The list feed the grid pages from (`/api/list/{kind}/{name}` unless the list overrides it). */
  feedBase: string;
  /** The list's current query string: search + declared filters + base filter. */
  query: string;
  canWrite: boolean;
  /** Fixed height when embedded; ignored on a route surface (`fill`). */
  height?: number;
  /** Fill the flexed route surface instead of a fixed height. */
  fill?: boolean;
};

/** Most records one period will pull; the server clamps a page to 500, so this is fetched in batches. */
const CAP = 2000;
const PAGE = 500;

async function fetchAll(feedBase: string, params: URLSearchParams, alive: () => boolean): Promise<{ rows: EntityRecord[]; truncated: boolean }> {
  const rows: EntityRecord[] = [];
  let cursor: string | null = null;
  for (;;) {
    const page = new URLSearchParams(params);
    page.set("limit", String(PAGE));
    if (cursor) page.set("cursor", cursor);
    const r = await fetch(`${feedBase}?${page.toString()}`, { credentials: "include" });
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    const data: { rows?: EntityRecord[]; nextCursor?: string | null; hasMore?: boolean } = await r.json();
    if (!alive()) return { rows, truncated: false };
    rows.push(...(data.rows ?? []));
    const next = data.nextCursor ?? null;
    if (!data.hasMore || !next || next === cursor) return { rows, truncated: false };
    if (rows.length >= CAP) return { rows, truncated: true };
    cursor = next;
  }
}

/**
 * The records overlapping `[from, to]` (inclusive days) under the list's current query. A change
 * of period or query blanks to the loader; a live event or a surface refresh reloads in place.
 */
function useScheduleRows(
  { kind, name, feedBase, query }: Pick<ViewProps, "kind" | "name" | "feedBase" | "query">,
  startColumn: string,
  endColumn: string | undefined,
  range: { from: Date; to: Date } | null,
) {
  const [rows, setRows] = useState<EntityRecord[] | null>(null);
  const [truncated, setTruncated] = useState(false);
  const [failed, setFailed] = useState(false);
  const gen = useRef(0);
  const fromKey = range ? format(range.from, "yyyy-MM-dd") : "";
  const toKey = range ? format(range.to, "yyyy-MM-dd") : "";

  const load = useCallback((soft: boolean) => {
    if (!fromKey) return;
    const mine = ++gen.current;
    const alive = () => gen.current === mine;
    if (!soft) setRows(null);
    const from = parseTemporal(fromKey)!;
    const to = parseTemporal(toKey)!;
    Promise.all(windowQueries(query, startColumn, endColumn, from, to).map((params) => fetchAll(feedBase, params, alive)))
      .then((results) => {
        if (!alive()) return;
        const byId = new Map<string, EntityRecord>();
        for (const result of results) for (const row of result.rows) byId.set(String(row.id ?? ""), row);
        setRows([...byId.values()]);
        setTruncated(results.some((r) => r.truncated));
        setFailed(false);
      })
      .catch(() => {
        if (!alive()) return;
        // Keep what is on screen after a failed refresh; a failed first load shows the empty state.
        setRows((prev) => prev ?? []);
        setFailed(true);
      });
  }, [feedBase, query, startColumn, endColumn, fromKey, toKey]);

  useEffect(() => { load(false); }, [load]);
  const reload = useCallback(() => load(true), [load]);
  // Live: any change to the entity (a save here, another user's edit, a post) re-reads the period.
  useEntityChanges(`${kind}/${name}`, reload);

  /** Optimistically apply a change to one record (the live event / a reload confirms it). */
  const patch = useCallback((id: string, values: EntityRecord) => {
    setRows((prev) => prev?.map((row) => (String(row.id) === id ? { ...row, ...values } : row)) ?? prev);
  }, []);

  return { rows, truncated, failed, reload, patch };
}

function saveRecord(kind: Kind, name: string, id: string, payload: EntityRecord): Promise<unknown> {
  return kind === "catalogs" ? api.updateCatalogItem(name, id, payload) : api.updateDocument(name, id, payload);
}

function useDateLocale() {
  const locale = useAppLocale();
  return locale?.startsWith("ru") ? ru : undefined;
}

/** The shared header: previous / today / next, the period title, and the view's own controls. */
function ScheduleHeader({ title, onStep, onToday, loading, children }: {
  title: string;
  onStep: (direction: -1 | 1) => void;
  onToday: () => void;
  loading: boolean;
  children?: ReactNode;
}) {
  const t = useMessages();
  return (
    <div className="flex shrink-0 flex-wrap items-center gap-2 border-b border-border/60 px-3 py-2">
      <div className="flex items-center">
        <Button variant="ghost" size="icon" className="h-7 w-7" onClick={() => onStep(-1)} aria-label={t("calendar.previous")}>
          <ChevronLeft className="h-3.5 w-3.5" />
        </Button>
        <Button variant="ghost" size="icon" className="h-7 w-7" onClick={() => onStep(1)} aria-label={t("calendar.next")}>
          <ChevronRight className="h-3.5 w-3.5" />
        </Button>
      </div>
      <Button variant="outline" size="sm" className="h-7 px-2" onClick={onToday}>{t("calendar.today")}</Button>
      <h3 className="min-w-0 truncate text-sm font-medium capitalize" aria-live="polite">{title}</h3>
      {loading && <Loader2 className="size-3.5 animate-spin text-muted-foreground" aria-label={t("loading.generic")} />}
      <div className="ml-auto flex items-center gap-2">{children}</div>
    </div>
  );
}

const frameClass = "flex min-h-0 flex-col overflow-hidden rounded-card border border-border bg-card";

// ─── Calendar ────────────────────────────────────────────────────────────────

const FC_VIEW = { month: "dayGridMonth", week: "timeGridWeek", day: "timeGridDay" } as const;
type CalendarMode = keyof typeof FC_VIEW;

/** Readable text over an authored hex colour; other CSS colours (our own hues) are dark enough for white. */
function textOn(color: string): string {
  const hex = /^#([0-9a-f]{6})$/i.exec(color.trim());
  if (!hex) return "white";
  const n = parseInt(hex[1], 16);
  const luminance = (0.299 * (n >> 16) + 0.587 * ((n >> 8) & 255) + 0.114 * (n & 255)) / 255;
  return luminance > 0.6 ? "#111827" : "white";
}

/**
 * The feed rows as FullCalendar events. A date-only start draws an all-day entry whose date-only
 * end is its last day (inclusive — FullCalendar's is exclusive, hence the extra day); a date-time
 * start draws a timed entry.
 */
export function calendarEvents(rows: EntityRecord[], config: ListCalendarConfig): EventInput[] {
  const events: EventInput[] = [];
  for (const row of rows) {
    const startValue = row[config.startField];
    const start = parseTemporal(startValue);
    if (!start || row.id == null) continue;
    const allDay = isDateOnly(startValue);
    const endValue = config.endField ? row[config.endField] : undefined;
    let end = parseTemporal(endValue);
    if (end && isDateOnly(endValue)) end = addDays(end, 1);
    if (end && end.getTime() <= start.getTime()) end = null;
    const accent = rowAccent(row, config.colorField);
    events.push({
      id: String(row.id),
      title: rowTitle(row, config.titleField),
      start,
      end: end ?? undefined,
      allDay,
      ...(accent ? { backgroundColor: accent, borderColor: accent, textColor: textOn(accent) } : {}),
      classNames: accent ? [] : ["fc-onno-posted"],
    });
  }
  return events;
}

/**
 * The partial update for a calendar drag or resize: the start in the shape the field already had
 * and — when the list declares an end field and the entry had (or was just given) an end — the end
 * likewise, a date-only end converted back to its inclusive last day.
 */
export function calendarMovePayload(
  row: EntityRecord,
  config: Pick<ListCalendarConfig, "startField" | "endField">,
  start: Date,
  end: Date | null,
): EntityRecord {
  const startValue = row[config.startField];
  const payload: EntityRecord = { [config.startField]: writeTemporal(start, startValue) };
  if (config.endField && end) {
    const endValue = row[config.endField];
    // An end the record never had takes the start's shape (both are the same kind of field).
    const like = endValue ?? startValue;
    payload[config.endField] = writeTemporal(isDateOnly(like) ? addDays(end, -1) : end, like);
  }
  return payload;
}

export function ListCalendarView({ kind, name, feedBase, query, canWrite, config, height = 620, fill = false }: ViewProps & { config: ListCalendarConfig }) {
  const t = useMessages();
  const locale = useAppLocale();
  const calendarRef = useRef<FullCalendar | null>(null);
  const [mode, setMode] = useState<CalendarMode>(config.mode && config.mode in FC_VIEW ? config.mode : "month");
  const [range, setRange] = useState<{ from: Date; to: Date } | null>(null);
  const [title, setTitle] = useState("");
  const endColumn = config.endField ? config.endColumn : undefined;
  const { rows, truncated, reload, patch } = useScheduleRows({ kind, name, feedBase, query }, config.startColumn, endColumn, range);
  const rowsRef = useRef(rows);
  rowsRef.current = rows;

  const events = useMemo(() => calendarEvents(rows ?? [], config), [rows, config]);

  const onDatesSet = useCallback((arg: DatesSetArg) => {
    // FullCalendar's range end is exclusive; the feed window is inclusive days.
    const from = startOfDay(arg.start);
    const to = startOfDay(addDays(arg.end, -1));
    setRange((prev) => (prev && isSameDay(prev.from, from) && isSameDay(prev.to, to) ? prev : { from, to }));
    setTitle(arg.view.title);
  }, []);

  const switchMode = (next: CalendarMode) => {
    setMode(next);
    calendarRef.current?.getApi().changeView(FC_VIEW[next]);
  };

  const save = async (arg: EventDropArg | EventResizeDoneArg) => {
    const id = arg.event.id;
    const row = rowsRef.current?.find((r) => String(r.id) === id);
    if (!row || !arg.event.start) { arg.revert(); return; }
    const payload = calendarMovePayload(row, config, arg.event.start, arg.event.end);
    patch(id, payload);
    try {
      await saveRecord(kind, name, id, payload);
    } catch (err) {
      arg.revert();
      reload();
      notifyError(err, { fallback: t("calendar.moveFailed") });
    }
  };

  return (
    <div className={cn("onno-calendar-widget onno-list-calendar", frameClass, fill && "h-full min-h-[360px]")} style={fill ? undefined : { height }}>
      <ScheduleHeader
        title={title}
        loading={rows === null}
        onStep={(d) => { const apiRef = calendarRef.current?.getApi(); if (d < 0) apiRef?.prev(); else apiRef?.next(); }}
        onToday={() => calendarRef.current?.getApi().today()}
      >
        <Segmented
          size="sm"
          value={mode}
          onChange={switchMode}
          options={[
            { value: "month" as const, label: t("calendar.month") },
            { value: "week" as const, label: t("calendar.week") },
            { value: "day" as const, label: t("schedule.day") },
          ]}
        />
      </ScheduleHeader>
      {truncated && <p className="shrink-0 px-3 pt-2 text-xs text-muted-foreground">{t("schedule.showingFirst", { shown: CAP })}</p>}
      <div className="min-h-0 flex-1 p-2">
        <FullCalendar
          ref={calendarRef}
          plugins={[dayGridPlugin, timeGridPlugin, interactionPlugin]}
          initialView={FC_VIEW[mode]}
          headerToolbar={false}
          height="100%"
          firstDay={1}
          locale={locale?.startsWith("ru") ? ruLocale : undefined}
          events={events}
          datesSet={onDatesSet}
          nowIndicator
          dayMaxEvents
          navLinks
          navLinkDayClick={(date) => { setMode("day"); calendarRef.current?.getApi().changeView(FC_VIEW.day, date); }}
          eventDisplay="block"
          scrollTime="08:00:00"
          eventTimeFormat={{ hour: "2-digit", minute: "2-digit", hour12: false }}
          slotLabelFormat={{ hour: "2-digit", minute: "2-digit", hour12: false }}
          editable={canWrite}
          eventStartEditable={canWrite}
          // Stretching an entry needs somewhere to store its end.
          eventDurationEditable={canWrite && !!config.endField}
          // An all-day (date) entry stays all-day and a timed one timed: the field's type is fixed.
          eventAllow={(drop, event) => !event || drop.allDay === event.allDay}
          eventClick={(arg) => { arg.jsEvent.preventDefault(); openRoute(`onno://${kind}/${name}/${arg.event.id}`); }}
          eventDrop={save}
          eventResize={save}
        />
      </div>
    </div>
  );
}

// ─── Timeline (resource day scheduler) ───────────────────────────────────────

type Lane = { id: string; label: string };
const UNASSIGNED = "";
const AXIS_W = 56;
const LANE_MIN_W = 150;

type Block = {
  id: string;
  row: EntityRecord;
  lane: string;
  /** Visible minutes from the day's midnight, clipped to the axis. */
  start: number;
  end: number;
  /** Real start/end minutes (may reach outside the visible day). */
  realStart: number;
  realEnd: number;
};

/** The day's blocks: every record overlapping the visible hours, clipped to them. */
export function timelineBlocks(rows: EntityRecord[], config: ListTimelineConfig, day: Date, bounds: { start: number; end: number }): Block[] {
  const blocks: Block[] = [];
  for (const row of rows) {
    const start = parseTemporal(row[config.startField]);
    if (!start || row.id == null) continue;
    const endValue = row[config.endField];
    let end = parseTemporal(endValue);
    if (end && isDateOnly(endValue)) end = addDays(end, 1);
    const realStart = minutesFromDay(start, day);
    // A record with no usable end still shows: one hour, like a calendar entry.
    const realEnd = end && end.getTime() > start.getTime() ? minutesFromDay(end, day) : realStart + 60;
    if (realEnd <= bounds.start || realStart >= bounds.end) continue;
    blocks.push({
      id: String(row.id),
      row,
      lane: config.laneField ? refId(row[config.laneField]) : UNASSIGNED,
      start: Math.max(bounds.start, realStart),
      end: Math.min(bounds.end, realEnd),
      realStart,
      realEnd,
    });
  }
  return blocks;
}

/**
 * The lanes to draw: the lane reference's records (so an idle resource still has a column), then
 * any lane the day's records use that the catalog list did not return, then "Unassigned" when a
 * record has no lane. Without a lane field there is exactly one unlabelled lane.
 */
export function timelineLanes(config: ListTimelineConfig, known: Lane[], blocks: Block[], unassignedLabel: string): Lane[] {
  if (!config.laneField) return [{ id: UNASSIGNED, label: "" }];
  const lanes = [...known];
  const seen = new Set(lanes.map((l) => l.id));
  let unassigned = false;
  for (const block of blocks) {
    if (!block.lane) { unassigned = true; continue; }
    if (seen.has(block.lane)) continue;
    seen.add(block.lane);
    lanes.push({ id: block.lane, label: String(block.row[`${config.laneField}Display`] ?? block.lane) });
  }
  if (unassigned || lanes.length === 0) lanes.push({ id: UNASSIGNED, label: unassignedLabel });
  return lanes;
}

/** The lane reference's records the viewer may list; empty when that list is not served to them. */
function useLaneRecords(config: ListTimelineConfig): Lane[] {
  const [lanes, setLanes] = useState<Lane[]>([]);
  // The lane entity names the reference's target by logical name; the model resolves its route.
  const laneEntity = findEntity(useModel(), config.laneEntity ?? config.laneTarget ?? "");
  const { laneField } = config;
  const laneKind = config.laneKind ?? laneEntity?.kind;
  const laneRoute = laneEntity?.name ?? (config.laneTarget ? toSnakeCase(config.laneTarget.replace(/ /g, "")) : undefined);
  useEffect(() => {
    if (!laneField || !laneKind || !laneRoute) { setLanes([]); return; }
    let alive = true;
    fetch(`/api/list/${laneKind}/${laneRoute}?limit=200`, { credentials: "include" })
      .then((r) => (r.ok ? r.json() : { rows: [] }))
      .then((data: { rows?: EntityRecord[] }) => {
        if (!alive) return;
        setLanes((data.rows ?? [])
          .filter((row) => row.id != null && row.isFolder !== true)
          .map((row) => ({ id: String(row.id), label: rowTitle(row) || String(row.id) })));
      })
      .catch(() => { if (alive) setLanes([]); });
    return () => { alive = false; };
  }, [laneField, laneKind, laneRoute]);
  return lanes;
}

type Drag = {
  id: string;
  mode: "move" | "resize";
  x: number;
  y: number;
  origin: { start: number; end: number };
  lane: string;
  moved: boolean;
  /** Where the block would land if released now (kept here, not only in render state). */
  next: { start: number; end: number; lane: string } | null;
};

export function ListTimelineView({ kind, name, feedBase, query, canWrite, config, height = 620, fill = false }: ViewProps & { config: ListTimelineConfig }) {
  const t = useMessages();
  const dateLocale = useDateLocale();
  const [day, setDay] = useState(() => startOfDay(new Date()));
  const range = useMemo(() => ({ from: day, to: day }), [day]);
  const { rows, truncated, reload, patch } = useScheduleRows({ kind, name, feedBase, query }, config.startColumn, config.endColumn, range);
  const laneRecords = useLaneRecords(config);

  const step = config.slotMinutes && config.slotMinutes > 0 ? config.slotMinutes : 30;
  const bounds = useMemo(() => ({ start: (config.startHour ?? 0) * 60, end: (config.endHour ?? 24) * 60 }), [config.startHour, config.endHour]);
  // A slot is at least 28px tall so a one-slot block stays legible; an hour is at least 48px.
  const pxPerMinute = Math.max(28 / step, 0.8);
  const bodyHeight = (bounds.end - bounds.start) * pxPerMinute;

  const blocks = useMemo(() => timelineBlocks(rows ?? [], config, day, bounds), [rows, config, day, bounds]);
  const lanes = useMemo(() => timelineLanes(config, laneRecords, blocks, t("schedule.unassigned")), [config, laneRecords, blocks, t]);

  const [preview, setPreview] = useState<{ id: string; start: number; end: number; lane: string } | null>(null);
  const drag = useRef<Drag | null>(null);
  const suppressClick = useRef(false);
  const laneEls = useRef(new Map<string, HTMLElement>());
  const scrollRef = useRef<HTMLDivElement | null>(null);

  // Open on the working part of the day: the first block, or 08:00 on a full-day axis.
  const scrolledFor = useRef("");
  useEffect(() => {
    const el = scrollRef.current;
    const key = format(day, "yyyy-MM-dd");
    if (!el || rows === null || scrolledFor.current === key) return;
    scrolledFor.current = key;
    const first = blocks.reduce((min, b) => Math.min(min, b.start), Infinity);
    const target = Number.isFinite(first) ? first - 30 : Math.max(bounds.start, 8 * 60);
    el.scrollTop = Math.max(0, (target - bounds.start) * pxPerMinute);
  }, [rows, blocks, day, bounds.start, pxPerMinute]);

  const laneAt = (x: number, fallback: string): string => {
    for (const [id, el] of laneEls.current) {
      const rect = el.getBoundingClientRect();
      if (x >= rect.left && x < rect.right) return id;
    }
    return fallback;
  };

  const onPointerDown = (e: ReactPointerEvent<HTMLElement>, block: Block, mode: "move" | "resize") => {
    // Touch keeps scrolling and tap-to-open; dragging is a mouse/pen gesture.
    if (!canWrite || e.button !== 0 || e.pointerType === "touch") return;
    // A block cut off by the visible hours cannot be moved (its start is off-axis) or resized past them.
    if (mode === "move" && block.realStart < bounds.start) return;
    if (block.realEnd > bounds.end) return;
    e.stopPropagation();
    e.currentTarget.setPointerCapture?.(e.pointerId);
    drag.current = { id: block.id, mode, x: e.clientX, y: e.clientY, origin: { start: block.realStart, end: block.realEnd }, lane: block.lane, moved: false, next: null };
  };

  const onPointerMove = (e: ReactPointerEvent<HTMLElement>) => {
    const d = drag.current;
    if (!d) return;
    if (!d.moved && Math.abs(e.clientX - d.x) + Math.abs(e.clientY - d.y) < 5) return;
    d.moved = true;
    const next = dragResult(d.mode, d.origin, (e.clientY - d.y) / pxPerMinute, step, bounds);
    const lane = d.mode === "move" && config.laneField ? laneAt(e.clientX, d.lane) : d.lane;
    d.next = { ...next, lane };
    setPreview({ id: d.id, ...d.next });
  };

  const commit = async (block: Block, next: { start: number; end: number; lane: string }) => {
    const payload: EntityRecord = {};
    const optimistic: EntityRecord = {};
    if (next.start !== block.realStart) {
      payload[config.startField] = writeTemporal(dateAtMinutes(day, next.start), block.row[config.startField]);
    }
    if (next.end !== block.realEnd) {
      const like = block.row[config.endField] ?? block.row[config.startField];
      const end = dateAtMinutes(day, next.end);
      payload[config.endField] = writeTemporal(isDateOnly(like) ? addDays(end, -1) : end, like);
    }
    if (config.laneField && next.lane !== block.lane) {
      payload[config.laneField] = next.lane || null;
      optimistic[`${config.laneField}Display`] = next.lane ? lanes.find((l) => l.id === next.lane)?.label ?? "" : "";
    }
    if (Object.keys(payload).length === 0) return;
    patch(block.id, { ...payload, ...optimistic });
    try {
      await saveRecord(kind, name, block.id, payload);
    } catch (err) {
      reload();
      notifyError(err, { fallback: t("calendar.moveFailed") });
    }
  };

  const onPointerUp = (e: ReactPointerEvent<HTMLElement>, block: Block) => {
    const d = drag.current;
    drag.current = null;
    if (!d) return;
    e.currentTarget.releasePointerCapture?.(e.pointerId);
    const landed = d.next;
    setPreview(null);
    if (!d.moved) return;
    // The click that follows this pointer-up belongs to the drag, not to "open the record".
    suppressClick.current = true;
    window.setTimeout(() => { suppressClick.current = false; }, 0);
    if (landed) void commit(block, landed);
  };

  const onPointerCancel = () => { drag.current = null; setPreview(null); };

  const open = (id: string) => {
    if (suppressClick.current) { suppressClick.current = false; return; }
    openRoute(`onno://${kind}/${name}/${id}`);
  };

  const hours: number[] = [];
  for (let m = Math.ceil(bounds.start / 60) * 60; m < bounds.end; m += 60) hours.push(m);
  const now = new Date();
  const nowMinutes = isSameDay(now, day) ? minutesFromDay(now, day) : null;
  const gridStyle: CSSProperties = {
    backgroundImage:
      "linear-gradient(to bottom, hsl(var(--border)) 1px, transparent 1px), linear-gradient(to bottom, hsl(var(--border) / 0.45) 1px, transparent 1px)",
    backgroundSize: `100% ${60 * pxPerMinute}px, 100% ${step * pxPerMinute}px`,
    backgroundPosition: `0 ${-(bounds.start % 60) * pxPerMinute}px, 0 0`,
  };
  const hhmm = (minutes: number) => format(dateAtMinutes(day, minutes), "HH:mm");

  return (
    <div className={cn("onno-list-timeline", frameClass, fill && "h-full min-h-[360px]")} style={fill ? undefined : { height }}>
      <ScheduleHeader
        title={format(day, "EEEE, d MMMM yyyy", { locale: dateLocale })}
        loading={rows === null}
        onStep={(d) => setDay((prev) => addDays(prev, d))}
        onToday={() => setDay(startOfDay(new Date()))}
      />
      {truncated && <p className="shrink-0 px-3 pt-2 text-xs text-muted-foreground">{t("schedule.showingFirst", { shown: CAP })}</p>}
      <div ref={scrollRef} className="min-h-0 flex-1 overflow-auto overscroll-contain" data-onno-timeline-scroll="">
        <div style={{ minWidth: AXIS_W + lanes.length * LANE_MIN_W }}>
          {config.laneField ? (
            <div className="sticky top-0 z-20 flex border-b border-border bg-card" role="row">
              <div className="sticky left-0 z-10 shrink-0 bg-card" style={{ width: AXIS_W }} />
              {lanes.map((lane) => (
                <div key={lane.id} role="columnheader" title={lane.label}
                  className={cn("min-w-0 flex-1 truncate border-l border-border px-2 py-1.5 text-xs font-medium", !lane.id && "text-muted-foreground")}
                  style={{ minWidth: LANE_MIN_W }}>
                  {lane.label}
                </div>
              ))}
            </div>
          ) : null}
          <div className="relative flex" style={{ height: bodyHeight }}>
            <div className="sticky left-0 z-10 shrink-0 bg-card" style={{ width: AXIS_W }} aria-hidden="true">
              {hours.map((m) => (
                <span key={m} className="absolute right-2 -translate-y-1/2 text-[11px] tabular-nums text-muted-foreground"
                  style={{ top: (m - bounds.start) * pxPerMinute, visibility: m === bounds.start ? "hidden" : undefined }}>
                  {hhmm(m)}
                </span>
              ))}
            </div>
            {lanes.map((lane) => {
              const laneBlocks = blocks
                .map((b) => (preview && preview.id === b.id ? { ...b, start: Math.max(bounds.start, preview.start), end: Math.min(bounds.end, preview.end), lane: preview.lane } : b))
                .filter((b) => b.lane === lane.id || (!config.laneField && lane.id === UNASSIGNED));
              const layout = overlapLayout(laneBlocks.map((b) => ({ id: b.id, start: b.start, end: b.end })));
              return (
                <div key={lane.id} data-onno-lane={lane.id} ref={(el) => { if (el) laneEls.current.set(lane.id, el); else laneEls.current.delete(lane.id); }}
                  className="relative min-w-0 flex-1 border-l border-border" style={{ minWidth: LANE_MIN_W, ...gridStyle }}>
                  {nowMinutes != null && nowMinutes >= bounds.start && nowMinutes < bounds.end ? (
                    <div className="pointer-events-none absolute inset-x-0 z-[5] border-t border-destructive" style={{ top: (nowMinutes - bounds.start) * pxPerMinute }} />
                  ) : null}
                  {laneBlocks.map((b) => {
                    const original = blocks.find((o) => o.id === b.id) ?? b;
                    const place = layout.get(b.id) ?? { column: 0, columns: 1 };
                    const accent = rowAccent(b.row, config.colorField) ?? "hsl(var(--primary))";
                    const dragging = preview?.id === b.id;
                    const movable = canWrite && original.realStart >= bounds.start && original.realEnd <= bounds.end;
                    const title = rowTitle(b.row, config.titleField);
                    const times = `${hhmm(dragging ? preview!.start : b.realStart)}–${hhmm(dragging ? preview!.end : b.realEnd)}`;
                    const blockHeight = Math.max(16, (b.end - b.start) * pxPerMinute - 2);
                    // Too short for two lines: title and time share one line so neither is cut in half.
                    const compact = blockHeight < 32;
                    return (
                      <div key={b.id} role="button" tabIndex={0} data-onno-block={b.id}
                        aria-label={`${title} ${times}`}
                        title={`${title}\n${times}`}
                        onClick={() => open(b.id)}
                        onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); open(b.id); } }}
                        onPointerDown={(e) => onPointerDown(e, original, "move")}
                        onPointerMove={onPointerMove}
                        onPointerUp={(e) => onPointerUp(e, original)}
                        onPointerCancel={onPointerCancel}
                        className={cn(
                          "absolute select-none overflow-hidden rounded-[4px] px-1.5 py-0.5 text-left text-[11px] leading-tight outline-none focus-visible:ring-2 focus-visible:ring-ring",
                          compact && "flex items-baseline gap-1.5",
                          movable ? "cursor-grab" : "cursor-pointer",
                          dragging && "z-10 cursor-grabbing shadow-md ring-1 ring-ring/50",
                        )}
                        style={{
                          top: (b.start - bounds.start) * pxPerMinute + 1,
                          height: blockHeight,
                          left: `calc(${(place.column / place.columns) * 100}% + 2px)`,
                          width: `calc(${100 / place.columns}% - 4px)`,
                          backgroundColor: `color-mix(in srgb, ${accent} ${dragging ? 34 : 24}%, hsl(var(--card)))`,
                          touchAction: "manipulation",
                        }}>
                        {compact ? (
                          <>
                            <span className="shrink-0 tabular-nums text-muted-foreground">{hhmm(dragging ? preview!.start : b.realStart)}</span>
                            <span className="min-w-0 truncate font-medium text-foreground">{title}</span>
                          </>
                        ) : (
                          <>
                            <div className="truncate font-medium text-foreground">{title}</div>
                            <div className="truncate tabular-nums text-muted-foreground">{times}</div>
                          </>
                        )}
                        {movable ? (
                          <div role="separator" aria-label={t("schedule.resize")} data-onno-resize=""
                            className="absolute inset-x-0 bottom-0 h-1.5 cursor-ns-resize"
                            onPointerDown={(e) => onPointerDown(e, original, "resize")}
                            onPointerMove={onPointerMove}
                            onPointerUp={(e) => { e.stopPropagation(); onPointerUp(e, original); }}
                            onPointerCancel={onPointerCancel}
                            onClick={(e) => e.stopPropagation()} />
                        ) : null}
                      </div>
                    );
                  })}
                </div>
              );
            })}
          </div>
        </div>
      </div>
      {rows !== null && blocks.length === 0 ? (
        <p className="shrink-0 border-t border-border/60 px-3 py-1.5 text-xs text-muted-foreground">{t("schedule.empty")}</p>
      ) : null}
    </div>
  );
}
