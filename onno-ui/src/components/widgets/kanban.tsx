import { openRoute } from "../../lib/navigation";
import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties, type ReactNode } from "react";
import { format } from "date-fns";
import {
  DragDropContext,
  Droppable,
  Draggable,
  type DropResult,
} from "@hello-pangea/dnd";
import { Lock } from "lucide-react";
import { api } from "../../lib/api";
import { notifyError } from "../../lib/notify-error";
import { toSnakeCase, cn } from "../../lib/utils";
import type { EntityRecord } from "../../lib/types";
import { useEntitySource } from "../../lib/entity-source";
import { useQuery } from "@tanstack/react-query";
import { queryClient, useEntityChanges } from "../../lib/query";
import { findEntity, useModel } from "../../lib/model-store";
import { Boundary } from "../pages/boundary";
import { Card, CardContent, CardHeader, CardTitle } from "../ui/card";
import { HintIcon } from "../ui/hint-icon";
import { Badge } from "../ui/badge";
import { Avatar, AvatarImage, AvatarFallback } from "../ui/avatar";
import { logicalEntityKey } from "../../lib/entity-keys";
import type { WhereClause } from "../../lib/measure";
import { formatAmount, toNumber } from "../../lib/format";
import { pickAvatar, recordDetails, recordTitle, useRecordColumns, type RecordColumn } from "../../lib/record-presentation";
import { RecordsTruncated } from "./records-truncated";

