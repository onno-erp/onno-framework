import type { EntityRecord } from "./types";

/**
 * Shared value-formatting helpers for the data widgets (list, calendar, chart, and
 * the count/metric card). Centralising them keeps number/currency rendering and the
 * `{field}` title templating consistent across every widget, and configurable from the
 * Java DSL via `config(...)` rather than hardcoded ($ + toFixed(2)).
 */

export interface NumberFormatOptions {
  /** ISO 4217 code (e.g. "EUR"); when set, render as currency. */
  currency?: string;
  /**
   * An explicit unit/symbol label (e.g. "E", "kg", "pcs", "€"). Unlike {@link currency}
   * it is not validated against ISO 4217, and it takes precedence over `currency`: the
   * number renders plainly and the label is placed on the {@link unitPosition} side.
   */
  unit?: string;
  /**
   * Where the {@link unit} sits relative to the number. "suffix" (default) → "100 E";
   * "prefix" → "$100". Suffixes get a separating space, prefixes do not.
   */
  unitPosition?: "prefix" | "suffix" | string;
  /** "integer" | "decimal" — fraction-digit policy when not a currency. */
  format?: string;
  /** BCP-47 locale; defaults to the runtime/browser locale. */
  locale?: string;
}

/** Place a unit label on either side of an already-formatted number. */
function attachUnit(num: string, unit: string, position?: string): string {
  return position === "prefix" ? `${unit}${num}` : `${num} ${unit}`;
}

/** A plain grouped number, honouring the integer/decimal fraction-digit policy. */
function formatPlain(value: number, opts: NumberFormatOptions): string {
  const locale = opts.locale || undefined;
  if (opts.format === "integer") {
    return new Intl.NumberFormat(locale, { maximumFractionDigits: 0 }).format(value);
  }
  return new Intl.NumberFormat(locale, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(value);
}

/** Coerce a cell value to a number, or null when it isn't numeric. */
export function toNumber(value: unknown): number | null {
  if (typeof value === "number") return Number.isFinite(value) ? value : null;
  if (typeof value === "string" && value.trim() !== "") {
    const n = Number(value);
    return Number.isFinite(n) ? n : null;
  }
  return null;
}

/** Format a number as currency (when a code is given) or a grouped decimal/integer. */
export function formatNumber(value: number, opts: NumberFormatOptions = {}): string {
  const locale = opts.locale || undefined;
  // An explicit unit wins over a currency code: render a plain grouped number and place
  // the label on the configured side (suffix by default, e.g. "100 E"; prefix → "$100").
  const unit = opts.unit?.trim();
  if (unit) {
    return attachUnit(formatPlain(value, opts), unit, opts.unitPosition);
  }
  if (opts.currency) {
    try {
      return new Intl.NumberFormat(locale, { style: "currency", currency: opts.currency }).format(value);
    } catch {
      // Invalid currency code — fall through to plain number formatting.
    }
  }
  return formatPlain(value, opts);
}

/**
 * Format a money amount with graceful fallback: a configured/per-row currency renders
 * via {@link formatNumber}; with no currency it keeps the legacy `$` + 2-decimals so
 * existing dashboards are unchanged.
 */
export function formatAmount(value: number, opts: NumberFormatOptions = {}): string {
  if (opts.unit?.trim() || opts.currency) return formatNumber(value, opts);
  return `$${value.toFixed(2)}`;
}

/** Split a comma-separated config value ("a, b ,c") into trimmed field names. */
export function splitFields(cfg: string | undefined | null): string[] {
  return cfg ? cfg.split(",").map((s) => s.trim()).filter(Boolean) : [];
}

/** First field whose value is a non-empty string/number. */
export function pickField(row: EntityRecord, fields: string[]): string | undefined {
  for (const f of fields) {
    const v = row[f];
    if (typeof v === "string" && v.trim()) return v;
    if (typeof v === "number") return String(v);
  }
  return undefined;
}

/** Expand a "{guest} — {property}" template against a row; unknown fields render empty. */
export function applyTemplate(template: string, row: EntityRecord): string {
  return template
    .replace(/\{([^}]+)\}/g, (_, key: string) => {
      const v = row[key.trim()];
      return v == null ? "" : String(v);
    })
    .replace(/\s{2,}/g, " ")
    .trim();
}

/**
 * Resolve a composite label from config: an explicit `{field}` template wins, else a
 * comma-list of fields joined by " — " (non-empty only), else the first of `fallbacks`.
 */
export function resolveText(
  row: EntityRecord,
  opts: { template?: string | null; fields?: string[]; fallbacks?: string[] }
): string {
  if (opts.template) return applyTemplate(opts.template, row);
  if (opts.fields && opts.fields.length) {
    const parts = opts.fields
      .map((f) => row[f])
      .filter((v) => v != null && String(v).trim() !== "")
      .map(String);
    if (parts.length) return parts.join(" — ");
  }
  return opts.fallbacks ? pickField(row, opts.fallbacks) ?? "" : "";
}

/** The currency code for a row: an explicit code wins, else the value at `currencyField`. */
export function resolveCurrency(
  row: EntityRecord,
  currencyField?: string | null,
  currency?: string | null
): string | undefined {
  if (currency && currency.trim()) return currency.trim();
  if (currencyField) {
    const v = row[currencyField];
    if (typeof v === "string" && v.trim()) return v.trim();
  }
  return undefined;
}
