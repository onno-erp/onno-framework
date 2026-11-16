import { openRoute } from "../../lib/navigation";
import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties, type MouseEvent as ReactMouseEvent, type ReactNode } from "react";
import FullCalendar from "@fullcalendar/react";
import dayGridPlugin from "@fullcalendar/daygrid";
import timeGridPlugin from "@fullcalendar/timegrid";
import interactionPlugin from "@fullcalendar/interaction";
import ruLocale from "@fullcalendar/core/locales/ru";
import type {
  EventApi,
  EventInput,
  EventContentArg,
  DatesSetArg,
  EventDropArg,
} from "@fullcalendar/core";
import type { EventResizeDoneArg } from "@fullcalendar/interaction";
import { format, endOfMonth, startOfMonth, addMonths, subMonths, addYears, subYears, startOfYear, endOfYear, getDaysInMonth, addDays, addWeeks, startOfWeek, differenceInCalendarDays, type Locale } from "date-fns";
import { ru } from "date-fns/locale";
import { ChevronDown, ChevronLeft, ChevronRight, ExternalLink, Lock, X } from "lucide-react";
import { api } from "../../lib/api";
import { notifyError } from "../../lib/notify-error";
import { toSnakeCase, cn } from "../../lib/utils";
import { formatAmount, toNumber } from "../../lib/format";
import { whereParam, type WhereClause } from "../../lib/measure";
import type { EntityRecord } from "../../lib/types";
import { Card, CardContent, CardHeader, CardTitle } from "../ui/card";
import { HintIcon } from "../ui/hint-icon";
import { Button } from "../ui/button";
import { Segmented } from "../ui/segmented";
import { Popover, PopoverAnchor, PopoverContent, PopoverTrigger } from "../ui/popover";
import { useAppLocale, useMessages } from "../../providers/messages-provider";
import { logicalEntityKey } from "../../lib/entity-keys";
import { useEntityVersion } from "../../lib/aggregate-data";
import { useEntitySource } from "../../lib/entity-source";
import { findEntity, useModel } from "../../lib/model-store";
import { Boundary } from "../pages/boundary";
import { useFillHeight } from "../../lib/fill-height";
import "./calendar.css";

/**
 * A month/week/year/agenda calendar of one catalog's or document's records: each record is an item
 * from its `start` field (to `end`, or `duration` days later), labelled by `itemTitle(record)`.
 *
 * ```tsx
 * <Calendar title="Schedule" entity="Schedule Events" start="startsAt" end="endsAt" itemTitle={(e) => e.subject} />
 * ```
 */
export interface CalendarProps {
  /** Card title. */
  title?: string;
  /** Help text behind a `?` next to the title. */
  hint?: string;
  /** The catalog or document whose records are shown, by logical name (`"Bookings"`). */
  entity?: string;
  /** Which records, e.g. `{ status: { notIn: ["DRAFT", "CANCELED"] } }`. */
  where?: WhereClause;
  /** The start field of each item; default `"date"`. */
  start?: string;
  /** The end field, making items span (booking-style). */
  end?: string;
  /** A field holding the item's length in days, used when there is no `end`. */
  duration?: string;
  /** Draw items as all-day bars; default true when `end` or `duration` is set. */
  allDay?: boolean;
  /** Each item's headline; default the record's description (catalogs) or number (documents). */
  itemTitle?: (record: EntityRecord) => string | null | undefined;
  /** Each item's second line; default the usual `…Display` companions. */
  subtitle?: (record: EntityRecord) => string | null | undefined;
  /** Each item's colour (any CSS colour); default the draft/posted styling. */
  color?: (record: EntityRecord) => string | null | undefined;
  /** The amount shown on items; default a numeric `total`. */
  amount?: (record: EntityRecord) => number | null | undefined;
  /** The amount's ISO 4217 currency, fixed (`"USD"`) or per record. */
  currency?: string | ((record: EntityRecord) => string | null | undefined);
  /** A free unit label instead of a currency (`"kg"`, `"pcs"`). */
  unit?: string;
  /** Which side of the amount the unit sits; default `"suffix"`. */
  unitPosition?: "prefix" | "suffix";
  /** Fraction-digit policy of the amount. */
  format?: "integer" | "decimal";
  /** BCP-47 locale for the amount; default the browser's. */
  locale?: string;
  /** Disable drag-to-reschedule (it is also off when the viewer may not write the entity). */
  readOnly?: boolean;
  /** Take the rest of the page height and scroll inside. */
  fill?: boolean;
}

interface EventExtendedProps {
  posted?: boolean;
  primary?: string;
  secondary?: string;
  amount?: number;
  currency?: string;
  hasEnd?: boolean;
  avatarUrl?: string;
  avatarLabel?: string;
  /** The authored item colour (`color(record)`), when there is one. */
  color?: string;
}

type CalendarView = "year" | "dayGridMonth" | "timeGridWeek" | "agenda";
type Range = { from: Date; to: Date };

// The 7-column month grid is unusable on a phone — each day is ~50px wide and
// every booking renders as a full bar, so the widget falls back to a tap-to-open
// agenda list below this width. Matches the app's server-side MOBILE_MAX so the
// agenda shows exactly where the mobile dashboard does.
const MOBILE_MAX = 600;

function useIsMobile(maxWidth = MOBILE_MAX): boolean {
  const [isMobile, setIsMobile] = useState(
    () => typeof window !== "undefined" && window.matchMedia(`(max-width: ${maxWidth}px)`).matches
  );
  useEffect(() => {
    const mql = window.matchMedia(`(max-width: ${maxWidth}px)`);
    const onChange = () => setIsMobile(mql.matches);
    onChange();
    mql.addEventListener("change", onChange);
    return () => mql.removeEventListener("change", onChange);
  }, [maxWidth]);
  return isMobile;
}

/** Per-viewer view and filter choices; storage can be unavailable (private mode), so never throw. */
function remembered(key: string): string | null {
  try { return window.localStorage.getItem(`onno.calendar.${key}`); } catch { return null; }
}
function remember(key: string, value: string | null) {
  try {
    if (value == null) window.localStorage.removeItem(`onno.calendar.${key}`);
    else window.localStorage.setItem(`onno.calendar.${key}`, value);
  } catch { /* preference only */ }
}

function pickAvatar(row: EntityRecord): { url?: string; label?: string } {
  for (const key of Object.keys(row)) {
    if (key.endsWith("Avatar") && typeof row[key] === "string" && (row[key] as string).trim()) {
      const base = key.slice(0, -"Avatar".length);
      const label = row[`${base}Display`];
      return {
        url: row[key] as string,
        label: typeof label === "string" ? label : undefined,
      };
    }
  }
  return {};
}

function pickField(row: EntityRecord, fields: string[]): string | undefined {
  for (const f of fields) {
    const v = row[f];
    if (typeof v === "string" && v.trim()) return v;
    if (typeof v === "number") return String(v);
  }
  return undefined;
}

/** Length of a timed item in minutes; 0 for all-day or open-ended items. */
export function timedMinutes(start: Date | null, end: Date | null, allDay: boolean): number {
  if (allDay || !start || !end) return 0;
  return Math.max(0, Math.round((end.getTime() - start.getTime()) / 60000));
}

