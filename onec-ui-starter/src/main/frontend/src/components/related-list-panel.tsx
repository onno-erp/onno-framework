import { useCallback, useEffect, useMemo, useState } from "react";
import { toast } from "sonner";
import { Plus, Trash2 } from "lucide-react";
import type { AttributeMeta, EntityRecord, RelatedListMeta } from "@/lib/types";
import { api } from "@/lib/api";
import { cn } from "@/lib/utils";
import { RefSelect } from "@/components/ref-select";

const actionBtn =
  "inline-flex items-center gap-1.5 rounded-lg bg-secondary px-3.5 py-2 text-sm font-medium transition-colors hover:bg-accent disabled:opacity-50";

/**
 * Inline related-list panel for a catalog editor: the catalog-side analogue of a document's
 * tabular section, backed by a join catalog instead of an owned child table. It reads the join
 * rows whose {@code viaField} ref points at the record being edited, renders one line per row,
 * and lets the user add a row (pick the other side → create a join record with {@code viaField}
 * set to this record) or remove one (delete the join record). Both sides of the relationship can
 * mount this panel against the same join catalog, so they stay consistent with no mirroring.
 *
 * Only meaningful once the parent record exists — a brand-new, unsaved catalog item has no id to
 * scope rows to, so the panel asks the user to save first.
 */
export function RelatedListPanel({
  catalog,
  parentId,
  meta,
}: {
  catalog: string;
  parentId: string | null;
  meta: RelatedListMeta;
}) {
  const title = useMemo(() => {
    if (meta.label && meta.label.trim()) return meta.label;
    return meta.name.charAt(0).toUpperCase() + meta.name.slice(1);
  }, [meta]);

  // The column that carries the "other side" display ref — used to render each row's primary
  // label and to drive the add-row picker. Falls back to the first column when not found.
  const displayCol = useMemo<AttributeMeta | undefined>(
    () => meta.columns.find((c) => c.fieldName === meta.displayField) ?? meta.columns[0],
    [meta]
  );

  const [rows, setRows] = useState<EntityRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [adding, setAdding] = useState(false);
  const [busy, setBusy] = useState(false);

  const reload = useCallback(async () => {
    if (!parentId) return;
    setLoading(true);
    try {
      setRows(await api.getRelatedList(catalog, parentId, meta.name));
    } catch (e) {
      toast.error(`Couldn't load ${title}: ${e instanceof Error ? e.message : String(e)}`);
    } finally {
      setLoading(false);
    }
  }, [catalog, parentId, meta.name, title]);

  useEffect(() => {
    void reload();
  }, [reload]);

  // Add a row by creating a join record: the via ref points back at this record, the display ref
  // holds the picked target. The join catalog's own write roles are enforced server-side.
  const add = async (targetId: string) => {
    if (!parentId || !targetId) return;
    setBusy(true);
    try {
      await api.createCatalogItem(meta.joinCatalog, {
        [meta.viaField]: parentId,
        [meta.displayField]: targetId,
      });
      setAdding(false);
      await reload();
    } catch (e) {
      toast.error(`Couldn't add: ${e instanceof Error ? e.message : String(e)}`);
    } finally {
      setBusy(false);
    }
  };

  const remove = async (rowId: string) => {
    setBusy(true);
    try {
      await api.deleteCatalogItem(meta.joinCatalog, rowId);
      await reload();
    } catch (e) {
      toast.error(`Couldn't remove: ${e instanceof Error ? e.message : String(e)}`);
    } finally {
      setBusy(false);
    }
  };

  // Render a single join-row cell: refs/enums resolve to a "*_display" label server-side; fall
  // back to the raw column value for plain attributes (e.g. a role string on the join row).
  const cell = (row: EntityRecord, col: AttributeMeta): string => {
    const display = row[`${col.columnName}_display`];
    if (display != null && display !== "") return String(display);
    const raw = row[col.columnName];
    return raw == null ? "" : String(raw);
  };

  return (
    <div className="mt-4 rounded-2xl border border-border bg-card p-4 sm:p-5">
      <div className="mb-3 flex items-center justify-between">
        <h2 className="text-sm font-semibold text-foreground">{title}</h2>
        {parentId && !adding ? (
          <button
            type="button"
            className={cn(actionBtn, "text-foreground")}
            onClick={() => setAdding(true)}
            disabled={busy}
          >
            <Plus className="size-4" aria-hidden="true" />
            Add
          </button>
        ) : null}
      </div>

      {!parentId ? (
        <p className="text-sm text-muted-foreground">Save this record first to manage {title.toLowerCase()}.</p>
      ) : (
        <>
          {adding && displayCol ? (
            // A one-shot picker for the other side; choosing a record adds the row immediately.
            <div className="mb-3 flex items-center gap-2">
              <div className="min-w-0 grow">
                <RefSelect
                  targetName={meta.target}
                  refKind={meta.targetKind}
                  value={undefined}
                  onChange={(id) => void add(String(id))}
                />
              </div>
              <button
                type="button"
                className={cn(actionBtn, "text-muted-foreground hover:text-foreground")}
                onClick={() => setAdding(false)}
                disabled={busy}
              >
                Cancel
              </button>
            </div>
          ) : null}

          {loading ? (
            <p className="text-sm text-muted-foreground">Loading…</p>
          ) : rows.length === 0 ? (
            <p className="text-sm text-muted-foreground">No rows yet.</p>
          ) : (
            <div className="space-y-1">
              {rows.map((row) => {
                const rowId = String(row._id);
                return (
                  <div
                    key={rowId}
                    className="group flex items-center gap-3 rounded-lg px-2 py-1.5 transition-colors hover:bg-muted/40"
                  >
                    {meta.columns.map((col) => (
                      <div
                        key={col.fieldName}
                        className={cn(
                          "min-w-0 truncate text-sm",
                          col.fieldName === displayCol?.fieldName
                            ? "grow font-medium text-foreground"
                            : "shrink-0 basis-40 text-muted-foreground"
                        )}
                        title={cell(row, col)}
                      >
                        {cell(row, col) || "—"}
                      </div>
                    ))}
                    <button
                      type="button"
                      onClick={() => void remove(rowId)}
                      disabled={busy}
                      aria-label="Remove row"
                      title="Remove"
                      className="grid size-8 shrink-0 place-items-center rounded-md text-muted-foreground opacity-50 transition-colors hover:bg-accent hover:text-destructive group-hover:opacity-100"
                    >
                      <Trash2 className="size-4" aria-hidden="true" />
                    </button>
                  </div>
                );
              })}
            </div>
          )}
        </>
      )}
    </div>
  );
}
