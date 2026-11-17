import { findEntity, modelOutsideReact } from "./model-store";
import type { WhereClause } from "./measure";

/**
 * `onno://` links: the deep-link protocol every onno client shares (this web shell today, mobile
 * clients later). A link names what to open, not how a client shows it:
 *
 *   onno://documents/orders/<id>            a record
 *   onno://documents/orders/new?customer=…  a new record, pre-filled
 *   onno://documents/orders?where=<json>    a list showing only some records
 *   onno://ops                              an app page (`/ops`); `onno://` is home
 *
 * Entities appear by their route segment (`documents/orders`); the builders below take the logical
 * name and resolve it through the model, so build links once the model is loaded (in handlers and
 * components, not at module scope).
 */
export type OnnoLink = `onno://${string}`;

function route(entity: string): string {
  const e = findEntity(modelOutsideReact(), entity);
  if (!e) throw new Error(`onno: no entity named "${entity}" in the model`);
  return e.route.slice(1);
}

function query(params: Record<string, string>): string {
  const qs = new URLSearchParams(params).toString();
  return qs ? `?${qs}` : "";
}

/** Typed `onno://` link builders; pass the result to `openRoute` or use it as data (notifications, QR codes). */
export const links = {
  /** One record's screen. */
  record: (entity: string, id: string): OnnoLink => `onno://${route(entity)}/${id}`,
  /** A new record's screen, its fields optionally pre-filled (ids for references and enums, ISO for dates). */
  newRecord: (entity: string, prefill?: Record<string, string>): OnnoLink =>
    `onno://${route(entity)}/new${query(prefill ?? {})}`,
  /** An entity's list, optionally showing only the records `where` selects. */
  list: (entity: string, options?: { where?: WhereClause }): OnnoLink => {
    const where = options?.where && Object.keys(options.where).length ? JSON.stringify(options.where) : undefined;
    return `onno://${route(entity)}${query(where ? { where } : {})}`;
  },
  /** An app page by its path (`"/ops"`); `"/"` is home. */
  page: (path: `/${string}`): OnnoLink => `onno://${path.slice(1)}`,
};

/** The records a list link (`…?where=<json>`) selects, or undefined when it names none or is malformed. */
export function linkedWhere(path: string): WhereClause | undefined {
  const raw = new URLSearchParams(path.split("?")[1] ?? "").get("where");
  if (!raw) return undefined;
  try {
    const where = JSON.parse(raw) as unknown;
    if (where && typeof where === "object" && !Array.isArray(where)) return where as WhereClause;
  } catch {
    // fall through
  }
  console.warn(`[onno] ignoring a malformed where in ${path}`);
  return undefined;
}
