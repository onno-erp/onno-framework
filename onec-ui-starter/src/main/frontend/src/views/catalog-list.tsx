import { useCallback, useEffect, useMemo, useState } from "react";
import { useParams } from "react-router-dom";
import { Plus, Trash2, Pencil, BookOpen } from "lucide-react";
import { api } from "@/lib/api";
import { toSnakeCase, displayValue } from "@/lib/utils";
import type { CatalogMeta, EntityRecord } from "@/lib/types";
import { useUiEvents } from "@/hooks/use-ui-events";
import { Button } from "@/components/ui/button";
import {
  Table,
  TableHeader,
  TableBody,
  TableRow,
  TableHead,
  TableCell,
} from "@/components/ui/table";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from "@/components/ui/dialog";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { EntityForm } from "@/components/entity-form";
import { PageHeader } from "@/components/page-header";
import { EmptyState } from "@/components/empty-state";
import { SkeletonTable } from "@/components/skeleton-table";
import { Skeleton } from "@/components/ui/skeleton";
import { DeleteDialog } from "@/components/delete-dialog";
import { Pagination } from "@/components/ui/pagination";
import { RowRefDisplay } from "@/components/ref-display";
import { Avatar, AvatarImage, AvatarFallback } from "@/components/ui/avatar";

export function CatalogListView() {
  const { name } = useParams<{ name: string }>();
  const [meta, setMeta] = useState<CatalogMeta | null>(null);
  const [items, setItems] = useState<EntityRecord[]>([]);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState<EntityRecord | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(25);

  const load = useCallback(() => {
    if (!name) return;
    api.listCatalog(name).then(setItems);
  }, [name]);

  useEffect(() => {
    if (!name) return;
    api.getCatalogs().then((all) => {
      const found = all.find((c) => toSnakeCase(c.name) === name);
      setMeta(found ?? null);
    });
    load();
  }, [name]);

  useUiEvents(useCallback((event) => {
    if (!name || event.entityType !== "catalog") return;
    if (event.entityName === meta?.name || event.entityName === "*") load();
  }, [load, meta?.name, name]));

  const handleSave = async (data: EntityRecord) => {
    if (!name) return;
    if (editing && editing._id) {
      await api.updateCatalogItem(name, editing._id as string, data);
    } else {
      await api.createCatalogItem(name, data);
    }
    setDialogOpen(false);
    setEditing(null);
    load();
  };

  const handleDelete = async () => {
    if (!name || !deleteTarget) return;
    await api.deleteCatalogItem(name, deleteTarget);
    setDeleteTarget(null);
    load();
  };

  const listAttrs = useMemo(
    () => meta?.attributes.filter((a) => a.visibleInList !== false).sort((a, b) => (a.order ?? 0) - (b.order ?? 0)) ?? [],
    [meta]
  );

  const pagedItems = useMemo(
    () => items.slice(page * pageSize, (page + 1) * pageSize),
    [items, page, pageSize]
  );

  useEffect(() => {
    // Snap back if the data shrinks below the current page
    const maxPage = Math.max(0, Math.ceil(items.length / pageSize) - 1);
    if (page > maxPage) setPage(maxPage);
  }, [items.length, pageSize, page]);

  if (!meta) {
    return (
      <div className="animate-in-page">
        <div className="mb-6">
          <Skeleton className="h-8 w-48 mb-2" />
          <Skeleton className="h-4 w-24" />
        </div>
        <SkeletonTable columns={4} rows={5} />
      </div>
    );
  }

  return (
    <div className="animate-in-page">
      <PageHeader
        title={meta.name}
        subtitle="Catalog"
        breadcrumbs={[{ label: "Catalogs" }, { label: meta.name }]}
        actions={
          <Button onClick={() => { setEditing(null); setDialogOpen(true); }}>
            <Plus className="h-4 w-4 mr-2" />
            New
          </Button>
        }
      />

      {items.length === 0 ? (
        <EmptyState
          icon={BookOpen}
          title="No records yet"
          description={`Create your first ${meta.name} record to get started.`}
          action={
            <Button onClick={() => { setEditing(null); setDialogOpen(true); }}>
              <Plus className="h-4 w-4 mr-2" />
              Create {meta.name}
            </Button>
          }
        />
      ) : (
        <div className="rounded-lg border overflow-hidden">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Code</TableHead>
                <TableHead>Description</TableHead>
                {listAttrs.map((a) => (
                  <TableHead key={a.fieldName}>{a.displayName}</TableHead>
                ))}
                <TableHead className="w-[100px]">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {pagedItems.map((item) => {
                const avatar = item.avatar_url as string | undefined;
                const showAvatar = meta.attributes.some((a) => a.widget === "avatar");
                return (
                <TableRow key={item._id as string}>
                  <TableCell className="font-mono">
                    <span className="inline-flex items-center gap-2">
                      {showAvatar && (
                        <Avatar className="h-6 w-6 text-[10px]">
                          {avatar && <AvatarImage src={avatar} alt={item._description as string} />}
                          <AvatarFallback>
                            {String(item._description ?? "?").slice(0, 2).toUpperCase()}
                          </AvatarFallback>
                        </Avatar>
                      )}
                      {item._code as string}
                    </span>
                  </TableCell>
                  <TableCell>{item._description as string}</TableCell>
                  {listAttrs.map((a) => (
                    <TableCell key={a.fieldName}>
                      {a.isRef ? (
                        <RowRefDisplay row={item} columnName={a.columnName} size="xs" />
                      ) : (
                        displayValue(a, item[a.columnName], item)
                      )}
                    </TableCell>
                  ))}
                  <TableCell>
                    <TooltipProvider>
                      <div className="flex gap-1">
                        <Tooltip>
                          <TooltipTrigger asChild>
                            <Button
                              size="icon"
                              variant="ghost"
                              onClick={() => {
                                setEditing(item);
                                setDialogOpen(true);
                              }}
                            >
                              <Pencil className="h-4 w-4" />
                            </Button>
                          </TooltipTrigger>
                          <TooltipContent>Edit</TooltipContent>
                        </Tooltip>
                        <Tooltip>
                          <TooltipTrigger asChild>
                            <Button
                              size="icon"
                              variant="ghost"
                              onClick={() => setDeleteTarget(item._id as string)}
                            >
                              <Trash2 className="h-4 w-4" />
                            </Button>
                          </TooltipTrigger>
                          <TooltipContent>Delete</TooltipContent>
                        </Tooltip>
                      </div>
                    </TooltipProvider>
                  </TableCell>
                </TableRow>
                );
              })}
            </TableBody>
          </Table>
          <Pagination
            page={page}
            pageSize={pageSize}
            total={items.length}
            onPageChange={setPage}
            onPageSizeChange={(s) => {
              setPage(0);
              setPageSize(s);
            }}
          />
        </div>
      )}

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent className={
          meta.attributes.length > 6
            ? "max-w-4xl max-h-[90vh] overflow-y-auto"
            : meta.attributes.length > 3
            ? "max-w-2xl max-h-[90vh] overflow-y-auto"
            : undefined
        }>
          <DialogHeader>
            <DialogTitle>{editing ? "Edit" : "New"} {meta.name}</DialogTitle>
            <DialogDescription>
              {editing ? "Update the record fields below." : "Fill in the fields to create a new record."}
            </DialogDescription>
          </DialogHeader>
          <EntityForm
            baseFields={[
              { label: "Code", key: "code", maxLength: meta.codeLength },
              { label: "Description", key: "description" },
            ]}
            attributes={meta.attributes}
            initial={
              editing
                ? {
                    code: editing._code,
                    description: editing._description,
                    ...Object.fromEntries(
                      meta.attributes.map((a) => [a.fieldName, editing[a.columnName]])
                    ),
                  }
                : {}
            }
            onSubmit={handleSave}
            onCancel={() => setDialogOpen(false)}
          />
        </DialogContent>
      </Dialog>

      <DeleteDialog
        open={deleteTarget !== null}
        onOpenChange={(open) => { if (!open) setDeleteTarget(null); }}
        onConfirm={handleDelete}
        title={`Delete ${meta.name}`}
        description="This will mark the record for deletion. Are you sure?"
      />
    </div>
  );
}