/** "45 min", "1 h", "2 h 30 min" — the length line on a detail card. */
export function formatDuration(minutes: number, t: (key: string, params?: Record<string, string | number>) => string): string {
  const hours = Math.floor(minutes / 60), rest = minutes % 60;
  if (!hours) return t("calendar.minutes", { count: rest });
  return rest ? `${t("calendar.hours", { count: hours })} ${t("calendar.minutes", { count: rest })}` : t("calendar.hours", { count: hours });
}
const day = (d: Date) => format(d, "yyyy-MM-dd");
const mondayOf = (d: Date) => startOfWeek(d, { weekStartsOn: 1 });

/**
 * The month and week grids are long FullCalendar views inside a native scroller, so they scroll
 * smoothly: the month through a run of weeks (vertically), the week through three weeks of days
 * (sideways). Near either end the window shifts by a block and the scroll position is compensated,
 * so travel never runs out and nothing visibly jumps.
 */
const FC_VIEW = { dayGridMonth: "dayGridWeeks", timeGridWeek: "timeGridDays" } as const;
const MONTH_WEEKS = 26, MONTH_LEAD = 8, MONTH_SHIFT = 8;
const WEEK_DAYS = 21, WEEK_SHIFT = 7;
/** First guess at the week grid's hour column width, replaced by the measured one after render. */
const AXIS_GUESS = 40;

/** First day of the rendered window that comfortably contains `date`. */
export function windowStart(view: string, date: Date): Date {
  return view === "timeGridWeek" ? addDays(mondayOf(date), -WEEK_SHIFT) : addWeeks(mondayOf(startOfMonth(date)), -MONTH_LEAD);
}

/** Where to bring into view for `date`: the 1st of its month, or its Monday. */
function focusDate(view: string, date: Date): Date {
  return view === "timeGridWeek" ? mondayOf(date) : startOfMonth(date);
}

/** "Oct 12 – 18, 2026" / "Sep 28 – Oct 4, 2026" / "Dec 28, 2026 – Jan 3, 2027" for seven visible days. */
export function weekTitle(first: Date, dateLocale?: Locale): string {
  const last = addDays(first, 6);
  const o = { locale: dateLocale };
  if (first.getFullYear() !== last.getFullYear()) return `${format(first, "MMM d, yyyy", o)} – ${format(last, "MMM d, yyyy", o)}`;
  if (first.getMonth() !== last.getMonth()) return `${format(first, "MMM d", o)} – ${format(last, "MMM d, yyyy", o)}`;
  return `${format(first, "MMM d", o)} – ${format(last, "d, yyyy", o)}`;
}

/**
 * Read-side optimistic values and write-side partial payload for a calendar drag/resize.
 *
 * A calendar may render a custom business field such as `startsAt` instead of the document's
 * `date`. The generic document endpoint still windows calendar reads by its date system field, so a move keeps
 * both values aligned while also updating the configured end field. Catalogs have no such system
 * date, so `syncDocumentDate` is off for them and only the configured fields are written.
 */
export function calendarMoveValues(
  dateField: string,
  endDateField: string | undefined,
  startIso: string,
  endIso: string | undefined,
  syncDocumentDate = true
): { optimistic: EntityRecord; payload: EntityRecord } {
  const startWriteKey = logicalEntityKey(dateField);
  const optimistic: EntityRecord = { [startWriteKey]: startIso };
  const payload: EntityRecord = {};
  if (syncDocumentDate) {
    optimistic.date = startIso;
    payload.date = startIso;
  }
  payload[startWriteKey] = startIso;

  if (endDateField && endIso) {
    const endWriteKey = logicalEntityKey(endDateField);
    optimistic[endWriteKey] = endIso;
    payload[endWriteKey] = endIso;
  }
  return { optimistic, payload };
}

/**
 * Query string for a catalog calendar window. Catalogs have no system date to window on, so the
 * configured start column is bounded with the list feed's `ge`/`le` filters; bare dates make the
 * server cast and include the whole last day.
 */
export function catalogWindowParams(dateField: string, from: Date, to: Date, where?: WhereClause): URLSearchParams {
  const column = toSnakeCase(logicalEntityKey(dateField));
  const params = new URLSearchParams({ limit: "500" });
  params.append("ge", `${column},${day(from)}`);
  params.append("le", `${column},${day(to)}`);
  const w = whereParam(where);
  if (w) params.set("where", w);
  return params;
}

/** What each data mode hands the shared calendar chrome. */
interface CalendarData {
  events: EventInput[];
  editable: boolean;
  move: (id: string, start: Date, end: Date | null, allDay: boolean) => Promise<void>;
  open: (id: string) => void;
  empty: string;
}

// ─── Entity-backed: one catalog or document ───────────────────────────────────

/** The default item headline: description for catalogs, number for documents. */
export function defaultItemTitle(row: EntityRecord, kind: "catalog" | "document" | null): string {
  const v = kind === "catalog" ? row.description ?? row.number : row.number ?? row.description;
  return v == null ? "" : String(v);
}

const DEFAULT_SUBTITLE = ["customerDisplay", "clientDisplay", "propertyDisplay", "warehouseDisplay", "description", "name"];

/** The default item second line: the usual reference displays, then description/name. */
export function defaultSubtitle(row: EntityRecord): string | undefined {
  return pickField(row, DEFAULT_SUBTITLE);
}

/** One record as a calendar item, or null when it has no start. */
export function calendarEvent(
  item: EntityRecord,
  config: {
    kind: "catalog" | "document" | null;
    start: string;
    end?: string;
    duration?: string;
    allDay: boolean;
    title?: CalendarProps["itemTitle"];
    subtitle?: CalendarProps["subtitle"];
    color?: CalendarProps["color"];
    amount?: CalendarProps["amount"];
    currency?: CalendarProps["currency"];
  }
): EventInput | null {
  const dateValue = item[config.start];
  if (typeof dateValue !== "string" || !dateValue) return null;
  const id = String(item.id ?? "");
  const primary = (config.title ? config.title(item) : defaultItemTitle(item, config.kind)) ?? "";
  const secondary = (config.subtitle ? config.subtitle(item) : defaultSubtitle(item)) ?? undefined;
  const rawAmount = config.amount ? config.amount(item) : toNumber(item.total);
  const amount = rawAmount != null && Number.isFinite(rawAmount) ? rawAmount : undefined;
  const currency = (typeof config.currency === "function" ? config.currency(item) : config.currency) ?? undefined;
  const endValue = itemEnd(item, dateValue, config.end, config.duration);
  const avatar = pickAvatar(item);
  const color = config.color?.(item) ?? undefined;
  // Catalog rows have no posted state; they draw as "posted" (solid) rather than as drafts.
  const solid = config.kind === "catalog" || Boolean(item.posted);
  const styled = color
    ? {
        backgroundColor: `color-mix(in srgb, ${color} ${solid ? 24 : 10}%, hsl(var(--card)))`,
        borderColor: color,
        textColor: "hsl(var(--foreground))",
      }
    : {};
  return {
    id,
    title: primary,
    start: dateValue,
    end: endValue,
    allDay: config.allDay,
    ...styled,
    extendedProps: {
      posted: solid,
      primary,
      secondary,
      amount,
      currency,
      hasEnd: Boolean(endValue),
      avatarUrl: avatar.url,
      avatarLabel: avatar.label,
      color,
    } satisfies EventExtendedProps,
    classNames: color ? ["fc-onno-colored"] : solid ? ["fc-onno-posted"] : ["fc-onno-draft"],
  };
}

