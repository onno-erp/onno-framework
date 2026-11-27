import { openRoute } from "../../lib/navigation";
import { notifyError } from "../../lib/notify-error";
import { useEffect, useMemo, useState, type ReactNode } from "react";
import { format } from "date-fns";
import { toSnakeCase } from "../../lib/utils";
import { useEntityVersion } from "../../lib/aggregate-data";
import { useEntitySource } from "../../lib/entity-source";
import { whereParam, type WhereClause } from "../../lib/measure";
import { formatAmount, toNumber } from "../../lib/format";
import type { EntityRecord } from "../../lib/types";
import { Card, CardContent, CardHeader, CardTitle } from "../ui/card";
import { HintIcon } from "../ui/hint-icon";
import { recordTitle, recordDetails, useRecordColumns, type RecordColumn } from "../../lib/record-presentation";
import { RecordsTruncated } from "./records-truncated";
import { Boundary } from "../pages/boundary";

/** A compact list of an entity's most recent records, each opening its record. */
export interface RecentRecordsProps {
  /** Card title. */
  title?: string;
  /** Help text behind a `?` next to the title. */
  hint?: string;
  /** The catalog or document, by logical name (`"Orders"`). */
  entity?: string;
  /** Which records, e.g. `{ posted: true }`. */
  where?: WhereClause;
  /** How many rows to show; default 8. */
  limit?: number;
  /** The date field rows sort by (newest first) and show on the right; default `"date"`. */
  dateField?: string;
  /** The headline; default the record's description/number/code. */
  primary?: (record: EntityRecord) => ReactNode;
  /** The second line; default the entity metadata's first text/reference label. */
  secondary?: (record: EntityRecord) => ReactNode;
  /** The trailing amount; default the first of total/totalGross/amount/sum. */
  amount?: (record: EntityRecord) => number | null | undefined;
  /** The amount's ISO 4217 currency, fixed (`"USD"`) or per record. */
  currency?: string | ((record: EntityRecord) => string | null | undefined);
  /** A free unit label instead of a currency (`"kg"`, `"pcs"`). */
  unit?: string;
  /** Which side of the amount the unit sits; default `"suffix"`. */
  unitPosition?: "prefix" | "suffix";
  /** Fraction-digit policy of the amount. */
  format?: "integer" | "decimal";
  /** BCP-47 locale for the amount; default the browser's. */
  locale?: string;
}

// Default trailing amount: the usual document money fields.
const DEFAULT_AMOUNT = ["total", "totalGross", "amount", "sum"];

/** The default headline is the framework's record identity. */
export const defaultPrimary = recordTitle;

/** The second line follows the entity's declared text/reference columns. */
export function defaultSecondary(row: EntityRecord, columns: readonly RecordColumn[] = []): string {
  return recordDetails(row, columns)[0] ?? "";
}

/** The default amount: the first of total/totalGross/amount/sum holding a number. */
export function defaultAmount(row: EntityRecord): number | null {
  for (const key of DEFAULT_AMOUNT) {
    const n = toNumber(row[key]);
    if (n != null) return n;
  }
  return null;
}

/** The list feed query: newest first by `dateField` for documents, `where` as JSON. */
export function recentRecordsParams(
  entityType: "catalog" | "document",
  options: { limit: number; dateField: string; where?: WhereClause }
): URLSearchParams {
  const params = new URLSearchParams();
  params.set("limit", String(Math.max(options.limit, 8)));
  if (entityType === "document") {
    params.set("sort", options.dateField);
    params.set("dir", "desc");
  }
  const where = whereParam(options.where);
  if (where) params.set("where", where);
  return params;
}

const isEmpty = (node: ReactNode) => node == null || node === false || node === "";

export function RecentRecords(props: RecentRecordsProps) {
  return (
    <Boundary label={props.title || "Recent records"}>
      <RecentRecordsCard {...props} />
    </Boundary>
  );
}

