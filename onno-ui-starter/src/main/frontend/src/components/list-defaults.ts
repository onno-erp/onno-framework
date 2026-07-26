export type InitialFilterControl = {
  key: string;
  type: "options" | "multiOptions" | "contains" | "startsWith" | "dateRange";
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
      return [filter.key, values[0] ? { eq: values[0] } : {}];
    })
  );
}