function itemEnd(item: EntityRecord, startIso: string, endField?: string, durationField?: string): string | undefined {
  if (endField) {
    const v = item[endField];
    if (typeof v === "string" && v) return v;
  }
  if (durationField) {
    const raw = item[durationField];
    const days = typeof raw === "number" ? raw : parseFloat(String(raw ?? ""));
    if (Number.isFinite(days) && days > 0) {
      const d = new Date(startIso);
      d.setDate(d.getDate() + Math.round(days));
      return d.toISOString();
    }
  }
  return undefined;
}

function useEntityCalendar(props: CalendarProps, range: Range): CalendarData {
  const t = useMessages();
  // `where` scopes the calendar server-side (e.g. drop DRAFT/CANCELED bookings).
  const source = useEntitySource(props.entity, { dateField: props.start, where: props.where });
  const model = useModel();
  const live = useEntityVersion(source);
  const [items, setItems] = useState<EntityRecord[]>([]);
  const kind: "catalog" | "document" | null = source?.entityType === "catalog" ? "catalog" : source?.entityType === "document" ? "document" : null;
  const name = toSnakeCase(source?.entityName ?? "");
  const where = source?.where;
  const readOnly = props.readOnly === true;
  // Authored readOnly wins; RBAC write access (the model's canWrite) also locks rescheduling.
  const canWrite = props.entity ? findEntity(model, props.entity)?.canWrite : undefined;
  const editable = !readOnly && kind != null && canWrite !== false;

  const start = props.start || "date";
  const end = props.end || undefined;
  const duration = props.duration || undefined;
  // Booking-style widgets (those with an end date or duration) default to all-day so
  // events render as continuous bars and can be resized day-by-day in month view.
  const allDay = props.allDay ?? Boolean(end || duration);
  // The render functions are usually inline arrows (new every render); read them through a ref so
  // the events — and FullCalendar — only rebuild when the records do.
  const fns = useRef(props);
  fns.current = props;

  const load = useCallback(() => {
    if (kind === "document") {
      return api.listDocuments(name, format(range.from, "yyyy-MM-dd'T'00:00:00"),
        format(range.to, "yyyy-MM-dd'T'23:59:59"), where).then(setItems);
    }
    if (kind === "catalog") {
      return api.listCatalogWindow(name, catalogWindowParams(start, range.from, range.to, where)).then(setItems);
    }
    return Promise.resolve();
  }, [kind, name, where, start, range]);

  useEffect(() => { void load(); }, [load, live]);

  const events: EventInput[] = useMemo(() => {
    const f = fns.current;
    const config = {
      kind, start, end, duration, allDay,
      title: f.itemTitle, subtitle: f.subtitle, color: f.color, amount: f.amount, currency: f.currency,
    };
    return items.flatMap((item) => calendarEvent(item, config) ?? []);
  }, [items, kind, start, end, duration, allDay]);

  const move = async (id: string, startAt: Date, endAt: Date | null) => {
    const startIso = startAt.toISOString();
    const endIso = endAt ? endAt.toISOString() : undefined;
    const values = calendarMoveValues(start, end, startIso, endIso, kind === "document");
    setItems((prev) => prev.map((r) => (r.id === id ? { ...r, ...values.optimistic } : r)));
    try {
      if (kind === "catalog") await api.updateCatalogItem(name, id, values.payload);
      else await api.updateDocument(name, id, values.payload);
    } catch (err) {
      void load();
      throw err;
    }
  };

  return {
    events,
    editable,
    move,
    open: (id) => { if (kind) openRoute(`/${kind === "catalog" ? "catalogs" : "documents"}/${name}/${id}`); },
    empty: t("calendar.emptyThisMonth", { name: (source?.entityName ?? props.entity ?? "").toLowerCase() }),
  };
}

// ─── Shared chrome: header, month/week grid, agenda ───────────────────────────

export function Calendar(props: CalendarProps) {
  return (
    <Boundary label={props.title || "Calendar"}>
      <EntityCalendar {...props} />
    </Boundary>
  );
}

function EntityCalendar(props: CalendarProps) {
  const state = useCalendarState(toSnakeCase(props.entity || "calendar"));
  const data = useEntityCalendar(props, state.range);
  return <CalendarChrome props={props} state={state} data={data} />;
}

function useCalendarState(storageKey: string) {
  const isMobile = useIsMobile();
  const [view, setViewState] = useState<CalendarView>(() => {
    const saved = remembered(`${storageKey}.view`);
    return saved === "timeGridWeek" || saved === "agenda" || saved === "year" ? saved : "dayGridMonth";
  });
  // The agenda and year views own navigation (FullCalendar isn't mounted to emit datesSet), so
  // their focused date is tracked here and drives the fetch range.
  const [anchor, setAnchor] = useState(() => new Date());
  const [gridRange, setGridRange] = useState<Range>(() => ({ from: startOfMonth(new Date()), to: endOfMonth(new Date()) }));
  const agenda = isMobile || view === "agenda";
  const year = !isMobile && view === "year";
  const range = useMemo<Range>(() =>
    agenda ? { from: startOfMonth(anchor), to: endOfMonth(anchor) }
      : year ? { from: startOfYear(anchor), to: endOfYear(anchor) }
      : gridRange,
    [agenda, year, anchor, gridRange]);
  const setView = (next: CalendarView) => { setViewState(next); remember(`${storageKey}.view`, next); };
  return { isMobile, view, setView, agenda, year, anchor, setAnchor, range, setGridRange };
}

/** Every day an item covers, as `yyyy-MM-dd` keys within [first, last]. */
export function daysCovered(start: Date, end: Date | null, first: string, last: string): string[] {
  // End is exclusive; a timed item ending exactly at midnight does not spill into the next day.
  const lastInstant = end && end > start ? new Date(end.getTime() - 1) : start;
  const keys: string[] = [];
  for (let d = new Date(start.getFullYear(), start.getMonth(), start.getDate()); d <= lastInstant && keys.length < 400; d.setDate(d.getDate() + 1)) {
    const key = day(d);
    if (key >= first && key <= last) keys.push(key);
  }
  return keys;
}

/** Items grouped by every day they cover, in date order. */
function bucketByDay(events: EventInput[], range: Range): Map<string, EventInput[]> {
  const groups = new Map<string, EventInput[]>();
  const first = day(range.from), last = day(range.to);
  for (const ev of events) {
    if (!ev.start) continue;
    const start = new Date(ev.start as string);
    const end = ev.end ? new Date(ev.end as string) : null;
    for (const key of daysCovered(start, end, first, last)) {
      const bucket = groups.get(key);
      if (bucket) bucket.push(ev); else groups.set(key, [ev]);
    }
  }
  return new Map([...groups.entries()].sort(([a], [b]) => a.localeCompare(b)));
}

