import { useCallback, useEffect, useMemo, useState, type CSSProperties } from "react";
import { format } from "date-fns";
import { toast } from "sonner";
import { useNavigate } from "react-router-dom";
import {
  DragDropContext,
  Droppable,
  Draggable,
  type DropResult,
} from "@hello-pangea/dnd";
import { Lock } from "lucide-react";
import { api } from "@/lib/api";
import { toSnakeCase, cn } from "@/lib/utils";
import type { DashboardWidgetMeta, EntityRecord } from "@/lib/types";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { HintIcon } from "@/components/ui/hint-icon";
import { Badge } from "@/components/ui/badge";
import { Avatar, AvatarImage, AvatarFallback } from "@/components/ui/avatar";

function pickAvatar(row: EntityRecord): { url?: string; label?: string } {
  for (const key of Object.keys(row)) {
    if (key.endsWith("_avatar") && typeof row[key] === "string" && (row[key] as string).trim()) {
      const base = key.slice(0, -"_avatar".length);
      const label = row[`${base}_display`];
      return {
        url: row[key] as string,
        label: typeof label === "string" ? label : undefined,
      };
    }
  }
  return {};
}

interface KanbanWidgetProps {
  widget: DashboardWidgetMeta;
}

interface Column {
  key: string;
  label: string;
  match: (row: EntityRecord) => boolean;
  apply: (entityName: string, row: EntityRecord) => Promise<EntityRecord | undefined>;
  optimistic: (row: EntityRecord) => EntityRecord;
  variant: "default" | "secondary" | "outline" | "success";
}

function defaultColumns(widget: DashboardWidgetMeta): Column[] {
  const groupBy = widget.extraConfig?.groupBy ?? "_posted";
  if (groupBy === "_posted" && widget.entityType === "document") {
    return [
      {
        key: "draft",
        label: "Draft",
        match: (r) => !r._posted,
        apply: (name, row) => api.unpostDocument(toSnakeCase(name), row._id as string),
        optimistic: (row) => ({ ...row, _posted: false }),
        variant: "secondary",
      },
      {
        key: "posted",
        label: "Posted",
        match: (r) => Boolean(r._posted),
        apply: (name, row) => api.postDocument(toSnakeCase(name), row._id as string),
        optimistic: (row) => ({ ...row, _posted: true }),
        variant: "success",
      },
    ];
  }
  // Fallback: no usable apply when grouping is unsupported
  return [];
}

export function KanbanWidget({ widget }: KanbanWidgetProps) {
  const [items, setItems] = useState<EntityRecord[]>([]);
  const navigate = useNavigate();

  const load = useCallback(() => {
    const name = toSnakeCase(widget.entityName);
    if (widget.entityType === "document") {
      api.listDocuments(name).then(setItems);
    } else if (widget.entityType === "catalog") {
      api.listCatalog(name).then(setItems);
    }
  }, [widget]);

  useEffect(() => {
    load();
  }, [load]);

  const titleField = widget.titleField || "_number";
  const columns = useMemo(() => defaultColumns(widget), [widget]);
  const draggable = columns.length > 0;
  // RBAC: the server stamps canWrite=false when the viewer may not write the entity — cards
  // stay clickable (open the record) but can't be dragged between columns. REST enforces anyway.
  const canWrite = widget.canWrite !== false;

  const onDragEnd = async (result: DropResult) => {
    if (!canWrite) return;
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
    if (!target) return;

    const row = items.find((r) => r._id === draggableId);
    if (!row || target.match(row)) return;

    const optimistic = target.optimistic(row);
    setItems((prev) => prev.map((r) => (r._id === row._id ? optimistic : r)));

    try {
      await target.apply(widget.entityName, row);
      load();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "Move failed");
      load();
    }
  };

  if (!draggable) {
    return (
      <Card>
        <CardHeader>
          <CardTitle className="text-[13px] font-medium">{widget.title}</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-xs text-muted-foreground">
            Kanban grouping <code>{widget.extraConfig?.groupBy ?? "_posted"}</code> is not supported.
          </p>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center gap-1.5">
          <CardTitle className="text-[13px] font-medium">{widget.title}</CardTitle>
          <HintIcon text={widget.hint} size={13} />
          {!canWrite ? (
            // Mirrors the calendar's read-only chip: cards open records, dragging is off.
            <span className="ml-auto flex items-center gap-1 text-[10px] text-muted-foreground">
              <Lock className="h-2.5 w-2.5" /> read only
            </span>
          ) : null}
        </div>
      </CardHeader>
      <CardContent className="overflow-x-auto">
        <DragDropContext onDragEnd={onDragEnd}>
          <div className="flex gap-3 min-w-fit pb-1">
            {columns.map((col) => {
              const cards = items
                .filter(col.match)
                .slice(0, widget.maxItems || 12);
              return (
                <Droppable droppableId={col.key} key={col.key}>
                  {(dropProvided, dropSnapshot) => (
                    <div
                      ref={dropProvided.innerRef}
                      {...dropProvided.droppableProps}
                      className={cn(
                        "flex w-72 shrink-0 flex-col gap-2 rounded-control p-2 transition-colors",
                        "border-2 bg-muted/40",
                        dropSnapshot.isDraggingOver
                          ? "border-primary/40 bg-muted/70"
                          : "border-transparent"
                      )}
                    >
                      <div className="flex items-center justify-between px-1">
                        <span className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
                          {col.label}
                        </span>
                        <Badge variant={col.variant} className="h-5 px-1.5 text-[10px]">
                          {cards.length}
                        </Badge>
                      </div>
                      <div className="flex flex-col gap-1.5 min-h-[40px]">
                        {cards.length === 0 && !dropSnapshot.isDraggingOver && (
                          <div className="rounded-control border border-dashed border-border/60 px-2 py-3 text-center text-[11px] text-muted-foreground">
                            No items
                          </div>
                        )}
                        {cards.map((row, index) => {
                          const id = String(row._id);
                          const avatar = pickAvatar(row);
                          const number = String(row[titleField] ?? row._number ?? row._code ?? "");
                          const primaryDisplay = String(
                            row.customer_display ??
                              row.client_display ??
                              row.primary_client_display ??
                              row._description ??
                              row.name ??
                              ""
                          );
                          const secondaryDisplay = String(
                            row.property_display ?? row.warehouse_display ?? ""
                          );
                          const handleOpen = () => {
                            if (widget.entityType === "document") {
                              navigate(`/documents/${toSnakeCase(widget.entityName)}/${id}`);
                            }
                          };
                          return (
                            <Draggable draggableId={id} index={index} key={id} isDragDisabled={!canWrite}>
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
                                    canWrite ? "cursor-grab active:cursor-grabbing" : "cursor-pointer",
                                    dragSnapshot.isDragging && "shadow-lg ring-1 ring-primary/30"
                                  )}
                                >
                                  <div className="flex items-start justify-between gap-2">
                                    <span className="font-mono text-[10px] uppercase tracking-wide text-muted-foreground">
                                      {number}
                                    </span>
                                    {typeof row._date === "string" && row._date && (
                                      <span className="text-[10px] text-muted-foreground tabular-nums">
                                        {format(new Date(row._date), "MMM d")}
                                      </span>
                                    )}
                                  </div>
                                  {primaryDisplay && (
                                    <div className="mt-1 line-clamp-2 text-[13px] font-medium leading-tight">
                                      {primaryDisplay}
                                    </div>
                                  )}
                                  {secondaryDisplay && (
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
                                    {typeof row.total === "number" && (
                                      <span className="text-[12px] font-medium tabular-nums">
                                        ${(row.total as number).toFixed(2)}
                                      </span>
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
