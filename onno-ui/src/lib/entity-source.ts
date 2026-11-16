import { useMemo } from "react";
import { findEntity, useModel } from "./model-store";
import type { WhereClause } from "./measure";

/**
 * What a data component reads: one entity of the model, by logical name, plus the date field it
 * windows by and which records (`where`). Components take an `entity` prop; this resolves it.
 */
export type EntitySource = {
  entityType: "catalog" | "document" | "register";
  /** The entity's logical name (`"Orders"`), as the data API and live events name it. */
  entityName: string;
  dateField: string;
  /** Which records (sent to the server as JSON). */
  where?: WhereClause;
};

/** The source for an `entity` prop (logical name, route or route name), or null until the model has it. */
export function useEntitySource(
  entity: string | undefined,
  options: { dateField?: string; where?: WhereClause } = {},
): EntitySource | null {
  const model = useModel();
  const { dateField, where } = options;
  // Keyed by content: callers pass a fresh object literal each render.
  const whereKey = where ? JSON.stringify(where) : "";
  return useMemo(() => {
    const e = entity ? findEntity(model, entity) : undefined;
    if (!e) return null;
    const entityType = e.kind === "catalogs" ? "catalog" : e.kind === "documents" ? "document" : "register";
    return { entityType, entityName: e.logicalName, dateField: dateField ?? "", where: whereKey ? JSON.parse(whereKey) : undefined };
  }, [model, entity, dateField, whereKey]);
}