/** One item as the cards show it, whichever view it was picked from. */
interface CardItem {
  id: string;
  title: string;
  start: Date | null;
  end: Date | null;
  allDay: boolean;
  color: string;
  ext: EventExtendedProps;
}

function cardFromApi(event: EventApi): CardItem {
  const ext = event.extendedProps as EventExtendedProps;
  return { id: event.id, title: event.title, start: event.start, end: event.end, allDay: event.allDay,
    color: ext.color ?? (event.backgroundColor || "hsl(var(--primary))"), ext };
}

function cardFromInput(event: EventInput): CardItem {
  const ext = (event.extendedProps ?? {}) as EventExtendedProps;
  return { id: String(event.id ?? ""), title: String(event.title ?? ""),
    start: event.start ? new Date(event.start as string) : null, end: event.end ? new Date(event.end as string) : null,
    allDay: Boolean(event.allDay), color: ext.color ?? ((event.backgroundColor as string) || "hsl(var(--primary))"), ext };
}

type CardState = { anchor: HTMLElement; day?: { date: Date; items: CardItem[] }; item?: CardItem };

function CalendarChrome({ props, state, data }: {
  props: CalendarProps;
  state: ReturnType<typeof useCalendarState>;
  data: CalendarData;
}) {
  const locale = useAppLocale();
  const t = useMessages();
  const calendarRef = useRef<FullCalendar | null>(null);
  const [gridTitle, setGridTitle] = useState("");
  // The card beside a clicked item or day: a day lists its items, an item shows everything about it.
  const [card, setCard] = useState<CardState | null>(null);
  const dateLocale = locale?.startsWith("ru") ? ru : undefined;
  const readOnly = props.readOnly === true;
  // fill: the widget takes the rest of the tab and scrolls inside, so the page never scrolls.
  const fill = props.fill === true;
  const rootRef = useRef<HTMLDivElement | null>(null);
  const fillHeight = useFillHeight(rootRef, fill);
  const { isMobile, view, agenda, year, anchor, setAnchor } = state;
  const week = !agenda && !year && view === "timeGridWeek";
  const monthGrid = !agenda && !year && view === "dayGridMonth";
  const scrollRef = useRef<HTMLDivElement | null>(null);
  // What the scroller currently shows: the month in the middle of the month grid (its other days
  // are dimmed), or the first fully visible day of the week grid. Drives the title and the buttons.
  const [focus, setFocus] = useState<Date>(() => focusDate(view, anchor));
  const focusMonth = monthGrid ? startOfMonth(focus) : null;
  const title = agenda ? format(anchor, "LLLL yyyy", { locale: dateLocale })
    : year ? format(anchor, "yyyy")
    : monthGrid ? format(focus, "LLLL yyyy", { locale: dateLocale })
    : week ? weekTitle(focus, dateLocale)
    : gridTitle;
  // A scroll to apply once FullCalendar has rendered a new window: bring a date into view, or
  // compensate a window shift so the content stays put.
  const pendingScroll = useRef<{ date?: Date; dx?: number; dy?: number; smooth?: boolean; hours?: boolean } | null>({ date: focusDate(view, anchor), hours: true });
  const shifting = useRef(false);
  // Where a button-driven glide is heading, so quick repeated clicks keep stepping from there
  // rather than from wherever the animation has got to. Cleared once the user scrolls themselves.
  const glideTarget = useRef<Date | null>(null);
  const [viewportWidth, setViewportWidth] = useState(0);
  const [axisWidth, setAxisWidth] = useState(AXIS_GUESS);

  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    setViewportWidth(el.clientWidth);
    const observer = new ResizeObserver(() => setViewportWidth(el.clientWidth));
    observer.observe(el);
    return () => observer.disconnect();
  }, [monthGrid, week]);

  /** Geometry of the rendered grid, measured from the DOM. */
  const geometry = () => {
    const el = scrollRef.current;
    if (!el) return null;
    const header = (el.querySelector(".fc-col-header") as HTMLElement | null)?.offsetHeight ?? 0;
    const axis = (el.querySelector(".fc-timegrid-axis") as HTMLElement | null)?.offsetWidth ?? 0;
    const row = (el.querySelector(".fc-daygrid-body tr") as HTMLElement | null)?.offsetHeight ?? 0;
    const dayWidth = week ? (el.scrollWidth - axis) / WEEK_DAYS : 0;
    return { el, header, axis, row, dayWidth };
  };

  /** Scroll offset that brings `date` to the top (month) or left edge (week) of the viewport. */
  const offsetOf = (date: Date): { top?: number; left?: number } | null => {
    const g = geometry();
    const api = calendarRef.current?.getApi();
    if (!g || !api) return null;
    const index = differenceInCalendarDays(date, api.view.currentStart);
    if (week) return index >= 0 && index < WEEK_DAYS ? { left: index * g.dayWidth } : null;
    const cell = g.el.querySelector(`.fc-daygrid-day[data-date="${day(date)}"]`) as HTMLElement | null;
    const row = cell?.closest("tr") as HTMLElement | null;
    return row ? { top: row.offsetTop - 2 } : null;
  };

  /** Bring a date into view: glide when it is already rendered, otherwise re-centre the window. */
  const reveal = (date: Date, smooth = true) => {
    setCard(null);
    const target = focusDate(view, date);
    glideTarget.current = target;
    setFocus(target);
    const offset = offsetOf(target);
    const el = scrollRef.current;
    // Glide when visible; a hidden tab runs no animation frames, so jump there instead.
    if (offset && el) el.scrollTo({ ...offset, behavior: smooth && !document.hidden ? "smooth" : "auto" });
    else {
      pendingScroll.current = { date: target };
      calendarRef.current?.getApi().gotoDate(windowStart(view, target));
    }
  };

  const applyPendingScroll = () => {
    const pending = pendingScroll.current;
    const el = scrollRef.current;
    if (!el) { shifting.current = false; return; }
    // Size three weeks of days around the real hour-column width, so exactly seven fit.
    const axis = (el.querySelector(".fc-timegrid-axis") as HTMLElement | null)?.offsetWidth;
    if (week && axis && axis !== axisWidth) setAxisWidth(axis);
    if (!pending) { shifting.current = false; return; }
    pendingScroll.current = null;
    if (pending.dx) el.scrollLeft += pending.dx;
    if (pending.dy) el.scrollTop += pending.dy;
    if (pending.date) {
      const offset = offsetOf(pending.date);
      if (offset) el.scrollTo({ ...offset, behavior: pending.smooth ? "smooth" : "auto" });
    }
    if (pending.hours && week) {
      // Open the week on the working day.
      const slot = el.querySelector('.fc-timegrid-slot[data-time="08:00:00"]') as HTMLElement | null;
      const header = (el.querySelector(".fc-col-header") as HTMLElement | null)?.offsetHeight ?? 0;
      if (slot) el.scrollTop = slot.getBoundingClientRect().top - el.getBoundingClientRect().top + el.scrollTop - header;
    }
    shifting.current = false;
    updateFocus();
  };

  /** Recompute the title/focus from the scroll position. */
  const updateFocus = () => {
    const g = geometry();
    const api = calendarRef.current?.getApi();
    if (!g || !api) return;
    if (week) {
      if (!g.dayWidth) return;
      const index = Math.round(g.el.scrollLeft / g.dayWidth);
      setFocus(addDays(api.view.currentStart, index));
      return;
    }
    // The month owning the row in the middle of the viewport (its Thursday decides, ISO-style).
    const middle = g.el.scrollTop + g.el.clientHeight / 2;
    const rows = [...g.el.querySelectorAll(".fc-daygrid-body tr")] as HTMLElement[];
    const row = rows.find((r) => r.offsetTop + r.offsetHeight > middle) ?? rows[rows.length - 1];
    const thursday = row?.querySelectorAll(".fc-daygrid-day[data-date]")[3]?.getAttribute("data-date");
    if (thursday) setFocus(startOfMonth(new Date(`${thursday}T00:00:00`)));
  };

  // A resized week grid changes every column's width: keep the same first day at the left edge.
  useEffect(() => {
    if (!week || !viewportWidth) return;
    const offset = offsetOf(focus);
    const el = scrollRef.current;
    if (offset?.left != null && el) el.scrollLeft = offset.left;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [viewportWidth]);

  const onScroll = () => {
    if (glideTarget.current) return;
    updateFocus();
    if (shifting.current) return;
    const g = geometry();
    const api = calendarRef.current?.getApi();
    if (!g || !api) return;
    const { el } = g;
    if (week && g.dayWidth) {
      const max = el.scrollWidth - el.clientWidth;
      const edge = g.dayWidth * 2;
      if (el.scrollLeft < edge || el.scrollLeft > max - edge) {
        const forward = el.scrollLeft > max - edge;
        shifting.current = true;
        pendingScroll.current = { dx: (forward ? -1 : 1) * WEEK_SHIFT * g.dayWidth };
        api.gotoDate(addDays(api.view.currentStart, (forward ? 1 : -1) * WEEK_SHIFT));
      }
    } else if (monthGrid && g.row) {
      const max = el.scrollHeight - el.clientHeight;
      const edge = g.row * 2;
      if (el.scrollTop < edge || el.scrollTop > max - edge) {
        const forward = el.scrollTop > max - edge;
        shifting.current = true;
        pendingScroll.current = { dy: (forward ? -1 : 1) * MONTH_SHIFT * g.row };
        api.gotoDate(addWeeks(api.view.currentStart, (forward ? 1 : -1) * MONTH_SHIFT));
      }
    }
  };

  const buckets = useMemo(() => bucketByDay(data.events, state.range), [data.events, state.range]);

  const fmtAmount = (ext: EventExtendedProps) =>
    formatAmount(ext.amount as number, {
      currency: ext.currency,
      unit: props.unit,
      unitPosition: props.unitPosition,
      format: props.format,
      locale: props.locale,
    });

  const openDay = (date: Date, anchorEl: HTMLElement) => {
    const items = (buckets.get(day(date)) ?? []).map(cardFromInput);
    setCard(items.length ? { anchor: anchorEl, day: { date, items } } : null);
  };

  const renderEvent = (arg: EventContentArg): { domNodes?: Node[]; html?: string } | ReactNode => {
    const ext = arg.event.extendedProps as EventExtendedProps;
    const isMonth = arg.view.type === "dayGridMonth";
    const avatar = ext.avatarUrl ? (
      <img
        src={ext.avatarUrl}
        alt={ext.avatarLabel ?? ""}
        title={ext.avatarLabel ?? ""}
        className="h-4 w-4 shrink-0 rounded-full ring-1 ring-white/40 object-cover"
      />
    ) : null;
    if (isMonth) {
      return (
        <div className="flex w-full min-w-0 items-center gap-1.5 leading-tight">
          {avatar}
          <div className="flex min-w-0 flex-1 flex-col">
            <span className="truncate font-medium">{ext.secondary || ext.primary}</span>
            <span className="truncate text-[9px] tabular-nums opacity-70">
              {arg.timeText && `${arg.timeText} · `}
              {ext.primary}
            </span>
          </div>
        </div>
      );
    }
    return (
      <div className="flex w-full min-w-0 flex-col gap-0.5 leading-tight">
        <span className="text-[10px] tabular-nums opacity-80">{arg.timeText}</span>
        <div className="flex items-center gap-1.5">
          {avatar}
          <span className="truncate font-medium">{ext.primary}</span>
        </div>
        {ext.secondary && <span className="truncate text-[10px] opacity-80">{ext.secondary}</span>}
        {typeof ext.amount === "number" && <span className="text-[10px] tabular-nums opacity-70">{fmtAmount(ext)}</span>}
      </div>
    );
  };

  // Tap-to-open agenda: a day-grouped list instead of the dense grid (phones, or chosen on desktop).
  const renderAgenda = () => {
    if (buckets.size === 0) return <div className="onno-agenda-empty">{data.empty}</div>;
    const today = day(new Date());
    return (
      <div className="onno-calendar-agenda">
        {[...buckets.entries()].map(([key, events]) => {
          const date = new Date(`${key}T00:00:00`);
          return (
            <div key={key} className="onno-agenda-day">
              <div className={cn("onno-agenda-date", key === today && "is-today")}>
                <span className="onno-agenda-dow">{format(date, "EEE", { locale: dateLocale })}</span>
                <span className="onno-agenda-dom">{format(date, "d")}</span>
              </div>
              <div className="onno-agenda-events">
                {events.map((ev) => {
                  const item = cardFromInput(ev);
                  const ext = item.ext;
                  const line1 = ext.secondary || ext.primary;
                  const line2 = ext.secondary ? ext.primary : undefined;
                  return (
                    <button
                      key={`${key}-${item.id}`}
                      type="button"
                      className={cn("onno-agenda-event", ext.posted ? "is-posted" : "is-draft")}
                      style={ext.color ? ({ "--onno-agenda-accent": ext.color } as CSSProperties) : undefined}
                      onClick={() => item.id && data.open(item.id)}
                    >
                      <span className="onno-agenda-bar" aria-hidden />
                      {ext.avatarUrl ? <img src={ext.avatarUrl} alt="" className="onno-agenda-avatar" /> : null}
                      <span className="onno-agenda-text">
                        <span className="onno-agenda-primary">
                          {line1}
                        </span>
                        {line2 && <span className="onno-agenda-secondary">{line2}</span>}
                      </span>
                      {typeof ext.amount === "number" && <span className="onno-agenda-amount">{fmtAmount(ext)}</span>}
                    </button>
                  );
                })}
              </div>
            </div>
          );
        })}
      </div>
    );
  };

  const step = (direction: 1 | -1) => {
    setCard(null);
    if (agenda) setAnchor((a) => (direction > 0 ? addMonths(a, 1) : subMonths(a, 1)));
    else if (year) setAnchor((a) => (direction > 0 ? addYears(a, 1) : subYears(a, 1)));
    // The buttons glide by whole steps: to the next month's first week, or seven days on.
    else if (monthGrid) reveal(addMonths(glideTarget.current ?? focus, direction));
    else reveal(addDays(glideTarget.current ?? focus, direction * 7));
  };
  const goToday = () => {
    setCard(null);
    if (agenda || year) setAnchor(new Date());
    else reveal(new Date());
  };
  const switchView = (next: CalendarView) => {
    setCard(null);
    // Keep the period in view: list/year views open on the date the grid was showing, and back.
    if ((next === "agenda" || next === "year") && !agenda && !year) {
      setAnchor(new Date((state.range.from.getTime() + state.range.to.getTime()) / 2));
    }
    state.setView(next);
    if (next === "dayGridMonth" || next === "timeGridWeek") {
      const at = focusDate(next, agenda || year ? anchor : focus);
      setFocus(at);
      pendingScroll.current = { date: at, hours: true };
      // Switching between the two grids remounts FullCalendar (the scroller's axis changes).
      if (!agenda && !year) setAnchor(at);
    }
  };
  /** Jump straight to a month (or, in the year view, a year) from the period picker. */
  const jumpTo = (date: Date, wholeYear: boolean) => {
    setCard(null);
    if (agenda || (year && wholeYear)) setAnchor(date);
    else if (year) openMonth(date);
    else reveal(date);
  };
  const openMonth = (month: Date) => {
    setCard(null);
    setAnchor(month);
    state.setView("dayGridMonth");
  };

  const handleDatesSet = (arg: DatesSetArg) => {
    setCard(null);
    setGridTitle(arg.view.title);
    // FullCalendar's end is exclusive; the widget's range is inclusive days.
    state.setGridRange({ from: arg.start, to: new Date(arg.end.getTime() - 1) });
    // Let FullCalendar paint the new window before measuring it. Frames don't run in a hidden tab,
    // so a timer backs them up; whichever fires first applies the scroll once.
    let done = false;
    const run = () => { if (!done) { done = true; applyPendingScroll(); } };
    requestAnimationFrame(() => requestAnimationFrame(run));
    window.setTimeout(run, 60);
  };

  const persist = async (id: string, start: Date | null, end: Date | null, allDay: boolean, revert: () => void) => {
    if (!start || !id) { revert(); return; }
    try {
      await data.move(id, start, end, allDay);
    } catch (err) {
      revert();
      // The api layer usually toasted already; notifyError adds one only when it didn't.
      notifyError(err, { fallback: t("calendar.moveFailed") });
    }
  };
  const handleEventDrop = (arg: EventDropArg) =>
    persist(arg.event.id, arg.event.start, arg.event.end, arg.event.allDay, arg.revert);
  const handleEventResize = (arg: EventResizeDoneArg) =>
    persist(arg.event.id, arg.event.start, arg.event.end, arg.event.allDay, arg.revert);

  // "+N more" opens the widget's own day card instead of FullCalendar's popover, so every overlay
  // looks the same. Captured before FullCalendar's own click handler sees it.
  const interceptMoreLink = (e: ReactMouseEvent) => {
    const link = (e.target as HTMLElement).closest(".fc-more-link") as HTMLElement | null;
    if (!link) return;
    e.preventDefault();
    e.stopPropagation();
    const key = (link.closest("[data-date]") as HTMLElement | null)?.getAttribute("data-date");
    if (key) openDay(new Date(`${key}T00:00:00`), link);
  };

  const viewOptions = [
    { value: "year" as const, label: t("calendar.year") },
    { value: "dayGridMonth" as const, label: t("calendar.month") },
    { value: "timeGridWeek" as const, label: t("calendar.week") },
    { value: "agenda" as const, label: t("calendar.agenda") },
  ];

  return (
    <Card ref={rootRef} className={cn("onno-calendar-widget", fill && "flex flex-col overflow-hidden")}
      style={fill && fillHeight ? { height: fillHeight } : undefined}>
      <CardHeader className="flex shrink-0 flex-row flex-wrap items-center justify-between gap-3 pb-3">
        {/* Keeps its full width (e.g. "Sep 28 – Oct 4, 2026"); the toolbar wraps below instead. */}
        <div className="min-w-0 shrink-0">
          <div className="flex items-center gap-1.5">
            <CardTitle className="text-[13px] font-medium">{props.title}</CardTitle>
            <HintIcon text={props.hint} size={13} />
            {readOnly && (
              <span
                className="inline-flex items-center gap-0.5 rounded-control bg-muted px-1.5 py-0.5 text-[10px] font-medium text-muted-foreground"
                aria-label="Read only"
              >
                <Lock className="h-2.5 w-2.5" /> {t("calendar.readOnly")}
              </span>
            )}
          </div>
          <PeriodPicker
            title={title}
            focus={agenda || year ? anchor : new Date((state.range.from.getTime() + state.range.to.getTime()) / 2)}
            yearOnly={year}
            onPick={jumpTo}
          />
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {/* Phones always get the agenda, so the view toggle is desktop-only. */}
          {!isMobile && <Segmented size="sm" value={view} onChange={switchView} options={viewOptions} />}
          <Button variant="outline" size="sm" className="h-7 px-2" onClick={goToday}>
            {t("calendar.today")}
          </Button>
          <div className="flex">
            <Button variant="ghost" size="icon" className="h-7 w-7" onClick={() => step(-1)} aria-label={t("calendar.previous")}>
              <ChevronLeft className="h-3.5 w-3.5" />
            </Button>
            <Button variant="ghost" size="icon" className="h-7 w-7" onClick={() => step(1)} aria-label={t("calendar.next")}>
              <ChevronRight className="h-3.5 w-3.5" />
            </Button>
          </div>
        </div>
      </CardHeader>
      <CardContent className={cn(agenda && isMobile ? "px-0 pb-1" : "px-3 pb-3", fill && "flex min-h-0 flex-1 flex-col")}>
        {agenda ? (
          fill ? <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain">{renderAgenda()}</div> : renderAgenda()
        ) : year ? (
          <div className={cn(fill && "min-h-0 flex-1 overflow-y-auto overscroll-contain")}>
            <YearView anchor={anchor} buckets={buckets} onDay={openDay} onMonth={openMonth} />
          </div>
        ) : (
          // One native scroller per grid (remounted when the grid changes): vertical through weeks for
          // the month, both ways for the week (hours down, days across).
          <div key={week ? "week" : "month"} ref={scrollRef} onScroll={onScroll} onClickCapture={interceptMoreLink}
            // Any scrolling by hand ends a button glide; the title follows the hand again.
            onWheel={() => { glideTarget.current = null; }} onTouchStart={() => { glideTarget.current = null; }}
            onPointerDown={() => { glideTarget.current = null; }}
            className={cn("onno-calendar-scroll", week ? "is-week" : "is-month", fill && "is-fill")}
            data-focus={focusMonth ? format(focusMonth, "yyyy-MM") : undefined}>
            {focusMonth && (
              // Dim every day outside the month in focus without re-rendering the grid on scroll.
              <style>{`.onno-calendar-scroll[data-focus="${format(focusMonth, "yyyy-MM")}"] .fc-daygrid-day:not([data-date^="${format(focusMonth, "yyyy-MM")}"]) { background: hsl(var(--muted) / 0.35); } .onno-calendar-scroll[data-focus="${format(focusMonth, "yyyy-MM")}"] .fc-daygrid-day:not([data-date^="${format(focusMonth, "yyyy-MM")}"]) .fc-daygrid-day-number { color: hsl(var(--muted-foreground) / 0.6); }`}</style>
            )}
            {/* Three weeks wide, so exactly seven days fit beside the pinned hour column. */}
            <div style={week && viewportWidth ? { width: axisWidth + 3 * (viewportWidth - axisWidth) } : undefined}>
            <FullCalendar
              ref={calendarRef}
              plugins={[dayGridPlugin, timeGridPlugin, interactionPlugin]}
              initialView={view === "timeGridWeek" ? FC_VIEW.timeGridWeek : FC_VIEW.dayGridMonth}
              initialDate={windowStart(view, anchor)}
              headerToolbar={false}
              // Natural height: the surrounding frame does the scrolling, with sticky day headers.
              height="auto"
              stickyHeaderDates
              allDayMaintainDuration
              firstDay={1}
              locale={locale?.startsWith("ru") ? ruLocale : undefined}
              events={data.events}
              editable={data.editable}
              eventStartEditable={data.editable}
              eventDurationEditable={data.editable}
              eventResizableFromStart={data.editable}
              eventDisplay="auto"
              datesSet={handleDatesSet}
              eventTimeFormat={{ hour: "2-digit", minute: "2-digit", hour12: false }}
              slotLabelFormat={{ hour: "2-digit", minute: "2-digit", hour12: false }}
              eventContent={renderEvent}
              eventDragStart={() => setCard(null)}
              eventResizeStart={() => setCard(null)}
              eventDrop={handleEventDrop}
              eventResize={handleEventResize}
              eventClick={(info) => {
                info.jsEvent.preventDefault();
                if (info.event.id) setCard({ anchor: info.el, item: cardFromApi(info.event) });
              }}
              // Busy days collapse behind "+N more" instead of stretching the row.
              views={{
                dayGridWeeks: { type: "dayGrid", duration: { weeks: MONTH_WEEKS }, dateAlignment: "week", dayMaxEventRows: 3 },
                timeGridDays: { type: "timeGrid", duration: { days: WEEK_DAYS }, dateAlignment: "day", dayMaxEventRows: 2 },
              }}
              // Week columns carry their date ("MON 12", today circled); the month grid keeps weekdays.
              dayHeaderContent={(arg) => week ? (
                <span className="onno-week-head">
                  <span className="onno-week-dow">{format(arg.date, "EEE", { locale: dateLocale })}</span>
                  <span className={cn("onno-week-dom", arg.isToday && "is-today")}>{arg.date.getDate()}</span>
                </span>
              ) : arg.text}
              // The first of each month names it, so the endless grid reads clearly as it scrolls.
              dayCellContent={(arg) => (monthGrid && arg.date.getDate() === 1
                ? format(arg.date, "d MMM", { locale: dateLocale })
                : monthGrid ? String(arg.date.getDate()) : arg.dayNumberText)}
            />
            </div>
          </div>
        )}
        <Popover open={card != null} onOpenChange={(open) => { if (!open) setCard(null); }}>
          {card && <PopoverAnchor virtualRef={{ current: card.anchor }} />}
          <PopoverContent side="right" align="start" collisionPadding={12} className="w-72 overflow-hidden p-0">
            {card?.item ? (
              <EventCard
                item={card.item}
                fallbackLabel={props.title ?? ""}
                amount={(ext) => fmtAmount(ext)}
                onBack={card.day ? () => setCard({ ...card, item: undefined }) : undefined}
                onOpen={() => { data.open(card.item!.id); setCard(null); }}
                onClose={() => setCard(null)}
              />
            ) : card?.day ? (
              <DayCard date={card.day.date} items={card.day.items}
                onPick={(item) => setCard({ ...card, item })} onClose={() => setCard(null)} />
            ) : null}
          </PopoverContent>
        </Popover>
      </CardContent>
    </Card>
  );
}

