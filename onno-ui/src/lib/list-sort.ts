import type { ValueFormat } from "./cell-format";

/** Ordered list sort state; the primary pair remains compatible with existing descriptors. */
export type SortColumn = { column: string; descending: boolean };
export type ListSort = { column: string | null; descending: boolean; then?: SortColumn[] };

export function sortColumns(sort: ListSort): SortColumn[] {
  return sort.column ? [{ column: sort.column, descending: sort.descending }, ...(sort.then ?? [])] : [];
}

export function sortFromColumns(columns: SortColumn[]): ListSort {
  return columns.length ? { ...columns[0], ...(columns.length > 1 ? { then: columns.slice(1) } : {}) }
    : { column: null, descending: false };
}

export function parseSort(value: string, descending = false, resolve = (column: string) => column): ListSort {
  const seen = new Set<string>();
  const columns: SortColumn[] = [];
  for (const token of value.split(",")) {
    const text = token.trim();
    if (!text) continue;
    const column = resolve(text.replace(/^[-+]/, "").replace(/:day$/, ""));
    if (!column || seen.has(column)) continue;
    seen.add(column);
    columns.push({ column, descending: /^[+-]/.test(text) ? text.startsWith("-") : descending });
  }
  return sortFromColumns(columns);
}

export function serializeSort(sort: ListSort, resolve = (column: string) => column): string {
  return sortColumns(sort).map(s => (s.descending ? "-" : "") + resolve(s.column)).join(",");
}

/** New keys append, direction changes keep priority, removing a key closes the priority gap. */
export function toggleListSort(sort: ListSort, column: string): ListSort {
  const columns = sortColumns(sort);
  const index = columns.findIndex(s => s.column === column);
  if (index < 0) columns.push({ column, descending: false });
  else if (!columns[index].descending) columns[index] = { column, descending: true };
  else columns.splice(index, 1);
  return sortFromColumns(columns);
}

/** Preserve legacy single-key URLs; multi-key directions live alongside their own columns. */
export function writeSortParams(params: URLSearchParams, sort: ListSort, resolve = (column: string) => column): void {
  const columns = sortColumns(sort);
  params.set("sort", columns.length === 1 ? resolve(columns[0].column) : serializeSort(sort, resolve));
  params.set("dir", columns.length === 1 && columns[0].descending ? "desc" : "asc");
}

/** A date-only formatter makes times within that local calendar day equal for subsequent criteria. */
export function dateOnlySort(format: ValueFormat | undefined): boolean {
  if (!format || !("date" in format)) return false;
  if (typeof format.date === "string") return true;
  const options = format.date;
  if (options.timeStyle || options.hour || options.minute || options.second || ("fractionalSecondDigits" in options && options.fractionalSecondDigits)) return false;
  return !!(options.dateStyle || options.day) || !Object.keys(options).length;
}