function RecentRecordsCard(props: RecentRecordsProps) {
  const { title, hint, entity, locale } = props;
  const maxItems = props.limit || 8;
  const [items, setItems] = useState<EntityRecord[]>([]);
  const source = useEntitySource(entity, { dateField: props.dateField, where: props.where });
  const columns = useRecordColumns(source);
  const [hasMore, setHasMore] = useState(false);
  const liveVersion = useEntityVersion(source);
  const dateField = props.dateField || "date";

  const headline = (row: EntityRecord): ReactNode => (props.primary ?? defaultPrimary)(row);
  const subtitle = (row: EntityRecord): ReactNode => props.secondary ? props.secondary(row) : defaultSecondary(row, columns);

  const amount = (row: EntityRecord): string => {
    const n = (props.amount ?? defaultAmount)(row);
    if (n == null || !Number.isFinite(n)) return "";
    const currency = typeof props.currency === "function" ? props.currency(row) ?? undefined : props.currency;
    return formatAmount(n, { currency, unit: props.unit, unitPosition: props.unitPosition, format: props.format, locale });
  };

  const when = (row: EntityRecord): string => {
    const raw = row[dateField];
    if (typeof raw === "string" && raw) {
      try {
        return format(new Date(raw), "MMM d");
      } catch {
        // fall through
      }
    }
    return "";
  };

  // A new source starts empty; a live refresh keeps the rows on screen until the server answers.
  useEffect(() => {
    setItems([]);
    setHasMore(false);
  }, [source, maxItems, dateField]);

  useEffect(() => {
    if (!source || (source.entityType !== "document" && source.entityType !== "catalog")) return;
    const name = toSnakeCase(source.entityName);
    const params = recentRecordsParams(source.entityType, { limit: maxItems, dateField, where: source.where });
    let alive = true;
    fetch(`/api/list/${source.entityType}s/${name}?${params.toString()}`, { credentials: "include" })
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
      .then((data: { rows?: EntityRecord[]; hasMore?: boolean }) => {
        if (!alive) return;
        setItems(data.rows ?? []);
        setHasMore(data.hasMore === true);
      })
      .catch((error: unknown) => {
        if (alive) notifyError(error);
      });
    return () => {
      alive = false;
    };
  }, [source, maxItems, dateField, liveVersion]);

  const rows = useMemo(() => {
    // Most-recent first when the entity is dated; otherwise as served.
    const sorted = [...items].sort((a, b) => {
      const da = typeof a[dateField] === "string" ? (a[dateField] as string) : "";
      const db = typeof b[dateField] === "string" ? (b[dateField] as string) : "";
      return db.localeCompare(da);
    });
    return sorted.slice(0, maxItems);
  }, [items, maxItems, dateField]);

  const open = (row: EntityRecord) => {
    const id = String(row.id ?? "");
    if (!id || !source) return;
    openRoute(`/${source.entityType}s/${toSnakeCase(source.entityName)}/${id}`);
  };

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center gap-1.5">
          <CardTitle className="text-[13px] font-medium">{title}</CardTitle>
          <HintIcon text={hint} size={13} />
        </div>
      </CardHeader>
      <CardContent>
        <RecordsTruncated count={rows.length} truncated={hasMore || items.length > rows.length} />
        {rows.length === 0 ? (
          <p className="py-6 text-center text-xs text-muted-foreground">No records yet.</p>
        ) : (
          <ul className="divide-y divide-border">
            {rows.map((row) => {
              const head = headline(row);
              const sub = subtitle(row);
              const money = amount(row);
              const date = when(row);
              return (
                <li key={String(row.id)}>
                  <button
                    type="button"
                    onClick={() => open(row)}
                    className="flex w-full items-center justify-between gap-3 rounded-card py-2 text-left transition-colors hover:bg-accent/40 -mx-2 px-2"
                  >
                    <div className="min-w-0">
                      <div className="truncate text-[13px] font-medium leading-tight">
                        {!isEmpty(head) ? head : !isEmpty(sub) ? sub : "—"}
                      </div>
                      {!isEmpty(head) && !isEmpty(sub) && (
                        <div className="truncate text-[11px] text-muted-foreground">{sub}</div>
                      )}
                    </div>
                    <div className="flex shrink-0 flex-col items-end">
                      {money && <span className="text-[12px] font-medium tabular-nums">{money}</span>}
                      {date && <span className="text-[10px] text-muted-foreground tabular-nums">{date}</span>}
                    </div>
                  </button>
                </li>
              );
            })}
          </ul>
        )}
      </CardContent>
    </Card>
  );
}