/** "All day" or "10:00–10:30". */
function timeRange(item: Pick<CardItem, "start" | "end" | "allDay">, t: (key: string) => string): string {
  if (item.allDay || !item.start) return t("calendar.allDay");
  return `${format(item.start, "HH:mm")}${item.end ? `–${format(item.end, "HH:mm")}` : ""}`;
}

/** Card header shared by the day and item cards: an optional back arrow, the heading, and close. */
function CardHeading({ eyebrow, title, dot, onBack, onClose }: {
  eyebrow: string;
  title: string;
  dot?: string;
  onBack?: () => void;
  onClose: () => void;
}) {
  const t = useMessages();
  return (
    <div className="flex items-start gap-2 px-4 pb-1 pt-3">
      {onBack ? (
        <Button variant="ghost" size="icon" className="-ml-2 -mt-1 h-7 w-7 shrink-0" onClick={onBack} aria-label={t("calendar.back")}>
          <ChevronLeft className="h-3.5 w-3.5" />
        </Button>
      ) : dot ? (
        <span className="mt-1.5 size-2.5 shrink-0 rounded-full" style={{ backgroundColor: dot }} aria-hidden />
      ) : null}
      <div className="min-w-0 flex-1">
        <p className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">{eyebrow}</p>
        <p className="mt-0.5 break-words text-sm font-semibold leading-snug">{title}</p>
      </div>
      <Button variant="ghost" size="icon" className="-mr-2 -mt-1 h-7 w-7 shrink-0" onClick={onClose} aria-label={t("calendar.close")}>
        <X className="h-3.5 w-3.5" />
      </Button>
    </div>
  );
}

