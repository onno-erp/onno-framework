import { useCallback, useEffect, useMemo, useState } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { Pencil } from "lucide-react";
import { api } from "@/lib/api";
import { toSnakeCase, displayValue } from "@/lib/utils";
import type { DocumentMeta, EntityRecord } from "@/lib/types";
import { useUiEvents } from "@/hooks/use-ui-events";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Skeleton } from "@/components/ui/skeleton";
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
import { PageHeader } from "@/components/page-header";
import { EntityForm } from "@/components/entity-form";

export function DocumentDetailView() {
  const { name, id } = useParams<{ name: string; id: string }>();
  const navigate = useNavigate();
  const [meta, setMeta] = useState<DocumentMeta | null>(null);
  const [doc, setDoc] = useState<EntityRecord | null>(null);
  const [editOpen, setEditOpen] = useState(false);

  const reload = useCallback(() => {
    if (!name || !id) return;
    api.getDocument(name, id).then(setDoc);
  }, [name, id]);

  useEffect(() => {
    if (!name || !id) return;
    api.getDocuments().then((all) => {
      setMeta(all.find((d) => toSnakeCase(d.name) === name) ?? null);
    });
    reload();
  }, [name, id]);

  useUiEvents(useCallback((event) => {
    if (!meta || event.entityType !== "document") return;
    if (event.entityName === meta.name && (!event.id || event.id === id)) reload();
  }, [id, meta, reload]));

  const detailAttrs = useMemo(
    () => meta?.attributes.filter((a) => a.visibleInDetail !== false).sort((a, b) => (a.order ?? 0) - (b.order ?? 0)) ?? [],
    [meta]
  );

  const handlePost = async () => {
    if (!name || !id) return;
    await api.postDocument(name, id);
    reload();
  };

  const handleUnpost = async () => {
    if (!name || !id) return;
    await api.unpostDocument(name, id);
    reload();
  };

  const handleUpdate = async (data: EntityRecord, andPost?: boolean) => {
    if (!name || !id) return;
    // If doc was posted, unpost first
    if (doc?._posted) {
      await api.unpostDocument(name, id);
    }
    await api.updateDocument(name, id, data);
    if (andPost) {
      await api.postDocument(name, id);
    }
    setEditOpen(false);
    reload();
  };

  if (!meta || !doc) {
    return (
      <div className="animate-in-page">
        <div className="mb-6">
          <Skeleton className="h-4 w-64 mb-3" />
          <Skeleton className="h-8 w-48 mb-2" />
        </div>
        <Card>
          <CardHeader>
            <Skeleton className="h-4 w-16" />
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-x-6 gap-y-4">
              {Array.from({ length: 4 }).map((_, i) => (
                <div key={i} className="space-y-2">
                  <Skeleton className="h-3 w-16" />
                  <Skeleton className="h-4 w-32" />
                </div>
              ))}
            </div>
          </CardContent>
        </Card>
      </div>
    );
  }

  return (
    <div className="animate-in-page">
      <PageHeader
        title={`${meta.name} #${doc._number as string}`}
        breadcrumbs={[
          { label: "Documents" },
          { label: meta.name, href: `/documents/${name}` },
          { label: `#${doc._number as string}` },
        ]}
        badge={
          <Badge variant={doc._posted ? "success" : "secondary"}>
            {doc._posted ? "Posted" : "Draft"}
          </Badge>
        }
        actions={
          <div className="flex items-center gap-2">
            <Button variant="outline" size="sm" onClick={() => setEditOpen(true)}>
              <Pencil className="h-3.5 w-3.5 mr-1.5" />
              Edit
            </Button>
            {doc._posted ? (
              <Button variant="outline" size="sm" onClick={handleUnpost}>
                Unpost
              </Button>
            ) : (
              <Button size="sm" onClick={handlePost}>
                Post
              </Button>
            )}
          </div>
        }
      />

      <Card className="mb-6">
        <CardHeader className="pb-4">
          <CardTitle className="text-[13px] font-medium text-muted-foreground">
            Details
          </CardTitle>
        </CardHeader>
        <CardContent>
          <dl className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-x-6 gap-y-4 text-sm">
            <div className="space-y-1">
              <dt className="text-xs text-muted-foreground">Number</dt>
              <dd className="font-mono">{doc._number as string}</dd>
            </div>
            <div className="space-y-1">
              <dt className="text-xs text-muted-foreground">Date</dt>
              <dd>{doc._date ? new Date(doc._date as string).toLocaleString() : "—"}</dd>
            </div>
            {detailAttrs.map((a) => (
              <div key={a.fieldName} className="space-y-1">
                <dt className="text-xs text-muted-foreground">{a.displayName}</dt>
                <dd>{displayValue(a, doc[a.columnName], doc) || "—"}</dd>
              </div>
            ))}
          </dl>
        </CardContent>
      </Card>

      {meta.tabularSections.length > 0 && (
        <div className="mt-8">
          <Tabs defaultValue={meta.tabularSections[0].name}>
            <TabsList>
              {meta.tabularSections.map((ts) => (
                <TabsTrigger key={ts.name} value={ts.name}>
                  {ts.name}
                </TabsTrigger>
              ))}
            </TabsList>

            {meta.tabularSections.map((ts) => {
              const rows = (doc[ts.name] as EntityRecord[]) ?? [];
              return (
                <TabsContent key={ts.name} value={ts.name}>
                  <div className="rounded-lg border overflow-hidden">
                    <Table>
                      <TableHeader>
                        <TableRow>
                          <TableHead>#</TableHead>
                          {ts.attributes.map((a) => (
                            <TableHead key={a.fieldName}>{a.displayName}</TableHead>
                          ))}
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {rows.length === 0 && (
                          <TableRow>
                            <TableCell
                              colSpan={1 + ts.attributes.length}
                              className="text-center text-muted-foreground py-8"
                            >
                              No rows
                            </TableCell>
                          </TableRow>
                        )}
                        {rows.map((row, i) => (
                          <TableRow key={i}>
                            <TableCell>{(row._line_number as number) ?? i + 1}</TableCell>
                            {ts.attributes.map((a) => (
                              <TableCell key={a.fieldName}>
                                {displayValue(a, row[a.columnName], row)}
                              </TableCell>
                            ))}
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </div>
                </TabsContent>
              );
            })}
          </Tabs>
        </div>
      )}

      <Dialog open={editOpen} onOpenChange={setEditOpen}>
        <DialogContent className={
          meta.tabularSections.length > 0 || meta.attributes.length > 6
            ? "max-w-5xl max-h-[90vh] overflow-y-auto"
            : meta.attributes.length > 3
            ? "max-w-2xl max-h-[90vh] overflow-y-auto"
            : undefined
        }>
          <DialogHeader>
            <DialogTitle>Edit {meta.name} #{doc._number as string}</DialogTitle>
            <DialogDescription>Update the document fields below.</DialogDescription>
          </DialogHeader>
          <EntityForm
            baseFields={[
              { label: "Number", key: "number" },
              { label: "Date", key: "date", type: "datetime-local" },
            ]}
            attributes={meta.attributes}
            tabularSections={meta.tabularSections}
            initial={{
              number: doc._number,
              date: doc._date,
              ...Object.fromEntries(
                meta.attributes.map((a) => [a.fieldName, doc[a.columnName]])
              ),
              ...Object.fromEntries(
                meta.tabularSections.map((ts) => {
                  const rows = (doc[ts.name] as EntityRecord[]) ?? [];
                  return [ts.name, rows.map((row) =>
                    Object.fromEntries(
                      ts.attributes.map((a) => [a.fieldName, row[a.columnName]])
                    )
                  )];
                })
              ),
            }}
            onSubmit={handleUpdate}
            showSaveAndPost
            onCancel={() => setEditOpen(false)}
          />
        </DialogContent>
      </Dialog>
    </div>
  );
}
