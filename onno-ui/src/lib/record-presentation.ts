import { useQuery } from "@tanstack/react-query";
import { api } from "./api";
import { queryClient } from "./query";
import { logicalEntityKey } from "./entity-keys";
import { pickField } from "./format";
import { rowTitle } from "./schedule";
import { toSnakeCase } from "./utils";
import type { EntitySource } from "./entity-source";
import { SECRET_SET, type EntityRecord } from "./types";

export type RecordColumn = { fieldName: string; type?: string };
const SYSTEM_FIELDS = new Set(["id", "code", "description", "number", "date", "posted"]);

/** Entity identity uses framework system fields, never an app-specific attribute name. */
export const recordTitle = (row: EntityRecord, titleField?: string): string => rowTitle(row, titleField);

/** Secondary text follows the entity's declared columns, including resolved ref/enum labels. */
export function recordDetails(row: EntityRecord, columns: readonly RecordColumn[]): string[] {
  const details: string[] = [];
  for (const column of columns) {
    const field = logicalEntityKey(column.fieldName);
    if (SYSTEM_FIELDS.has(field)) continue;
    const keys = column.type === "ref" ? [`${field}Display`]
      : column.type === "enum" ? [`${field}Display`, field]
      : column.type === "text" ? [field] : [];
    const value = pickField(row, keys);
    if (value && value !== SECRET_SET && value !== recordTitle(row) && !details.includes(value)) details.push(value);
  }
  return details;
}

/** First resolved reference photo, in metadata order, shared by calendar and kanban. */
export function pickAvatar(row: EntityRecord, columns: readonly RecordColumn[]): { url?: string; label?: string } {
  for (const column of columns) {
    if (column.type !== "ref") continue;
    const field = logicalEntityKey(column.fieldName);
    const url = row[`${field}Avatar`];
    if (typeof url === "string" && url.trim()) return { url, label: pickField(row, [`${field}Display`]) };
  }
  return {};
}

const NO_COLUMNS: RecordColumn[] = [];

/** Reuse the entity list descriptor (and the kanban grouping query), not record-key heuristics. */
export function useRecordColumns(source: EntitySource | null): RecordColumn[] {
  const route = source && source.entityType !== "register"
    ? `/${source.entityType}s/${toSnakeCase(source.entityName)}` : "";
  const query = useQuery({
    queryKey: ["onno", "list-view", route],
    enabled: !!route,
    staleTime: Infinity,
    queryFn: () => api.getJson<{ list?: { columns?: RecordColumn[] } }>(`/api/ui/view${route}`),
  }, queryClient);
  return query.data?.list?.columns ?? NO_COLUMNS;
}
