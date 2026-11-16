import { afterEach, describe, expect, it, vi } from "vitest";
import { api, normalizeExtensionApiPath } from "@/lib/api";
import { installPluginHost } from "@/lib/plugin-host";
import { RefSelect } from "@/components/ref-select";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("normalizeExtensionApiPath", () => {
  it("keeps a plain extension path and its query", () => {
    expect(normalizeExtensionApiPath("/api/crm/inbox?limit=5")).toBe("/api/crm/inbox?limit=5");
  });

  it.each([
    "/api/../auth/me",
    "/api/crm/%2e%2e/%2e%2e/auth/me",
    "/api/./crm",
    "/api/crm%2F..%2Fsecret",
    "//evil.example/api/x",
    "/apix/y",
    "https://evil.example/api/x",
    "/api\\..\\auth",
  ])("rejects %s", (path) => {
    expect(() => normalizeExtensionApiPath(path)).toThrow(/same-origin \/api\//);
  });
});

describe("plugin read API", () => {
  it("gives plugins a detached copy of a shared host GET", async () => {
    const body = { rows: [{ id: "1", name: "Ada" }] };
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify(body), { status: 200 })));

    const host = installPluginHost();
    const hostRead = api.getJson<typeof body>("/api/extension/rows");
    const pluginRead = host.api.getJson<typeof body>("/api/extension/rows");
    const [hostCopy, pluginCopy] = await Promise.all([hostRead, pluginRead]);

    expect(fetch).toHaveBeenCalledTimes(1);
    pluginCopy.rows[0].name = "mutated";
    expect(hostCopy.rows[0].name).toBe("Ada");
  });

  it("rejects traversal before any request is made", async () => {
    vi.stubGlobal("fetch", vi.fn());
    await expect(installPluginHost().api.getJson("/api/../auth/me")).rejects.toThrow();
    expect(fetch).not.toHaveBeenCalled();
  });
});

describe("plugin UI primitives", () => {
  it("hands widgets the form's reference picker on host contract v7", () => {
    const host = installPluginHost();
    expect(host.ui.RefSelect).toBe(RefSelect);
    expect(host.version).toBeGreaterThanOrEqual(7);
  });
});
