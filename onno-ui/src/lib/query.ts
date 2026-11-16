import { useEffect, useRef } from "react";
import { QueryClient, useMutation, useQuery, type UseMutationResult, type UseQueryResult } from "@tanstack/react-query";
import { allRegisterRows, api } from "./api";
import { subscribeUiEvents } from "./ui-event-bus";
import { findEntity, modelOutsideReact, useModel } from "./model-store";
import type { AppModel, ModelEntity } from "./app-model";
import type { EntityRecord } from "./types";
import type { CommandInputs, CommandResult } from "./types";
import type { AggregateBuckets } from "./aggregate-data";
import { aggregateParams, type GroupSpec, type MeasureSpec, type WhereClause } from "./measure";

/**
 * The app's server state: one TanStack Query client shared by the shell and every app component
 * (the `@onno/ui/vite` preset dedupes `@tanstack/react-query`, like React, so an app shares this instance). Entity data is
 * keyed `["onno", logicalName, …]`; the server's live event stream invalidates exactly the entity
 * that changed, so every query over it refetches — no polling, no manual reloads.
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Live updates arrive over SSE, so data stays fresh until an event says otherwise.
      staleTime: 30_000,
      refetchOnWindowFocus: false,
      retry: 1,
    },
  },
});

/** Query keys for entity data. `entityKey(name)` prefixes all of them (what an event invalidates). */
export const queryKeys = {
  entity: (logicalName: string) => ["onno", logicalName] as const,
  records: (logicalName: string, where?: WhereClause, page?: { sort?: string; limit?: number }) =>
    ["onno", logicalName, "records", where ?? null, page?.sort ?? null, page?.limit ?? null] as const,
  record: (logicalName: string, id: string) => ["onno", logicalName, "record", id] as const,
  aggregate: (logicalName: string, params: Record<string, string>) => ["onno", logicalName, "aggregate", params] as const,
};

/**
 * One entity's change signal, kept in the query cache beside its data: `seq` counts the changes,
 * `ids` names the records the last change touched in place, or is null when the set itself may have
 * changed (a create or delete, a command's refresh, several changes at once).
 */
export type EntityChange = { seq: number; ids: readonly string[] | null };

/**
 * The change signal's key. It names the entity by its route segment (`"sales_orders"`), which both
 * a server event (by snake-casing its logical name, like the server's routes) and a list (by its
 * route) know without the model — a list may show an entity the model doesn't offer the user.
 */
const changesKey = (route: string) => ["onno-changes", route] as const;

// Mirror of the server's snake-casing (UiLayoutResolver.toSnakeCase): logical name → route segment.
function routeSegment(logicalName: string): string {
  return logicalName.replace(/ /g, "").replace(/([a-z0-9])([A-Z])/g, "$1_$2").toLowerCase();
}

/** An entity's route segment from a logical name or a route (`"documents/orders"`, `"/documents/orders"`). */
function changeRoute(model: AppModel | null, entity: string): string {
  const e = findEntity(model, entity);
  if (e) return e.name;
  return entity.includes("/") ? entity.slice(entity.lastIndexOf("/") + 1) : routeSegment(entity);
}

function bump(route: string, ids: readonly string[] | null): void {
  queryClient.setQueryData<EntityChange>(changesKey(route), (prev) => ({ seq: (prev?.seq ?? 0) + 1, ids }));
}

/**
 * Mark an entity's data changed: every query over it refetches, and every {@link useEntityChanges}
 * subscriber hears about it. `ids` names the records that changed in place (a list patches just
 * those rows); omitted, the subscribers reload. `entity` is a logical name or a route
 * (`"documents/orders"`, what a command or list knows itself by). This is what a server change
 * event does, and what a command's refresh does.
 */
export function invalidateEntity(entity: string, ids?: readonly string[]): void {
  const model = modelOutsideReact();
  const e = findEntity(model, entity);
  bump(changeRoute(model, entity), ids?.length ? ids : null);
  void queryClient.invalidateQueries({ queryKey: queryKeys.entity(e?.logicalName ?? entity) });
}

