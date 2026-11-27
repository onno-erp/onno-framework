import { useEffect } from "react";
import { useQuery } from "@tanstack/react-query";
import { modelEntities, type AppModel, type ModelEntity } from "./app-model";
import { queryClient } from "./query";

/**
 * The business model as the signed-in user sees it (`GET /api/ui/model`), as server state in the
 * shell's TanStack Query cache. The shell loads it once ({@link useModelLoader}); everything else
 * reads the current one with {@link useModel}.
 */
const CURRENT = ["onno-model"] as const;
const LOADED = ["onno-model", "loaded"] as const;

async function fetchModel(): Promise<AppModel> {
  const r = await fetch("/api/ui/model", { credentials: "include" });
  if (!r.ok) throw new Error(`HTTP ${r.status}`);
  return (await r.json()) as AppModel;
}

/** Loads the model for the signed-in user (the shell calls this once it is authenticated). */
export function useModelLoader(): AppModel | null {
  const { data } = useQuery({ queryKey: LOADED, queryFn: fetchModel, staleTime: Infinity }, queryClient);
  useEffect(() => {
    if (data) queryClient.setQueryData(CURRENT, data);
  }, [data]);
  return data ?? null;
}

/** The business model as the signed-in user sees it, or null until it loads. */
export function useModel(): AppModel | null {
  const { data } = useQuery({ queryKey: CURRENT, queryFn: () => modelOutsideReact() as AppModel, enabled: false, staleTime: Infinity }, queryClient);
  return data ?? null;
}

/**
 * The current model for code outside React — the `onno://` link builders and `openRecord`, called
 * from handlers. Components read {@link useModel}.
 */
export function modelOutsideReact(): AppModel | null {
  return queryClient.getQueryData<AppModel>(CURRENT) ?? null;
}

/** Replace the current model (tests, and the shell after it loads one). */
export function setModel(model: AppModel | null): void {
  if (model) queryClient.setQueryData(CURRENT, model);
  else queryClient.removeQueries({ queryKey: CURRENT });
}

/** Forget every loaded model (sign-out), so the next user loads their own. */
export function clearModel(): void {
  queryClient.removeQueries({ queryKey: CURRENT });
}

/** Find a model entity by route kind + name, its logical name or its route. */
export function findEntity(model: AppModel | null, ref: string, kind?: string): ModelEntity | undefined {
  if (!model) return undefined;
  const r = ref.replace(/^\//, "");
  return modelEntities(model).find((e) =>
    (!kind || e.kind === kind) &&
    (e.name === r || e.logicalName === ref || e.route === "/" + r || `${e.kind}/${e.name}` === r));
}
