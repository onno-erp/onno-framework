/**
 * The dashboard's time-range domain — a small, framework-agnostic core (no React, no charts) shared
 * by the time picker, the row-windowing filter, and granularity sizing. A range is one of three
 * shapes; everything downstream resolves it to absolute instants via {@link resolveRange}, so adding
 * a preset — even a sub-minute one — never touches the consumers. Reusable anywhere a "last N" / from–
 * to window is needed (charts, logs, registers, the calendar), not just the chart widget.
 */

/** Duration units. Grafana's convention so they don't collide: `m` = minute, `M` = month. */
export type TimeUnit = "s" | "m" | "h" | "d" | "w" | "M" | "y";

export type RelativeRange = { kind: "relative"; amount: number; unit: TimeUnit };
export type AbsoluteRange = { kind: "absolute"; from?: string; to?: string };
export type AllTimeRange = { kind: "all" };

/** A window of time: a rolling "last N <unit>", a fixed from/to, or unbounded. */
export type TimeRange = RelativeRange | AbsoluteRange | AllTimeRange;

/** A labelled quick-pick bound to a relative (or all-time) range; the picker renders a list of these. */
export interface RangePreset {
  id: string;
  label: string;
  range: RelativeRange | AllTimeRange;
}

const MS: Record<TimeUnit, number> = {
  s: 1_000,
  m: 60_000,
  h: 3_600_000,
  d: 86_400_000,
  w: 604_800_000,
  M: 2_592_000_000, // 30d — approximate, fine for a rolling window
  y: 31_536_000_000, // 365d
};

const DURATION_RE = /^(\d+)([smhdwMy])$/;
const DATE_ONLY_RE = /^\d{4}-\d{2}-\d{2}$/;

/** Parse a compact id like `15m`, `24h`, `90d`, `1y`, or `all` into a range; null if unrecognized. */
export function parseRangeId(id: string): RelativeRange | AllTimeRange | null {
  if (id === "all") return { kind: "all" };
  const m = DURATION_RE.exec(id);
  if (!m) return null;
  return { kind: "relative", amount: Number(m[1]), unit: m[2] as TimeUnit };
}

/** A built preset for an id; the id doubles as its short label (`All` for all-time). */
export function buildPreset(id: string): RangePreset | null {
  const range = parseRangeId(id);
  if (!range) return null;
  return { id, label: id === "all" ? "All" : id, range };
}

/** The default quick-pick ladder — sub-minute through all-time. Overridable per dashboard. */
export const DEFAULT_PRESET_IDS = ["15m", "1h", "6h", "24h", "7d", "30d", "90d", "1y", "all"];
export const DEFAULT_PRESETS: RangePreset[] = DEFAULT_PRESET_IDS.map(buildPreset).filter(
  (p): p is RangePreset => p != null
);

/** The range used until a dashboard configures its own default (and the "clear custom" target). */
export const FALLBACK_RANGE: RelativeRange = { kind: "relative", amount: 30, unit: "d" };

/** Build a preset list from a dashboard's comma-separated id config; falls back to the defaults. */
export function presetsFromConfig(csv?: string): RangePreset[] {
  if (!csv) return DEFAULT_PRESETS;
  const list = csv
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean)
    .map(buildPreset)
    .filter((p): p is RangePreset => p != null);
  return list.length ? list : DEFAULT_PRESETS;
}

/** Find a preset by id within a list. */
export function presetById(presets: RangePreset[], id: string): RangePreset | undefined {
  return presets.find((p) => p.id === id);
}

const UNIT_WORD: Record<TimeUnit, [string, string]> = {
  s: ["second", "seconds"],
  m: ["minute", "minutes"],
  h: ["hour", "hours"],
  d: ["day", "days"],
  w: ["week", "weeks"],
  M: ["month", "months"],
  y: ["year", "years"],
};

/** A human label for a quick-pick: `30d` → "Last 30 days", `1h` → "Last hour", `all` → "All time". */
export function presetLabel(preset: RangePreset): string {
  if (preset.range.kind === "all") return "All time";
  const { amount, unit } = preset.range;
  const [one, many] = UNIT_WORD[unit];
  return amount === 1 ? `Last ${one}` : `Last ${amount} ${many}`;
}

/** Whether two ranges denote the same window — used to light up the active preset button. */
export function sameRange(a: TimeRange, b: TimeRange): boolean {
  if (a.kind !== b.kind) return false;
  if (a.kind === "relative" && b.kind === "relative")
    return a.amount === b.amount && a.unit === b.unit;
  if (a.kind === "absolute" && b.kind === "absolute") return a.from === b.from && a.to === b.to;
  return true; // both "all"
}

function bound(s: string | undefined, end: boolean): number {
  if (!s) return end ? Infinity : -Infinity;
  const t = Date.parse(s);
  if (Number.isNaN(t)) return end ? Infinity : -Infinity;
  // A date-only end bound includes the whole day; a datetime bound (e.g. from drag-zoom) is exact.
  return end && DATE_ONLY_RE.test(s) ? t + MS.d : t;
}

/**
 * Resolve any range to absolute epoch-millis `{ from, to }` — the single function the row filter and
 * granularity sizing share. Relative ranges anchor to `now` (defaults to the call time); an all-time
 * range resolves to the open interval `(-∞, +∞)`.
 */
export function resolveRange(range: TimeRange, now = Date.now()): { from: number; to: number } {
  if (range.kind === "all") return { from: -Infinity, to: Infinity };
  if (range.kind === "absolute") return { from: bound(range.from, false), to: bound(range.to, true) };
  return { from: now - range.amount * MS[range.unit], to: now };
}

/** The window's span in days — for choosing bucket granularity. `spanDaysForAll` sizes all-time. */
export function rangeSpanDays(range: TimeRange, spanDaysForAll: number, now = Date.now()): number {
  if (range.kind === "all") return spanDaysForAll;
  const { from, to } = resolveRange(range, now);
  return Math.max(1 / 1440, (to - from) / MS.d); // floor at one minute, expressed in days
}