/**
 * Call `onChange` whenever the entity's data changes — a server event, a command's refresh, a
 * write through the hooks below — for components that keep their own view of the data (a list's
 * keyset window, a form's draft) instead of reading it through a query. `entity` is a logical
 * name or route (`"documents/orders"`); `onChange` receives the records changed in place, or null.
 */
export function useEntityChanges(entity: string | null | undefined, onChange: (ids: readonly string[] | null) => void): void {
  const model = useModel();
  const route = entity ? changeRoute(model, entity) : "";
  const { data } = useQuery({
    queryKey: changesKey(route),
    // A cache cell, not a fetch: invalidateEntity writes it.
    queryFn: () => queryClient.getQueryData<EntityChange>(changesKey(route)) ?? { seq: 0, ids: null },
    enabled: false,
    staleTime: Infinity,
  }, queryClient);
  const handler = useRef(onChange);
  handler.current = onChange;
  // The signal as this component first saw it: only later changes call back.
  const seen = useRef<{ route: string; seq: number } | null>(null);
  useEffect(() => {
    const seq = data?.seq ?? 0;
    const last = seen.current;
    seen.current = { route, seq };
    if (!route || !last || last.route !== route || seq <= last.seq) return;
    // Two changes landed between renders: only a reload covers both.
    handler.current(seq === last.seq + 1 ? data!.ids : null);
  }, [data, route]);
}

const IN_PLACE = new Set(["updated", "posted", "unposted", "changed"]);

let invalidationStarted = false;

/**
 * Route the server's change events into the query cache — the one way live data reaches the UI.
 * Started once by the app shell.
 */
export function startQueryInvalidation(): () => void {
  if (invalidationStarted) return () => {};
  invalidationStarted = true;
  // Per entity: the records changed in place, or null once its set may have changed.
  const pending = new Map<string, Set<string> | null>();
  let timer: number | undefined;
  const flush = () => {
    for (const [name, ids] of pending) {
      if (name === "*") {
        // Posting announces its register changes as "*": refresh every register.
        const model = modelOutsideReact();
        if (model) {
          for (const e of model.entities) if (e.kind === "registers") invalidateEntity(e.logicalName);
          for (const r of model.informationRegisters) invalidateEntity(r.logicalName);
        } else {
          void queryClient.invalidateQueries({ queryKey: ["onno"] });
          for (const q of queryClient.getQueryCache().findAll({ queryKey: ["onno-changes"] })) bump(String(q.queryKey[1]), null);
        }
      } else {
        invalidateEntity(name, ids ? [...ids] : undefined);
      }
    }
    pending.clear();
  };
  const off = subscribeUiEvents((event) => {
    const name = event.entityName;
    if (!name || event.entityType === "presence" || event.entityType === "comment") return;
    const known = pending.get(name);
    if (event.id && IN_PLACE.has(event.type) && known !== null) pending.set(name, (known ?? new Set()).add(event.id));
    else pending.set(name, null);
    // Coalesce bursts (a post emits the document change plus every register it touched).
    window.clearTimeout(timer);
    timer = window.setTimeout(flush, 100);
  });
  return () => {
    invalidationStarted = false;
    window.clearTimeout(timer);
    off();
  };
}

type EntityQuery<T> = UseQueryResult<T> & { entity: ModelEntity | null };

/**
 * An entity's records, live: those `where` selects, in `sort` order (a field name, `-` prefix for
 * descending; default the server's), at most `limit` (default and cap 500).
 *
 *   const { data: orders = [], isPending } = useRecords("Orders", { where: { open: true }, sort: "-date", limit: 20 });
 */
export function useRecords(
  entity: string,
  options: { where?: WhereClause; sort?: string; limit?: number } = {},
): EntityQuery<EntityRecord[]> {
  const e = findEntity(useModel(), entity);
  const where = options.where && Object.keys(options.where).length ? options.where : undefined;
  const page = { sort: options.sort, limit: options.limit };
  const result = useQuery({
    queryKey: queryKeys.records(e?.logicalName ?? entity, where, page),
    enabled: !!e && e.kind !== "registers",
    queryFn: () =>
      e!.kind === "documents"
        ? api.listDocuments(e!.name, undefined, undefined, where, page)
        : api.listCatalog(e!.name, where, page),
  });
  return Object.assign(result, { entity: e ?? null });
}

