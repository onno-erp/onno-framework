/**
 * Typed data questions, as values: *what* to measure (`count`, `sum` of a field, …) over which
 * records (`where`), optionally grouped (`by`). Components and hooks take these instead of strings;
 * the server receives `where` as JSON.
 *
 *   sum("Orders", "total", { posted: true })            // revenue
 *   count("Orders", { status: { not: OrderStatus.CANCELLED } })
 *   by: "status"  |  by: { date: "week" }
 */

/** A comparison on one field. A bare value means equality; `null` means "has no value". */
export type FieldPredicate<T = unknown> =
  | T
  | null
  | {
      eq?: T | null;
      not?: T | null;
      in?: T[];
      notIn?: T[];
      gt?: T;
      gte?: T;
      lt?: T;
      lte?: T;
      contains?: string;
      startsWith?: string;
    };

/** Which records: field predicates (all must hold), plus `and` / `or` / `not`. */
export type WhereClause = {
  [field: string]: FieldPredicate | WhereClause | WhereClause[] | undefined;
  and?: WhereClause[];
  or?: WhereClause[];
  not?: WhereClause;
};

export type Aggregation = "count" | "sum" | "avg" | "min" | "max";

/** A number over an entity's records. Built with {@link count}, {@link sum}, {@link avg}, {@link min}, {@link max}. */
export type MeasureSpec = {
  entity: string;
  aggregation: Aggregation;
  /** The field summed/averaged; absent for a count. */
  field?: string;
  where?: WhereClause;
  /** The date field the page's time range windows by (default: the entity's date / period). */
  dateField?: string;
};

export type DateBucket = "minute" | "hour" | "day" | "week" | "month";

/** Group by a field, or a date field bucketed: `"status"`, `{ date: "week" }`. */
export type GroupSpec = string | Record<string, DateBucket>;

export const count = (entity: string, where?: WhereClause): MeasureSpec => ({ entity, aggregation: "count", where });
export const sum = (entity: string, field: string, where?: WhereClause): MeasureSpec => ({ entity, aggregation: "sum", field, where });
export const avg = (entity: string, field: string, where?: WhereClause): MeasureSpec => ({ entity, aggregation: "avg", field, where });
export const min = (entity: string, field: string, where?: WhereClause): MeasureSpec => ({ entity, aggregation: "min", field, where });
export const max = (entity: string, field: string, where?: WhereClause): MeasureSpec => ({ entity, aggregation: "max", field, where });

/** The same measure over other records (`where` ANDed with the measure's own). */
export function withWhere(measure: MeasureSpec, where: WhereClause | undefined): MeasureSpec {
  if (!where || !Object.keys(where).length) return measure;
  return { ...measure, where: measure.where ? { and: [measure.where, where] } : where };
}

/** A group spec as the aggregate API's `groupBy` / `groupByDate` parameters. */
export function groupParams(by: GroupSpec | undefined): { groupBy?: string; groupByDate?: DateBucket } {
  if (!by) return {};
  if (typeof by === "string") return { groupBy: by };
  const [field, bucket] = Object.entries(by)[0] ?? [];
  return field ? { groupBy: field, groupByDate: bucket } : {};
}

/** The wire form of a `where`: compact JSON, or undefined when there is nothing to filter. */
export function whereParam(where: WhereClause | undefined): string | undefined {
  return where && Object.keys(where).length ? JSON.stringify(where) : undefined;
}

/** A measure (and grouping) as the aggregate endpoint's query parameters. */
export function aggregateParams(measure: MeasureSpec, by?: GroupSpec): Record<string, string> {
  const params: Record<string, string> = { metric: measure.aggregation };
  if (measure.field) params.field = measure.field;
  const group = groupParams(by);
  if (group.groupBy) params.groupBy = group.groupBy;
  if (group.groupByDate) params.groupByDate = group.groupByDate;
  const where = whereParam(measure.where);
  if (where) params.where = where;
  return params;
}
