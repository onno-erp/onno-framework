import { afterEach, describe, expect, it, vi } from "vitest";

// base-path.ts reads window.__onnoBasePath once at module load, so each case sets the global and
// re-imports the module fresh (resetModules clears the cached evaluation).
async function load(value: string | undefined) {
  if (value === undefined) {
    delete (window as { __onnoBasePath?: string }).__onnoBasePath;
  } else {
    (window as { __onnoBasePath?: string }).__onnoBasePath = value;
  }
  vi.resetModules();
  return import("@/lib/base-path");
}

describe("base-path", () => {
  afterEach(() => {
    delete (window as { __onnoBasePath?: string }).__onnoBasePath;
  });

  it("defaults to the web root when the global is unset (e.g. tests)", async () => {
    const m = await load(undefined);
    expect(m.BASE_PATH).toBe("/");
    expect(m.withBasePath("/catalogs/x")).toBe("/catalogs/x");
    expect(m.stripBasePath("/catalogs/x")).toBe("/catalogs/x");
  });

  it("treats the unreplaced placeholder as the web root (Vite dev, no server templating)", async () => {
    const m = await load("__ONNO_BASE_PATH__");
    expect(m.BASE_PATH).toBe("/");
    expect(m.withBasePath("/catalogs/x")).toBe("/catalogs/x");
  });

  it("honors an injected prefix: prefixes links and strips it back off raw pathnames", async () => {
    const m = await load("/ui");
    expect(m.BASE_PATH).toBe("/ui");
    expect(m.withBasePath("/catalogs/x")).toBe("/ui/catalogs/x");
    expect(m.stripBasePath("/ui/catalogs/x")).toBe("/catalogs/x");
    // The mount root itself maps back to the router root.
    expect(m.stripBasePath("/ui")).toBe("/");
    // A path that doesn't carry the prefix is left untouched (defensive).
    expect(m.stripBasePath("/catalogs/x")).toBe("/catalogs/x");
  });

  it("normalizes a trailing slash and a missing leading slash", async () => {
    expect((await load("/ui/")).BASE_PATH).toBe("/ui");
    expect((await load("ui")).BASE_PATH).toBe("/ui");
    expect((await load("/app/ui/")).BASE_PATH).toBe("/app/ui");
  });
});
