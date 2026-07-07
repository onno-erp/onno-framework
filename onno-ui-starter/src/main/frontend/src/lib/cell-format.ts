import { format as formatWithPattern, parseISO, isValid } from "date-fns";

/**
 * Client-side display formatting for table cells, driven by a column's `.format(...)` and
 * `.widget(...)` hints from the Java DSL. Kept in lock-step with the server-side
 * {@code ValueFormat.java} so a column looks identical in the React list and the DivKit detail.
 */

/** Does this column render its value as an image thumbnail rather than text? */
export function isImageWidget(widget?: string): boolean {
  return /^(image|photo|avatar)$/i.test(widget ?? "");
}

export function isAvatarWidget(widget?: string): boolean {
  return /^avatar$/i.test(widget ?? "");
}

/** A value that can be shown as an <img>: a data: URL or an http(s) URL. */
export function looksLikeImageUrl(value: string): boolean {
  return value.startsWith("data:") || /^https?:\/\//i.test(value);
}

const NUMBER_KEYWORD = /^(integer|decimal|percent|currency(:[a-zA-Z]{3})?)$/;

/**
 * Apply a `.format(...)` hint to a raw value string. Returns the formatted text, or null when the
 * hint is blank or the value doesn't fit the spec (so the caller keeps the raw text). The hint is
 * interpreted by shape: a number spec ("integer" | "decimal" | "percent" | "currency[:EUR]" | a
 * "#,##0.00" pattern), otherwise a date pattern ("dd-MM-yy").
 */
export function applyFormat(raw: string, format?: string): string | null {
  const fmt = (format ?? "").trim();
  if (!fmt || !raw) return null;
  return isNumberSpec(fmt) ? formatNumber(raw, fmt) : formatDate(raw, fmt);
}

// A machine timestamp: full ISO/JDBC date-time ("2026-07-05T00:58:48.232+00:00",
// "2026-07-05 00:58:48.0"). Deliberately requires a time part — a bare "2026-07-05" date is
// already readable, and business text that merely starts with digits must never be rewritten.
const TIMESTAMP = /^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?$/;

/**
 * The default rendering for a timestamp value in a column with no authored `.format(...)`: the
 * user's locale, medium date + short time ("Jul 5, 2026, 12:58 AM"). Raw ISO with millis and a
 * zone offset is machine output, not a display value — no list cell should ever show it. Returns
 * null when the value isn't a full date-time, so the caller keeps the raw text.
 */
export function formatTimestampDefault(raw: string): string | null {
  if (!TIMESTAMP.test(raw)) return null;
  const d = parseISO(raw.includes("T") ? raw : raw.replace(" ", "T"));
  if (!isValid(d)) return null;
  return new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(d);
}

function isNumberSpec(fmt: string): boolean {
  return NUMBER_KEYWORD.test(fmt) || /[#0]/.test(fmt);
}

function formatNumber(raw: string, fmt: string): string | null {
  const n = Number(raw);
  if (!Number.isFinite(n)) return null;
  const lower = fmt.toLowerCase();
  try {
    if (lower === "integer") {
      return new Intl.NumberFormat(undefined, { maximumFractionDigits: 0 }).format(n);
    }
    if (lower === "decimal") {
      return new Intl.NumberFormat(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(n);
    }
    if (lower === "percent") {
      return new Intl.NumberFormat(undefined, { style: "percent", maximumFractionDigits: 2 }).format(n);
    }
    if (lower.startsWith("currency")) {
      const code = lower.includes(":") ? fmt.slice(fmt.indexOf(":") + 1).trim().toUpperCase() : "USD";
      try {
        return new Intl.NumberFormat(undefined, { style: "currency", currency: code }).format(n);
      } catch {
        return new Intl.NumberFormat(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(n);
      }
    }
    // A DecimalFormat-style pattern: decimal places from after ".", grouping if it contains ",".
    const dot = fmt.indexOf(".");
    const decimals = dot >= 0 ? fmt.length - dot - 1 : 0;
    return new Intl.NumberFormat(undefined, {
      useGrouping: fmt.includes(","),
      minimumFractionDigits: decimals,
      maximumFractionDigits: decimals,
    }).format(n);
  } catch {
    return null;
  }
}

function formatDate(raw: string, fmt: string): string | null {
  // Accept an ISO date ("2026-06-05"), an ISO date-time / offset date-time
  // ("2026-06-05T14:30:00+00:00"), or a JDBC timestamp with a space separator
  // ("2026-06-05 14:30:00.0"). parseISO keeps a bare date local (no timezone shift).
  const norm = raw.includes("T") ? raw : raw.replace(" ", "T");
  const d = parseISO(norm);
  if (!isValid(d)) return null;
  try {
    return formatWithPattern(d, normalizeDatePattern(fmt));
  } catch {
    return null;
  }
}

/** date-fns shares dd/MM/yyyy/HH/mm/ss with Java; accept uppercase D→day and Y→year. */
function normalizeDatePattern(p: string): string {
  return p.replace(/D/g, "d").replace(/Y/g, "y");
}
