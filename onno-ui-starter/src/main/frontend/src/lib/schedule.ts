import { addDays, format } from "date-fns";
import type { EntityRecord } from "@/lib/types";

/**
 * Shared, framework-free pieces of the calendar and timeline list views (ListSpec.calendar /
 * ListSpec.timeline): the descriptor shapes, temporal parsing/formatting that round-trips the
 * server's value shape, the list-feed window query, and the timeline's geometry.
 */

/** ListSpec.calendar as the list descriptor carries it: each field is a row key + a data column. */
export type ListCalendarConfig = {
  startField: string;
  startColumn: string;
  endField?: string;
  endColumn?: string;
  titleField?: string;
  colorField?: string;
  mode?: "month" | "week" | "day";
  defaultView?: boolean;
};

/** ListSpec.timeline as the list descriptor carries it. */
export type ListTimelineConfig = {
  startField: string;
  startColumn: string;
  endField: string;
  endColumn: string;
  laneField?: string;
  laneColumn?: string;
  /** "catalogs" | "documents": where the lane reference's records are listed from. */
  laneKind?: string;
  /** Logical name of the lane reference's target entity. */
  laneTarget?: string;
  laneLabel?: string;
  titleField?: string;
  colorField?: string;
  slotMinutes?: number;
  startHour?: number;
  endHour?: number;
  defaultView?: boolean;
};

const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/;
const ZONED = /(Z|[+-]\d{2}:?\d{2})$/;

/** Whether a value is a bare calendar date (a LocalDate field), as opposed to a date-time. */
export function isDateOnly(value: unknown): boolean {
  return typeof value === "string" && DATE_ONLY.test(value);
}

/**
 * Parse a feed value into a Date. A bare date is local midnight (never UTC midnight, which would
 * land on the previous day west of Greenwich); a zone-less date-time is wall-clock local time.
 */
