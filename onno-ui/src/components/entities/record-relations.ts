import { useCallback, useMemo, useState } from "react";
import { useQueries } from "@tanstack/react-query";
import { api, type CollectionPage } from "../../lib/api";
import type { ModelEntity } from "../../lib/app-model";
import type { RelationCustomization } from "../../lib/entity-customizations";
import { findEntity, useModel } from "../../lib/model-store";
import { invalidateEntity, queryClient } from "../../lib/query";
import type { AttributeMeta, EntityRecord, TabularSectionMeta } from "../../lib/types";

/**
 * Many-to-many relations on a record form. Each relation is a join entity whose rows reference the
 * record through `via`; the form edits them with the tabular-section editor and saves them with the
 * record (the server replaces the row set in the record's transaction — see
 * `RelationWriteService`). Rows keep their join-record `id`, so an edit updates that link instead of
 * recreating it, and fields the editor does not show are left as stored.
 */
export type RecordRelation = {
  key: string;
  spec: RelationCustomization;
  entity: ModelEntity | null;
  /** The editor's section: the join entity's fields as columns, `via` excluded. Null until loaded. */
  section: TabularSectionMeta | null;
  rows: EntityRecord[];
  /** The stored rows are in (always true on a new record), so a save may replace them. */
  loaded: boolean;
  readOnly: boolean;
};

/** One relation in a save body (`@relations`): the complete row set for the record. */
export type RelationWrite = { entity: string; via: string; rows: EntityRecord[] };

type JoinForm = { form?: { meta?: { attributes?: AttributeMeta[] } } };

// A relation write replaces the complete set. Publish rows only after every window succeeds:
// treating a preview or a failed continuation as a complete set would delete unseen links.
async function readRelationRows(entity: ModelEntity, spec: RelationCustomization, ownerId: string): Promise<EntityRecord[]> {
  const where = { [spec.via]: { eq: ownerId } };
  const rows: EntityRecord[] = [];
  const seen = new Set<string>();
  let cursor: string | null = null;
  do {
    const request = { limit: 500, sort: spec.sort, cursor };
    const page: CollectionPage<EntityRecord> = entity.kind === "documents"
      ? await api.listDocumentsPage(entity.name, undefined, undefined, where, request)
      : await api.listCatalogPage(entity.name, where, request);
    rows.push(...page.rows);
    if (!page.hasMore) return rows;
    cursor = page.nextCursor;
    if (!cursor || seen.has(cursor)) throw new Error("Relation feed returned an invalid continuation");
    seen.add(cursor);
  } while (cursor);
  return rows;
}

async function fetchJoinAttributes(route: string): Promise<AttributeMeta[]> {
  const r = await fetch(`/api/ui/view${route}/new`, { credentials: "include" });
  if (!r.ok) throw new Error(`HTTP ${r.status}`);
  const view = (await r.json()) as JoinForm;
  return view.form?.meta?.attributes ?? [];
}

function sectionFor(key: string, spec: RelationCustomization, entity: ModelEntity, attributes: AttributeMeta[]): TabularSectionMeta {
  const byName = new Map(attributes.map((a) => [a.fieldName, a]));
  const names = spec.columns?.length
    ? spec.columns.filter((c) => c !== spec.via)
    : attributes.filter((a) => a.visibleInForm !== false && a.fieldName !== spec.via).map((a) => a.fieldName);
  const columns = names.map((n) => byName.get(n)).filter((a): a is AttributeMeta => !!a)
    .map((a, index) => ({
      ...a,
      visibleInForm: true,
      order: index,
      // The first reference is the related record: link it once.
      uniqueWithinSection: a.uniqueWithinSection ?? (a.isRef && index === names.findIndex((n) => byName.get(n)?.isRef)),
    }));
  return { name: key, tableName: "", label: spec.label ?? entity.title, attributes: columns };
}

/** A stored join record as an editor row: its id plus the section's columns. */
function toRow(record: EntityRecord, section: TabularSectionMeta): EntityRecord {
  const row: EntityRecord = { id: record.id };
  for (const a of section.attributes) {
    if (!a.secret && record[a.fieldName] != null) row[a.fieldName] = record[a.fieldName];
  }
  return row;
}

function isBlank(row: EntityRecord, section: TabularSectionMeta): boolean {
  return !row.id && section.attributes.every((a) => {
    const v = row[a.fieldName];
    return v === null || v === undefined || v === "" || v === false;
  });
}

/**
 * The relations of the record `ownerId` (null for a new record), their rows and edit state. `markDirty`
 * is called on every edit so the form's discard guard covers them.
 */
export function useRecordRelations(
  specs: Record<string, RelationCustomization> | undefined,
  ownerId: string | null,
  markDirty: () => void,
) {
  const model = useModel();
  const entries = useMemo(() => Object.entries(specs ?? {}).map(([key, spec]) => ({
    key, spec, entity: findEntity(model, spec.entity) ?? null,
  })), [specs, model]);

  const metas = useQueries({
    queries: entries.map(({ entity }) => ({
      queryKey: ["onno", entity?.logicalName ?? "", "relation-form"],
      queryFn: () => fetchJoinAttributes(entity!.route),
      enabled: !!entity,
      staleTime: Infinity,
    })),
  }, queryClient);

  const stored = useQueries({
    queries: entries.map(({ spec, entity }) => ({
      queryKey: ["onno", entity?.logicalName ?? "", "relation-rows", spec.via, ownerId, spec.sort ?? null],
      queryFn: () => readRelationRows(entity!, spec, ownerId!),
      enabled: !!entity && !!ownerId,
    })),
  }, queryClient);

  // Unsaved edits per relation; absent, the editor shows the stored rows.
  const [drafts, setDrafts] = useState<Record<string, EntityRecord[]>>({});

  const relations: RecordRelation[] = entries.map(({ key, spec, entity }, i) => {
    const attributes = metas[i]?.data;
    const section = entity && attributes ? sectionFor(key, spec, entity, attributes) : null;
    const loaded = !!section && (!ownerId || stored[i]?.isSuccess === true);
    const rows = drafts[key]
      ?? (section && ownerId ? (stored[i]?.data ?? []).map((r) => toRow(r, section)) : []);
    return { key, spec, entity, section, rows, loaded, readOnly: !entity?.canWrite };
  });

  const setRows = useCallback((key: string, rows: EntityRecord[]) => {
    markDirty();
    setDrafts((prev) => ({ ...prev, [key]: rows }));
  }, [markDirty]);

  /** What a save sends: the edited relations whose stored rows are known, blank rows dropped. */
  const payload = (): RelationWrite[] => relations
    .filter((r) => r.loaded && r.section && !r.readOnly && drafts[r.key])
    .map((r) => ({
      entity: r.spec.entity,
      via: r.spec.via,
      rows: r.rows.filter((row) => !isBlank(row, r.section!)).map((row) => {
        const out: EntityRecord = row.id ? { id: row.id } : {};
        for (const a of r.section!.attributes) {
          const v = row[a.fieldName];
          out[a.fieldName] = /^(boolean|Boolean)$/.test(a.javaType) ? v === true : v ?? null;
        }
        return out;
      }),
    }));

  /** Drop the drafts and reload the stored rows (after a save, or a discard). */
  const reset = useCallback(() => {
    setDrafts({});
    for (const { entity } of entries) if (entity) invalidateEntity(entity.logicalName);
  }, [entries]);

  return { relations, setRows, payload, reset };
}
