import { vi } from "vitest";
import type { AppModel, ModelEntity } from "@/lib/app-model";

/** One model entity; `kind`/`name` drive the route. */
export function entity(kind: ModelEntity["kind"], name: string, overrides: Partial<ModelEntity> = {}): ModelEntity {
  return {
    kind,
    name,
    logicalName: name,
    title: name,
    context: "",
    route: `/${kind}/${name}`,
    canWrite: true,
    ...overrides,
  };
}

/** A `GET /api/ui/model` payload with the given entities. */
export function appModel(entities: ModelEntity[] = []): AppModel {
  return {
    user: { username: "admin", displayName: "Admin", avatarUrl: "", roles: ["ADMIN"], permissions: [], unrestricted: false },
    roles: [],
    features: { comments: false, notifications: false },
    entities,
    informationRegisters: [],
    constants: [],
  };
}

/** Serve `model` for every fetch (the shell only fetches `/api/ui/model` when ContentPane is mocked). */
export function mockModelFetch(model: AppModel) {
  return vi.spyOn(globalThis, "fetch").mockImplementation(async () =>
    new Response(JSON.stringify(model), { headers: { "Content-Type": "application/json" }, status: 200 })
  );
}
