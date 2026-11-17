import { parseISO, isValid } from "date-fns";
import { localeOutsideReact } from "./messages-outside-react";
import { configuredTimeZone, isInstantString } from "./time-zone";

/**
 * How a value displays, as typed data: an app writes `{ money: "USD" }` or `{ date: "medium" }`,
 * never a pattern string the UI has to parse.
 *
 *   { money: "USD" }                                 // $1,250.00
 *   { number: { decimals: 0 } }                      // 1,250
 *   { number: { decimals: 2, grouping: false } }     // 1250.00
 *   { percent: { decimals: 1 } }                     // 0.125 → 12.5%
 *   { date: "medium" }                               // Jul 4, 2026
 *   { date: { day: "2-digit", month: "short" } }     // any Intl.DateTimeFormat options
 */
export type ValueFormat =
  | { money: string }
  | { number: { decimals?: number; grouping?: boolean } }
  | { percent: { decimals?: number } }
  | { date: DateStyle | Intl.DateTimeFormatOptions };

/** A date's length in the app's locale (`Intl.DateTimeFormat` `dateStyle`). */
export type DateStyle = "short" | "medium" | "long" | "full";

/** The control a field edits with (and, for media and colors, how its list cell shows), one name per control. */
export type FieldInput = "textarea" | "switch" | "image" | "images" | "file" | "color" | "avatar" | "map";

/** A field's share of the form row: the whole row, or half of it (two half fields pair up). */
export type FieldWidth = "full" | "half";

/** Whether a column renders its value as an image thumbnail rather than text. */
export function isImageInput(input: FieldInput | undefined): boolean {
  return input === "image" || input === "avatar";
}

/** A canonical stored-media/object URL that can be shown as an image. */
export function looksLikeImageUrl(value: string): boolean {
  return value.startsWith("/") || /^https?:\/\//i.test(value);
}

/**
 * A raw value (as the list feed sends it) in a {@link ValueFormat}. Null when there is no format or
 * the value does not fit it (a non-number under a number format), so the caller keeps the raw text.
 */
export function formatValue(raw: string, format: ValueFormat | undefined): string | null {
  if (!format || raw === "") return null;
  const locale = localeOutsideReact();
  try {
    if ("date" in format) {
      const d = parseDate(raw);
      if (!d) return null;
      const options: Intl.DateTimeFormatOptions = typeof format.date === "string" ? { dateStyle: format.date } : { ...format.date };
      // An Instant renders in the configured display zone, not the browser's.
      if (isInstantString(raw.includes("T") ? raw : raw.replace(" ", "T")) && configuredTimeZone()) options.timeZone = configuredTimeZone();
      return new Intl.DateTimeFormat(locale, options).format(d);
    }
    const n = Number(raw);
    if (!Number.isFinite(n)) return null;
    if ("money" in format) {
      return new Intl.NumberFormat(locale, { style: "currency", currency: format.money }).format(n);
    }
    if ("percent" in format) {
      const decimals = format.percent.decimals;
      return new Intl.NumberFormat(locale, {
        style: "percent",
        minimumFractionDigits: decimals ?? 0,
        maximumFractionDigits: decimals ?? 2,
      }).format(n);
    }
    const { decimals, grouping } = format.number;
    return new Intl.NumberFormat(locale, {
      useGrouping: grouping ?? true,
      ...(decimals === undefined ? {} : { minimumFractionDigits: decimals, maximumFractionDigits: decimals }),
    }).format(n);
  } catch {
    return null;
  }
}

// An ISO date ("2026-06-05"), an ISO date-time / offset date-time ("2026-06-05T14:30:00+00:00") or a
// JDBC timestamp with a space separator ("2026-06-05 14:30:00.0"). parseISO keeps a bare date local.
function parseDate(raw: string): Date | null {
  const d = parseISO(raw.includes("T") ? raw : raw.replace(" ", "T"));
  return isValid(d) ? d : null;
}

// A machine timestamp: full ISO/JDBC date-time ("2026-07-05T00:58:48.232+00:00",
// "2026-07-05 00:58:48.0"). Deliberately requires a time part — a bare "2026-07-05" date is
// already readable, and business text that merely starts with digits must never be rewritten.
const TIMESTAMP = /^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?$/;

/**
 * The default rendering for a timestamp value in a column with no format: the configured locale
 * ({@code onno.ui.locale}, else the browser's), medium date + short time ("Jul 5, 2026, 12:58 AM").
 * Raw ISO with millis and a zone offset is machine output, not a display value — no list cell
 * should ever show it. Returns null when the value isn't a full date-time, so the caller keeps the
 * raw text.
 */
export function formatTimestampDefault(raw: string): string | null {
  if (!TIMESTAMP.test(raw)) return null;
  const d = parseDate(raw);
  if (!d) return null;
  // An absolute moment (an Instant: explicit Z/offset) renders in the display zone —
  // onno.ui.time-zone, else the browser's. A wall-clock value has no offset and is never shifted.
  const timeZone = isInstantString(raw) ? configuredTimeZone() : undefined;
  return new Intl.DateTimeFormat(localeOutsideReact(), { dateStyle: "medium", timeStyle: "short", timeZone }).format(d);
}