/** A day's items, in the same card as a single item; picking one shows its details in place. */
function DayCard({ date, items, onPick, onClose }: {
  date: Date;
  items: CardItem[];
  onPick: (item: CardItem) => void;
  onClose: () => void;
}) {
  const t = useMessages();
  const locale = useAppLocale();
  const dateLocale = locale?.startsWith("ru") ? ru : undefined;
  const sorted = [...items].sort((a, b) => Number(b.allDay) - Number(a.allDay) || (a.start?.getTime() ?? 0) - (b.start?.getTime() ?? 0));
  return (
    <div className="onno-calendar-card" role="dialog" aria-label={format(date, "EEEE d MMMM", { locale: dateLocale })}>
      <CardHeading eyebrow={format(date, "EEEE", { locale: dateLocale })} title={format(date, "d MMMM yyyy", { locale: dateLocale })} onClose={onClose} />
      <ul className="max-h-80 space-y-0.5 overflow-y-auto px-2 pb-2 pt-1">
        {sorted.map((item) => (
          <li key={item.id}>
            <button type="button" onClick={() => onPick(item)}
              className="onno-calendar-card-row flex w-full items-stretch gap-2.5 rounded-field px-2 py-1.5 text-left">
              <span className="w-[3px] shrink-0 rounded-full" style={{ backgroundColor: item.color }} aria-hidden />
              <span className="min-w-0 flex-1">
                <span className="block truncate text-xs font-medium">
                  {item.ext.primary || item.title}
                </span>
                <span className="block truncate text-[11px] tabular-nums text-muted-foreground">
                  {[timeRange(item, t), item.ext.secondary].filter(Boolean).join(" · ")}
                </span>
              </span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}

/**
 * Everything about one item, beside it: the grid truncates to fit, the card never does. Opening the
 * record is an explicit button so a click to read never navigates away.
 */
function EventCard({ item, fallbackLabel, amount, onBack, onOpen, onClose }: {
  item: CardItem;
  fallbackLabel: string;
  amount: (ext: EventExtendedProps) => string;
  onBack?: () => void;
  onOpen: () => void;
  onClose: () => void;
}) {
  const t = useMessages();
  const locale = useAppLocale();
  const dateLocale = locale?.startsWith("ru") ? ru : undefined;
  const { ext, start, end } = item;
  const minutes = timedMinutes(start, end, item.allDay);
  const lastDay = end ? new Date(end.getTime() - (item.allDay ? 1 : 0)) : null;
  const multiDay = Boolean(start && lastDay && day(lastDay) !== day(start));
  const dateText = start
    ? multiDay && lastDay
      ? `${format(start, "EEE d MMM", { locale: dateLocale })} – ${format(lastDay, "EEE d MMM", { locale: dateLocale })}`
      : format(start, "EEEE d MMMM yyyy", { locale: dateLocale })
    : "";
  return (
    <div className="onno-calendar-card" role="dialog" aria-label={ext.primary || item.title}>
      <CardHeading eyebrow={fallbackLabel} title={ext.primary || item.title} dot={item.color}
        onBack={onBack} onClose={onClose} />
      <dl className="space-y-1.5 px-4 py-3 text-xs">
        <div><dt className="sr-only">{t("calendar.when")}</dt><dd>{dateText}</dd></div>
        <div className="text-muted-foreground"><dd className="tabular-nums">{timeRange(item, t)}{minutes > 0 && ` · ${formatDuration(minutes, t)}`}</dd></div>
        {ext.secondary && <div><dd className="break-words">{ext.secondary}</dd></div>}
        {typeof ext.amount === "number" && <div className="tabular-nums"><dd>{amount(ext)}</dd></div>}
      </dl>
      <div className="border-t border-border px-4 py-2.5">
        <Button size="sm" variant="outline" className="h-7 w-full gap-1.5" onClick={onOpen}>
          <ExternalLink className="h-3.5 w-3.5" /> {t("calendar.openItem")}
        </Button>
      </div>
    </div>
  );
}

/**
 * Twelve small months, like a wall planner: a day with items carries up to three colour dots, today
 * is circled, a day opens its card and a month name opens that month.
 */
function YearView({ anchor, buckets, onDay, onMonth }: {
  anchor: Date;
  buckets: Map<string, EventInput[]>;
  onDay: (date: Date, anchor: HTMLElement) => void;
  onMonth: (month: Date) => void;
}) {
  const locale = useAppLocale();
  const t = useMessages();
  const dateLocale = locale?.startsWith("ru") ? ru : undefined;
  const year = anchor.getFullYear();
  const today = day(new Date());
  // Monday-first weekday initials (2024-01-01 was a Monday).
  const weekdays = Array.from({ length: 7 }, (_, i) => format(new Date(2024, 0, 1 + i), "EEEEE", { locale: dateLocale }));
  return (
    <div className="onno-year" role="grid" aria-label={String(year)}>
      {Array.from({ length: 12 }, (_, m) => {
        const first = new Date(year, m, 1);
        const lead = (first.getDay() + 6) % 7;
        const total = getDaysInMonth(first);
        const current = today.startsWith(format(first, "yyyy-MM"));
        return (
          <section key={m} className="onno-year-month">
            <button type="button" className={cn("onno-year-title", current && "is-current")} onClick={() => onMonth(first)}>
              {format(first, "LLLL", { locale: dateLocale })}
            </button>
            <div className="onno-year-grid">
              {weekdays.map((w, i) => <span key={`w${i}`} className="onno-year-dow" aria-hidden>{w}</span>)}
              {Array.from({ length: lead }, (_, i) => <span key={`l${i}`} aria-hidden />)}
              {Array.from({ length: total }, (_, d) => {
                const date = new Date(year, m, d + 1);
                const key = day(date);
                const items = buckets.get(key) ?? [];
                const colors = [...new Set(items.map((ev) => cardFromInput(ev)).map((i) => i.color))].slice(0, 3);
                return (
                  <button key={key} type="button" disabled={!items.length}
                    className={cn("onno-year-day", key === today && "is-today", items.length > 0 && "has-items")}
                    aria-label={`${format(date, "d MMMM", { locale: dateLocale })}${items.length ? ` · ${t("calendar.itemCount", { count: items.length })}` : ""}`}
                    onClick={(e) => onDay(date, e.currentTarget)}>
                    <span className="onno-year-num">{d + 1}</span>
                    <span className="onno-year-dots" aria-hidden>
                      {colors.map((c) => <span key={c} style={{ backgroundColor: c }} />)}
                    </span>
                  </button>
                );
              })}
            </div>
          </section>
        );
      })}
    </div>
  );
}

/**
 * The period title doubles as a jump picker: step the year, pick a month. In the year view picking a
 * year applies at once and a month opens that month.
 */
function PeriodPicker({ title, focus, yearOnly, onPick }: {
  title: string;
  focus: Date;
  yearOnly: boolean;
  onPick: (date: Date, wholeYear: boolean) => void;
}) {
  const t = useMessages();
  const locale = useAppLocale();
  const dateLocale = locale?.startsWith("ru") ? ru : undefined;
  const [open, setOpen] = useState(false);
  const [pickerYear, setPickerYear] = useState(focus.getFullYear());
  const now = new Date();
  const changeYear = (next: number) => {
    setPickerYear(next);
    if (yearOnly) onPick(new Date(next, 0, 1), true);
  };
  return (
    <Popover open={open} onOpenChange={(next) => { setOpen(next); if (next) setPickerYear(focus.getFullYear()); }}>
      <PopoverTrigger asChild>
        <button type="button" className="onno-period-trigger" aria-label={t("calendar.jumpTo")}>
          <span className="whitespace-nowrap" aria-live="polite">{title}</span>
          <ChevronDown className="h-3 w-3 shrink-0 opacity-70" aria-hidden />
        </button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-64 p-3">
        <div className="mb-2 flex items-center justify-between">
          <Button variant="ghost" size="icon" className="h-7 w-7" onClick={() => changeYear(pickerYear - 1)} aria-label={t("calendar.previous")}>
            <ChevronLeft className="h-3.5 w-3.5" />
          </Button>
          <span className="text-sm font-semibold tabular-nums" aria-label={t("calendar.yearPicker")}>{pickerYear}</span>
          <Button variant="ghost" size="icon" className="h-7 w-7" onClick={() => changeYear(pickerYear + 1)} aria-label={t("calendar.next")}>
            <ChevronRight className="h-3.5 w-3.5" />
          </Button>
        </div>
        <div className="grid grid-cols-3 gap-1" role="group" aria-label={t("calendar.monthPicker")}>
          {Array.from({ length: 12 }, (_, m) => {
            const month = new Date(pickerYear, m, 1);
            const selected = !yearOnly && focus.getFullYear() === pickerYear && focus.getMonth() === m;
            const current = now.getFullYear() === pickerYear && now.getMonth() === m;
            return (
              <button key={m} type="button"
                className={cn("onno-period-month", selected && "is-selected", current && "is-current")}
                onClick={() => { onPick(month, false); setOpen(false); }}>
                {format(month, "LLL", { locale: dateLocale })}
              </button>
            );
          })}
        </div>
      </PopoverContent>
    </Popover>
  );
}