/** A board of an entity's records in columns; documents drag between Draft and Posted. */
export interface KanbanProps {
  /** Card title. */
  title?: string;
  /** Help text behind a `?` next to the title. */
  hint?: string;
  /** The catalog or document, by logical name (`"Orders"`). */
  entity?: string;
  /** Which records, e.g. `{ status: { not: "CANCELED" } }`. */
  where?: WhereClause;
  /** Cards per column; default 12. */
  limit?: number;
  /**
   * The column grouping: an enumeration field (a column per value, in declaration order), a
   * reference field (a column per referenced record, plus one for no value) or `"posted"` (Draft /
   * Posted, the default for documents). Dragging a card to another column sets the field (posts or
   * unposts a document).
   */
  by?: string;
  /** The date shown on each card; default `"date"`. */
  dateField?: string;
  /** The small caption atop each card; default the record's number/code. */
  caption?: (record: EntityRecord) => ReactNode;
  /** The card's headline; default the record's description/number/code. */
  primary?: (record: EntityRecord) => ReactNode;
  /** The card's second line; default the entity metadata's first text/reference label. */
  secondary?: (record: EntityRecord) => ReactNode;
  /** The trailing amount; default `total`. */
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

const text = (v: unknown) => (v == null ? "" : String(v));

/** The default card caption: the record's number, else its code. */
export const defaultKanbanCaption = (row: EntityRecord): ReactNode => text(row.number ?? row.code);
/** The default card headline uses the framework's record identity. */
export const defaultKanbanPrimary = recordTitle;
/** The default second line follows the entity's declared columns. */
export const defaultKanbanSecondary = (row: EntityRecord, columns: readonly RecordColumn[] = []): ReactNode =>
  recordDetails(row, columns)[0] ?? "";
/** The default card amount: a numeric `total`. */
export const defaultKanbanAmount = (row: EntityRecord): number | null => toNumber(row.total);

const isEmpty = (node: ReactNode) => node == null || node === false || node === "";

interface Column {
  key: string;
  label: string;
  color?: string;
  match: (row: EntityRecord) => boolean;
  apply?: (entityName: string, row: EntityRecord) => Promise<EntityRecord | undefined>;
  optimistic?: (row: EntityRecord) => EntityRecord;
  variant: "default" | "secondary" | "outline" | "success";
}

/** An enumeration value as the entity's list descriptor offers it (its enum filter's option). */
type EnumOption = { value: string; label: string; color?: string };

/** What the board needs to know about the grouping field (from the entity's default list view). */
type GroupFieldInfo = { type: "enum"; options: EnumOption[] } | { type: "ref" } | { type: "other" };

const NO_VALUE = "__none__";

const valueOf = (row: EntityRecord, field: string): string | null => {
  const v = row[field];
  return v == null || v === "" ? null : String(v);
};

/** Write `value` to the grouping field through the same partial update the form uses. */
const setField = (kind: "catalog" | "document", field: string, value: string | null) =>
  (name: string, row: EntityRecord) =>
    kind === "document"
      ? api.updateDocument(toSnakeCase(name), row.id as string, { [field]: value })
      : api.updateCatalogItem(toSnakeCase(name), row.id as string, { [field]: value });

/** One column per value the field takes: Draft/Posted, enum values, referenced records. */
export function kanbanColumns(
  kind: "catalog" | "document",
  field: string,
  info: GroupFieldInfo | undefined,
  rows: EntityRecord[],
  noValueLabel = "—",
): Column[] {
  if (field === "posted" && kind === "document") {
    return [
      {
        key: "draft",
        label: "Draft",
        match: (r) => !r.posted,
        apply: (name, row) => api.unpostDocument(toSnakeCase(name), row.id as string),
        optimistic: (row) => ({ ...row, posted: false }),
        variant: "secondary",
      },
      {
        key: "posted",
        label: "Posted",
        match: (r) => Boolean(r.posted),
        apply: (name, row) => api.postDocument(toSnakeCase(name), row.id as string),
        optimistic: (row) => ({ ...row, posted: true }),
        variant: "success",
      },
    ];
  }
  const write = (value: string | null, extra: EntityRecord = {}): Pick<Column, "apply" | "optimistic"> => ({
    apply: setField(kind, field, value),
    optimistic: (row) => ({ ...row, [field]: value, ...extra }),
  });
  const noValue = (always: boolean): Column[] =>
    always || rows.some((r) => valueOf(r, field) == null)
      ? [{ key: NO_VALUE, label: noValueLabel, match: (r) => valueOf(r, field) == null, variant: "outline",
          ...write(null, { [`${field}Display`]: null }) }]
      : [];
  if (info?.type === "enum") {
    return [
      ...info.options.map((o): Column => ({
        key: o.value,
        label: o.label,
        color: o.color,
        match: (r) => valueOf(r, field) === o.value,
        variant: "secondary",
        ...write(o.value, { [`${field}Display`]: o.label, [`${field}Color`]: o.color }),
      })),
      ...noValue(false),
    ];
  }
  if (info?.type === "ref") {
    const referenced = new Map<string, string>();
    for (const r of rows) {
      const id = valueOf(r, field);
      if (id != null && !referenced.has(id)) referenced.set(id, String(r[`${field}Display`] ?? id));
    }
    const sorted = [...referenced].sort((a, b) => a[1].localeCompare(b[1]));
    return [
      ...sorted.map(([id, label]): Column => ({
        key: id,
        label,
        match: (r) => valueOf(r, field) === id,
        variant: "secondary",
        ...write(id, { [`${field}Display`]: label }),
      })),
      ...noValue(true),
    ];
  }
  return [];
}

/**
 * What kind of field `field` is: an enumeration when the entity's default list view filters by it
 * (its options are the values in declaration order, with labels and colors), a reference when the
 * records carry its `…Ref` companion (or the view's column says so).
 */
function useGroupFieldInfo(route: string | undefined, field: string, enabled: boolean, rows: EntityRecord[]): GroupFieldInfo | undefined {
  const query = useQuery({
    queryKey: ["onno", "list-view", route ?? ""],
    enabled: enabled && !!route,
    staleTime: Infinity,
    queryFn: () => api.getJson<{ list?: { columns?: ListColumnInfo[]; filters?: ListFilterInfo[] } }>(`/api/ui/view${route}`),
  }, queryClient);
  const list = query.data?.list;
  if (!list) return undefined;
  const column = list.columns?.find((c) => c.fieldName === field);
  const filter = list.filters?.find((f) => f.key === field && f.type === "multiOptions");
  if (column?.type === "enum" || (column?.type == null && filter?.options?.length)) {
    return { type: "enum", options: (filter?.options ?? []).map((o) => ({ value: o.value, label: o.label, color: o.color || undefined })) };
  }
  if (column?.type === "ref" || rows.some((r) => `${field}Ref` in r)) return { type: "ref" };
  return { type: "other" };
}

type ListColumnInfo = { fieldName: string; type?: string };
type ListFilterInfo = { key: string; type?: string; options?: { value: string; label: string; color?: string }[] };

export function Kanban(props: KanbanProps) {
  return (
    <Boundary label={props.title || "Kanban"}>
      <KanbanBoard {...props} />
    </Boundary>
  );
}

function KanbanBoard(props: KanbanProps) {
  const { title, hint, entity, by: groupBy, dateField: dateFieldProp, limit } = props;
  const [items, setItems] = useState<EntityRecord[]>([]);
  const source = useEntitySource(entity, { where: props.where });
  const recordColumns = useRecordColumns(source);
  const [hasMore, setHasMore] = useState(false);
  const request = useRef(0);
  const cancelLoad = useCallback(() => { request.current++; }, []);
  const model = useModel();
  const entityType = source?.entityType;
  const entityName = source?.entityName ?? "";

  const load = useCallback(async () => {
    const seq = ++request.current;
    if (!source) return;
    const name = toSnakeCase(source.entityName);
    const page = source.entityType === "document"
      ? await api.listDocumentsPage(name, undefined, undefined, source.where, { limit: 500 })
      : source.entityType === "catalog" ? await api.listCatalogPage(name, source.where, { limit: 500 }) : null;
    if (seq !== request.current) return;
    setItems(page?.rows ?? []);
    setHasMore(page?.hasMore === true);
  }, [source]);

  useEffect(() => {
    setItems([]);
    setHasMore(false);
    void load().catch(notifyError);
    return cancelLoad;
  }, [load, cancelLoad]);
  useEntityChanges(source?.entityName, () => { void load().catch(notifyError); });

  const dateField = dateFieldProp || "date";
  const captionOf = props.caption ?? defaultKanbanCaption;
  const primaryOf = props.primary ?? defaultKanbanPrimary;
  const secondaryOf = props.secondary ?? ((row: EntityRecord) => defaultKanbanSecondary(row, recordColumns));
  const amountOf = (row: EntityRecord): string => {
    const n = (props.amount ?? defaultKanbanAmount)(row);
    if (n == null || !Number.isFinite(n)) return "";
    const currency = typeof props.currency === "function" ? props.currency(row) ?? undefined : props.currency;
    return formatAmount(n, { currency, unit: props.unit, unitPosition: props.unitPosition, format: props.format, locale: props.locale });
  };
  const kind = entityType === "catalog" ? "catalog" : "document";
  // Catalogs have no posting state: ungrouped, they show one column of cards.
  const field = groupBy ? logicalEntityKey(groupBy) : entityType === "document" ? "posted" : "";
  const route = entity ? findEntity(model, entity)?.route : undefined;
  const fieldInfo = useGroupFieldInfo(route, field, !!field && field !== "posted", items);
  const columns = useMemo<Column[]>(
    () => (!field
      ? [{ key: "all", label: "All", match: () => true, variant: "secondary" }]
      : kanbanColumns(kind, field, fieldInfo, items)),
    [kind, field, fieldInfo, items],
  );
  const visibleCount = columns.reduce((n, col) => n + items.filter(col.match).slice(0, limit || 12).length, 0);
  const draggable = columns.some((column) => column.apply != null);
  // RBAC: the model marks canWrite=false when the viewer may not write the entity — cards stay
  // clickable (open the record) but can't be dragged between columns. REST enforces anyway.
  const canWrite = (entity ? findEntity(model, entity)?.canWrite : undefined) !== false;

  const onDragEnd = async (result: DropResult) => {
    if (!canWrite || !draggable) return;
    const { destination, source, draggableId } = result;
    if (!destination) return;

    // Same-column reorder: keep changes local, no backend call.
    if (destination.droppableId === source.droppableId) {
      if (destination.index === source.index) return;
      const col = columns.find((c) => c.key === source.droppableId);
      if (!col) return;
      setItems((prev) => {
        const colRows = prev.filter(col.match);
        const [moved] = colRows.splice(source.index, 1);
        if (!moved) return prev;
        colRows.splice(destination.index, 0, moved);
        let cursor = 0;
        return prev.map((row) => (col.match(row) ? colRows[cursor++] : row));
      });
      return;
    }

    // Cross-column move: optimistic, then call apply.
    const target = columns.find((c) => c.key === destination.droppableId);
    if (!target?.apply || !target.optimistic) return;

    const row = items.find((r) => r.id === draggableId);
    if (!row || target.match(row)) return;

    const optimistic = target.optimistic(row);
    setItems((prev) => prev.map((r) => (r.id === row.id ? optimistic : r)));

    try {
      await target.apply(entityName, row);
      void load().catch(notifyError);
    } catch (err) {
      // The api layer usually toasted already; notifyError adds one only when it didn't.
      notifyError(err, { fallback: "Move failed" });
      void load().catch(notifyError);
    }
  };

  if (!source) {
    // The model hasn't loaded (or doesn't know the entity): a quiet frame, not a grouping error.
    return (
      <Card>
        <CardHeader>
          <CardTitle className="text-[13px] font-medium">{title}</CardTitle>
        </CardHeader>
      </Card>
    );
  }

  if (!columns.length && (field === "posted" || fieldInfo)) {
    return (
      <Card>
        <CardHeader>
          <CardTitle className="text-[13px] font-medium">{title}</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-xs text-muted-foreground">
            Kanban grouping <code>{groupBy ?? "posted"}</code> is not supported.
          </p>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center gap-1.5">
          <CardTitle className="text-[13px] font-medium">{title}</CardTitle>
          <HintIcon text={hint} size={13} />
          {!canWrite ? (
            // Mirrors the calendar's read-only chip: cards open records, dragging is off.
            <span className="ml-auto flex items-center gap-1 text-[10px] text-muted-foreground">
              <Lock className="h-2.5 w-2.5" /> read only
            </span>
          ) : null}
        </div>
      </CardHeader>
      <CardContent className="overflow-x-auto">
        <RecordsTruncated count={visibleCount} truncated={hasMore || visibleCount < items.length} />
        <DragDropContext onDragEnd={onDragEnd}>
          <div className="flex gap-3 min-w-fit pb-1">
            {columns.map((col) => {
              const cards = items
                .filter(col.match)
                .slice(0, limit || 12);
              return (
                <Droppable droppableId={col.key} key={col.key}>
                  {(dropProvided, dropSnapshot) => (
                    <div
                      ref={dropProvided.innerRef}
                      {...dropProvided.droppableProps}
                      className={cn(
                        "flex w-72 shrink-0 flex-col gap-2 rounded-panel p-2 transition-colors",
                        "border-2 bg-muted/40",
                        dropSnapshot.isDraggingOver
                          ? "border-primary/40 bg-muted/70"
                          : "border-transparent"
                      )}
                    >
                      <div className="flex items-center justify-between px-1">
                        <span className="flex items-center gap-1.5 text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
                          {col.color ? <span className="size-2 rounded-full" style={{ backgroundColor: col.color }} aria-hidden="true" /> : null}
                          {col.label}
                        </span>
                        <Badge variant={col.variant} className="h-5 px-1.5 text-[10px]">
                          {cards.length}
                        </Badge>
                      </div>
                      <div className="flex flex-col gap-1.5 min-h-[40px]">
                        {cards.length === 0 && !dropSnapshot.isDraggingOver && (
                          <div className="rounded-field border border-dashed border-border/60 px-2 py-3 text-center text-[11px] text-muted-foreground">
                            No items
                          </div>
                        )}
                        {cards.map((row, index) => {
                          const id = String(row.id ?? "");
                          const avatar = pickAvatar(row, recordColumns);
                          const caption = captionOf(row);
                          const primaryDisplay = primaryOf(row);
                          const secondaryDisplay = secondaryOf(row);
                          const money = amountOf(row);
                          const handleOpen = () => {
                            if (!id) return;
                            const kind = entityType === "catalog" ? "catalogs" : "documents";
                            openRoute(`/${kind}/${toSnakeCase(entityName)}/${id}`);
                          };
                          return (
                            <Draggable draggableId={id} index={index} key={id} isDragDisabled={!canWrite || !draggable}>
                              {(dragProvided, dragSnapshot) => (
                                <div
                                  ref={dragProvided.innerRef}
                                  {...dragProvided.draggableProps}
                                  {...dragProvided.dragHandleProps}
                                  // @hello-pangea/dnd's DraggableStyle predates radix's CSSProperties
                                  // augmentation (the `--radix-*` index signature), so re-assert the
                                  // spread style as a plain CSSProperties to bridge the two type sets.
                                  style={dragProvided.draggableProps.style as CSSProperties}
                                  onClick={() => {
                                    if (!dragSnapshot.isDragging) handleOpen();
                                  }}
                                  className={cn(
                                    "group select-none rounded-card border bg-card text-card-foreground p-3 shadow-sm transition-shadow",
                                    "hover:shadow-md hover:border-foreground/20",
                                    canWrite && draggable ? "cursor-grab active:cursor-grabbing" : "cursor-pointer",
                                    dragSnapshot.isDragging && "shadow-lg ring-1 ring-primary/30"
                                  )}
                                >
                                  <div className="flex items-start justify-between gap-2">
                                    <span className="font-mono text-[10px] uppercase tracking-wide text-muted-foreground">
                                      {!props.caption && caption === primaryDisplay ? null : caption}
                                    </span>
                                    {typeof row[dateField] === "string" && row[dateField] && (
                                      <span className="text-[10px] text-muted-foreground tabular-nums">
                                        {format(new Date(row[dateField] as string), "MMM d")}
                                      </span>
                                    )}
                                  </div>
                                  {!isEmpty(primaryDisplay) && (
                                    <div className="mt-1 line-clamp-2 text-[13px] font-medium leading-tight">
                                      {primaryDisplay}
                                    </div>
                                  )}
                                  {!isEmpty(secondaryDisplay) && (
                                    <div className="mt-0.5 truncate text-[11px] text-muted-foreground">
                                      {secondaryDisplay}
                                    </div>
                                  )}
                                  <div className="mt-2 flex items-center justify-between gap-2">
                                    {avatar.url ? (
                                      <Avatar className="h-6 w-6 text-[10px]" title={avatar.label}>
                                        <AvatarImage src={avatar.url} alt={avatar.label ?? ""} />
                                        <AvatarFallback>
                                          {(avatar.label ?? "?").slice(0, 2).toUpperCase()}
                                        </AvatarFallback>
                                      </Avatar>
                                    ) : (
                                      <span />
                                    )}
                                    {money && (
                                      <span className="text-[12px] font-medium tabular-nums">{money}</span>
                                    )}
                                  </div>
                                </div>
                              )}
                            </Draggable>
                          );
                        })}
                        {dropProvided.placeholder}
                      </div>
                    </div>
                  )}
                </Droppable>
              );
            })}
          </div>
        </DragDropContext>
      </CardContent>
    </Card>
  );
}