export function parseTemporal(value: unknown): Date | null {
  if (typeof value !== "string" || !value) return null;
  if (DATE_ONLY.test(value)) {
    const [y, m, d] = value.split("-").map(Number);
    return new Date(y, m - 1, d);
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

/**
 * Format a Date for the update command in the same shape the field already had: a bare date for a
 * LocalDate, an ISO instant for a zoned value, otherwise a zone-less local date-time.
 */
export function writeTemporal(date: Date, like: unknown): string {
  if (isDateOnly(like)) return format(date, "yyyy-MM-dd");
  if (typeof like === "string" && ZONED.test(like)) return date.toISOString();
  return format(date, "yyyy-MM-dd'T'HH:mm:ss");
}

/**
 * The list-feed queries that return every record overlapping the inclusive day window
 * {@code [from, to]}, on top of the list's own query (search, declared filters, base filter).
 *
 * <p>Without an end column a record is in the window when its start is. With one, a record
 * overlaps when it starts on or before the last day and ends on or after the first — plus a second
 * query for records that start inside the window but have no end value yet, which the range
 * comparison on the end column would silently drop. Bare dates make the server include the whole
 * last day.</p>
 */
export function windowQueries(baseQuery: string, startColumn: string, endColumn: string | undefined, from: Date, to: Date): URLSearchParams[] {
  const first = format(from, "yyyy-MM-dd");
  const last = format(to, "yyyy-MM-dd");
  const startsInside = new URLSearchParams(baseQuery);
  startsInside.append("ge", `${startColumn},${first}`);
  startsInside.append("le", `${startColumn},${last}`);
  if (!endColumn) return [startsInside];
  const overlaps = new URLSearchParams(baseQuery);
  overlaps.append("le", `${startColumn},${last}`);
  overlaps.append("ge", `${endColumn},${first}`);
  return [overlaps, startsInside];
}

/** A reference value's id: the feed sends a bare UUID string (or, storage-shaped, an object). */
export function refId(value: unknown): string {
  if (value == null) return "";
  if (typeof value === "object") return String((value as { id?: unknown }).id ?? "");
  return String(value);
}

/** An entry's title: the configured field's display value, else a system identifier. */
export function rowTitle(row: EntityRecord, titleField?: string): string {
  const candidates = [
    titleField && (row[`${titleField}Display`] ?? row[titleField]),
    row.description,
    row.number,
    row.code,
  ];
  for (const c of candidates) {
    if (c != null && typeof c !== "object" && String(c).trim() !== "") return String(c);
  }
  return "";
}

/**
 * An entry's accent colour from the configured colour field: an enumeration value's authored
 * colour when it has one, else a stable hue derived from the value, else null (theme default).
 */
export function rowAccent(row: EntityRecord, colorField?: string): string | null {
  if (!colorField) return null;
  const authored = row[`${colorField}Color`];
  if (typeof authored === "string" && authored.trim()) return authored;
  const value = refId(row[colorField]);
  if (!value) return null;
  let h = 0;
  for (let i = 0; i < value.length; i++) h = (h * 31 + value.charCodeAt(i)) >>> 0;
  return `hsl(${(h * 47) % 360} 65% 45%)`;
}

/** Round minutes to the nearest multiple of the slot step. */
export function snapMinutes(minutes: number, step: number): number {
  return Math.round(minutes / step) * step;
}

/** Minutes since local midnight of {@code day} (negative before it, past 1440 after it). */
export function minutesFromDay(date: Date, day: Date): number {
  return Math.round((date.getTime() - day.getTime()) / 60000);
}

/** The Date at {@code minutes} past local midnight of {@code day} (DST-safe for whole days). */
export function dateAtMinutes(day: Date, minutes: number): Date {
  const wholeDays = Math.floor(minutes / 1440);
  const rest = minutes - wholeDays * 1440;
  const base = wholeDays ? addDays(day, wholeDays) : day;
  return new Date(base.getFullYear(), base.getMonth(), base.getDate(), Math.floor(rest / 60), rest % 60);
}

export type Span = { id: string; start: number; end: number };

/**
 * Side-by-side placement for overlapping blocks in one lane: each block gets a column index and
 * the column count of its overlap cluster, so concurrent appointments share the lane's width
 * instead of covering each other. Greedy first-fit over blocks sorted by start.
 */
export function overlapLayout(spans: Span[]): Map<string, { column: number; columns: number }> {
  const out = new Map<string, { column: number; columns: number }>();
  const sorted = [...spans].sort((a, b) => a.start - b.start || b.end - a.end);
  let cluster: { span: Span; column: number }[] = [];
  let clusterEnd = -Infinity;
  const flush = () => {
    const columns = cluster.reduce((max, c) => Math.max(max, c.column + 1), 0);
    for (const c of cluster) out.set(c.span.id, { column: c.column, columns });
    cluster = [];
  };
  for (const span of sorted) {
    if (span.start >= clusterEnd) {
      flush();
      clusterEnd = -Infinity;
    }
    const taken = new Set(cluster.filter((c) => c.span.end > span.start).map((c) => c.column));
    let column = 0;
    while (taken.has(column)) column++;
    cluster.push({ span, column });
    clusterEnd = Math.max(clusterEnd, span.end);
  }
  flush();
  return out;
}

/**
 * Where a dragged block lands. A move keeps the block's length and snaps its start to the slot
 * grid inside the visible day; a resize keeps the start and snaps the end, never shorter than one
 * slot.
 */
export function dragResult(
  mode: "move" | "resize",
  origin: { start: number; end: number },
  deltaMinutes: number,
  step: number,
  bounds: { start: number; end: number },
): { start: number; end: number } {
  if (mode === "resize") {
    const end = Math.min(bounds.end, Math.max(origin.start + step, snapMinutes(origin.end + deltaMinutes, step)));
    return { start: origin.start, end };
  }
  const length = origin.end - origin.start;
  const latest = Math.max(bounds.start, bounds.end - length);
  const start = Math.min(latest, Math.max(bounds.start, snapMinutes(origin.start + deltaMinutes, step)));
  return { start, end: start + length };
}