/** One record by id, live. */
export function useRecord(entity: string, id: string | null | undefined): EntityQuery<EntityRecord> {
  const e = findEntity(useModel(), entity);
  const result = useQuery({
    queryKey: queryKeys.record(e?.logicalName ?? entity, id ?? ""),
    enabled: !!e && !!id && e.kind !== "registers",
    queryFn: () => (e!.kind === "documents" ? api.getDocument(e!.name, id!) : api.getCatalogItem(e!.name, id!)),
  });
  return Object.assign(result, { entity: e ?? null });
}

/**
 * A number (or one per group) over an entity's records, live — what the charts and stats read.
 *
 *   const { data } = useAggregate(sum("Orders", "total", { posted: true }), { date: "week" });
 *   data?.buckets.map((b) => [b.label, b.value]);
 */
export function useAggregate(measure: MeasureSpec, by?: GroupSpec): EntityQuery<AggregateBuckets> {
  const e = findEntity(useModel(), measure.entity);
  const params = aggregateParams(measure, by);
  const result = useQuery({
    queryKey: queryKeys.aggregate(e?.logicalName ?? measure.entity, params),
    enabled: !!e,
    queryFn: () => api.aggregate(e!.kind, e!.name, params),
  });
  return Object.assign(result, { entity: e ?? null });
}

// ---- registers -------------------------------------------------------------------------------------

/** A register's balances — current, or `asOf` an ISO date(-time) — the rows `where` selects, live. */
export function useBalance(register: string, options: { where?: WhereClause; asOf?: string } = {}): EntityQuery<EntityRecord[]> {
  const e = findEntity(useModel(), register, "registers");
  const result = useQuery({
    queryKey: [...queryKeys.entity(e?.logicalName ?? register), "balance", options.where ?? null, options.asOf ?? null],
    enabled: !!e,
    queryFn: () => allRegisterRows((page) => api.getBalance(e!.name, { ...options, ...page })),
  });
  return Object.assign(result, { entity: e ?? null });
}

/** A register's turnover between two ISO dates over the movements `where` selects, live. */
export function useTurnover(register: string, options: { from: string; to: string; where?: WhereClause }): EntityQuery<EntityRecord[]> {
  const e = findEntity(useModel(), register, "registers");
  const result = useQuery({
    queryKey: [...queryKeys.entity(e?.logicalName ?? register), "turnover", options.from, options.to, options.where ?? null],
    enabled: !!e,
    queryFn: () => allRegisterRows((page) => api.getTurnover(e!.name, { ...options, ...page })),
  });
  return Object.assign(result, { entity: e ?? null });
}

/** A register's movements (newest first), optionally between two ISO dates and narrowed by `where`, live. */
export function useMovements(register: string, options: { from?: string; to?: string; where?: WhereClause } = {}): EntityQuery<EntityRecord[]> {
  const e = findEntity(useModel(), register, "registers");
  const result = useQuery({
    queryKey: [...queryKeys.entity(e?.logicalName ?? register), "movements", options.from ?? null, options.to ?? null, options.where ?? null],
    enabled: !!e,
    queryFn: () => allRegisterRows((page) => api.getMovements(e!.name, { ...options, ...page })),
  });
  return Object.assign(result, { entity: e ?? null });
}

// ---- information registers and settings ------------------------------------------------------------

/**
 * An information register's slice of last values — per dimension tuple, the record in force `at`
 * an ISO date-time (default now) — narrowed by `where`. `register` is its logical name.
 *
 *   const { data: rates = [] } = useInformationRegister("Exchange Rates", { where: { currency: "EUR" } });
 */
