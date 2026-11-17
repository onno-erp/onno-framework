export type InitialFilterControl = {
  key: string;
  type: "options" | "multiOptions" | "contains" | "startsWith" | "dateRange" | "numberRange";
};

export type FilterState = Record<
  string,
  { eq?: string; in?: string[]; text?: string; from?: string; to?: string }
>;

/** Build removable initial toolbar-filter state from a list descriptor. */
export function initialFilterState(
  filters: InitialFilterControl[],
  defaults: Record<string, string[]> = {}
): FilterState {
  return Object.fromEntries(
    filters.map((filter) => {
      const values = (defaults[filter.key] ?? []).filter(Boolean);
      if (filter.type === "multiOptions") return [filter.key, values.length ? { in: values } : {}];
      if (filter.type === "contains" || filter.type === "startsWith") {
        return [filter.key, values[0] ? { text: values[0] } : {}];
      }
      if (filter.type === "dateRange") {
        return [filter.key, values[0] || values[1] ? { from: values[0] ?? "", to: values[1] ?? "" } : {}];
      }
      if (filter.type === "numberRange") {
        const from = normalizeNumberBound(values[0] ?? "");
        const to = normalizeNumberBound(values[1] ?? "");
        return [filter.key, from || to ? { from, to } : {}];
      }
      return [filter.key, values[0] ? { eq: values[0] } : {}];
    })
  );
}

/**
 * A number-range bound as the server accepts it: a plain decimal with a dot ("1 250,5" → "1250.5").
 * Anything that isn't a number (or is unfinished, like a lone "-") yields "" — no constraint.
 */
export function normalizeNumberBound(text: string): string {
  const v = text.replace(/[\s\u00a0\u202f]/g, "").replace(",", ".");
  return /^[+-]?(\d+(\.\d*)?|\.\d+)$/.test(v) ? v.replace(/\.$/, "") : "";
}

/** The chip badge for a number range: "100 – 500", or "≥ 100" / "≤ 500" when one end is open. */
export function numberRangeLabel(from: string, to: string): string {
  if (from && to) return `${from} – ${to}`;
  return from ? `≥ ${from}` : to ? `≤ ${to}` : "";
}
