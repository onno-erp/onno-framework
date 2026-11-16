import { getLocalTimeZone, parseAbsolute, parseDateTime, toZoned } from "@internationalized/date";

/**
 * The display time zone for `Instant` fields.
 *
 * An `Instant` travels as an absolute ISO-8601 UTC moment ("2026-01-16T01:00:00Z"). It is shown and
 * edited as wall time in one zone: `onno.ui.time-zone` when the deployment configured it (every
 * viewer of a single-site app sees the same clock), otherwise the viewer's browser zone.
 * `LocalDate` / `LocalDateTime` / `LocalTime` are zone-less wall values and never pass through here.
 *
 * A module-level store rather than React context, like the widget locale: cell formatters and
 * widget plugins run outside the provider tree.
 */
let configured: string | undefined;

/** Called by MessagesProvider with `timeZone` from `/api/config`; ignores an id the browser rejects. */
export function publishDisplayTimeZone(zone: string | undefined): void {
  if (!zone) {
    configured = undefined;
    return;
  }
  try {
    new Intl.DateTimeFormat(undefined, { timeZone: zone });
    configured = zone;
  } catch {
    configured = undefined;
  }
}

/** The configured display zone, or undefined when instants follow the browser zone. */
export function configuredTimeZone(): string | undefined {
  return configured;
}

/** The zone instants are rendered in: the configured one, else the browser's. */
export function displayTimeZone(): string {
  return configured ?? getLocalTimeZone();
}

/** Does this value carry an explicit offset — i.e. is it an absolute moment, not a wall time? */
export function isInstantString(value: string): boolean {
  return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})$/.test(value);
}

/**
 * An absolute ISO moment as wall time in the display zone ("2026-01-16T04:00:00"), or "" when the
 * value is empty or unparseable.
 */
export function instantToWall(iso: string | null | undefined, zone: string = displayTimeZone()): string {
  if (!iso) return "";
  try {
    const z = parseAbsolute(iso, zone);
    const pad = (n: number, w = 2) => String(n).padStart(w, "0");
    return `${pad(z.year, 4)}-${pad(z.month)}-${pad(z.day)}T${pad(z.hour)}:${pad(z.minute)}:${pad(z.second)}`;
  } catch {
    return "";
  }
}

/**
 * Wall time in the display zone ("2026-01-16T04:00") as an absolute ISO UTC moment
 * ("2026-01-16T01:00:00.000Z"), or "" when empty/unparseable. A wall time skipped by a DST jump
 * resolves forward and an ambiguous one to its earlier occurrence, as a calendar app does.
 */
export function wallToInstant(wall: string | null | undefined, zone: string = displayTimeZone()): string {
  if (!wall) return "";
  try {
    return toZoned(parseDateTime(wall), zone).toAbsoluteString();
  } catch {
    return "";
  }
}