export function useInformationRegister(register: string, options: { where?: WhereClause; at?: string } = {}): UseQueryResult<EntityRecord[]> {
  const model = useModel();
  const known = !!model?.informationRegisters.some((r) => r.logicalName === register);
  return useQuery({
    queryKey: ["onno", register, "slice-last", options.where ?? null, options.at ?? null],
    enabled: known,
    queryFn: () => api.getInformationSlice(register, options),
  });
}

/** The settings query: every `@Constant` with its value (administrators only, like the endpoint). */
export const settingsKey = ["onno", "settings"] as const;

/** One `@Constant`'s value, live across `Setting` editors and `useSetConstant`. */
export function useConstant(name: string): UseQueryResult<unknown> {
  const model = useModel();
  return useQuery({
    queryKey: settingsKey,
    enabled: !!model?.constants.some((c) => c.name === name),
    queryFn: () => api.getSettings(),
    select: (all) => all.find((s) => s.name === name)?.value,
  });
}

/** Set `@Constant` values by name: `set.mutate({ "Store Name": "Books & Co" })`. */
export function useSetConstants(): UseMutationResult<void, Error, Record<string, unknown>> {
  return useMutation({
    mutationFn: (values) => api.saveSettings(values),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: settingsKey }),
  });
}

// ---- writes ----------------------------------------------------------------------------------------

type Mutation<V, R> = UseMutationResult<R, Error, V> & { entity: ModelEntity | null };

/**
 * Every write goes through the same server path as the default forms (lifecycle hooks, rules,
 * access checks) and invalidates the entity's queries on success, so every view of it refreshes.
 */
function useEntityMutation<V, R>(entity: string, run: (e: ModelEntity, vars: V) => Promise<R>): Mutation<V, R> {
  const e = findEntity(useModel(), entity);
  const result = useMutation<R, Error, V>({
    mutationFn: (vars) => {
      if (!e) throw new Error(`Unknown or unreadable entity: ${entity}`);
      return run(e, vars);
    },
    onSuccess: () => {
      if (e) invalidateEntity(e.logicalName);
    },
  });
  return Object.assign(result, { entity: e ?? null });
}

/** Create a record: `create.mutate({ customer, items: [...] })`. Resolves to the saved record. */
export function useCreateRecord(entity: string): Mutation<EntityRecord, EntityRecord> {
  return useEntityMutation(entity, (e, values) =>
    e.kind === "documents" ? api.createDocument(e.name, values) : api.createCatalogItem(e.name, values));
}

/** Partially update a record: `update.mutate({ id, values: { status } })` — omitted fields keep their value. */
export function useUpdateRecord(entity: string): Mutation<{ id: string; values: EntityRecord }, EntityRecord> {
  return useEntityMutation(entity, (e, { id, values }) =>
    e.kind === "documents" ? api.updateDocument(e.name, id, values) : api.updateCatalogItem(e.name, id, values));
}

/** Delete (soft-delete) a record by id. */
export function useDeleteRecord(entity: string): Mutation<string, void> {
  return useEntityMutation(entity, (e, id) =>
    e.kind === "documents" ? api.deleteDocument(e.name, id) : api.deleteCatalogItem(e.name, id));
}

/** Post a document (or repost a posted one): writes its register movements. */
export function usePostDocument(entity: string): Mutation<string, EntityRecord> {
  return useEntityMutation(entity, (e, id) => api.postDocument(e.name, id));
}

/** Unpost a document: reverses its register movements. */
export function useUnpostDocument(entity: string): Mutation<string, EntityRecord> {
  return useEntityMutation(entity, (e, id) => api.unpostDocument(e.name, id));
}

/**
 * Run one of the entity's commands (a `Commands` bean's) by key — a record command with the
 * record's `id`, a collection command without — with the inputs it declares. Resolves to the
 * command's result ({ refresh, feedback }); a rejection (HTTP 422) rejects with its feedback.
 */
export function useCommand(entity: string, key: string): Mutation<{ id?: string; inputs?: CommandInputs } | void, CommandResult> {
  return useEntityMutation(entity, (e, vars) =>
    api.runCommand(e.kind, e.name, key, vars ? vars.id : undefined, vars ? vars.inputs : undefined));
}
