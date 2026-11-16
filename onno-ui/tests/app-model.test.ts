import { beforeEach, describe, expect, it, vi } from "vitest";
import { appModel, entity } from "./app-shell-fixtures";

// Navigation links and the builder are module state: load a fresh copy per test.
let m: typeof import("@/lib/app-model");
beforeEach(async () => {
  vi.resetModules();
  m = await import("@/lib/app-model");
});

const model = appModel([
  entity("catalogs", "customers", { title: "Customers", context: "Sales" }),
  entity("documents", "orders", { title: "Orders", context: "Sales" }),
  entity("catalogs", "products", { title: "Products" }),
  entity("registers", "stock", { title: "Stock" }),
]);

describe("defaultNavigation", () => {
  it("groups entities by context, falling back to a per-kind section and icon", () => {
    expect(m.defaultNavigation(model)).toEqual([
      { title: "Sales", items: [
        { label: "Customers", icon: "book-marked", path: "/catalogs/customers" },
        { label: "Orders", icon: "file-text", path: "/documents/orders" },
      ] },
      { title: "Catalogs", items: [{ label: "Products", icon: "book-marked", path: "/catalogs/products" }] },
      { title: "Reports", items: [{ label: "Stock", icon: "chart-column", path: "/registers/stock" }] },
    ]);
  });

  it("takes each entity's icon from the given picker", () => {
    const nav = m.defaultNavigation(model, [], (e) => (e.name === "customers" ? "users" : "box"));
    expect(nav[0].items.map((i) => i.icon)).toEqual(["users", "box"]);
  });

  it("puts the given page links first", () => {
    const nav = m.defaultNavigation(model, [{ label: "Ops", path: "/ops" }]);
    expect(nav[0]).toEqual({ title: null, items: [{ label: "Ops", path: "/ops" }] });
    expect(nav[1].items.map((i) => i.path)).toEqual(["/catalogs/customers", "/documents/orders"]);
  });
});

const brand = { brand: "Acme", logo: "", mark: "", markFramed: true };

describe("shellBrand", () => {
  it("picks the theme's logo and mark, falling back to the light ones", () => {
    const branding = { appName: "Acme", logoUrl: "/l.svg", logoUrlDark: "/l-dark.svg", markUrl: "/m.svg", markFramed: false };
    expect(m.shellBrand(branding, "dark")).toMatchObject({ brand: "Acme", logo: "/l-dark.svg", mark: "/m.svg", markFramed: false });
    expect(m.shellBrand(branding, "light")).toMatchObject({ logo: "/l.svg", mark: "/m.svg" });
  });

  it("falls back to the favicon, then the logo, for a missing mark, and to onno for a missing name", () => {
    expect(m.shellBrand({ logoUrl: "/l.svg", faviconUrl: "/f.svg" }, "light")).toMatchObject({ brand: "onno", mark: "/f.svg", markFramed: true });
    expect(m.shellBrand({ logoUrl: "/l.svg" }, "light").mark).toBe("/l.svg");
    expect(m.shellBrand({}, "dark")).toMatchObject({ logo: "", mark: "" });
  });
});

describe("shellFromModel", () => {
  it("derives branding, title/icon maps and home", () => {
    const shell = m.shellFromModel(model, brand, "sidebar", true);
    expect(shell).toMatchObject({ navStyle: "sidebar", home: "/", brand: "Acme" });
    expect(shell.titles).toMatchObject({ "/catalogs/customers": "Customers", "/registers/stock": "Stock" });
    expect(shell.icons).toMatchObject({ "/catalogs/customers": "book-marked" });
    expect(shell.accountInfo).toMatchObject({ displayName: "Admin" });
  });

  it("lands on the first nav item when there is no home route", () => {
    expect(m.shellFromModel(model, brand, "bottom_bar", false).home).toBe("/catalogs/customers");
    expect(m.shellFromModel(appModel(), brand, "sidebar", false).home).toBe("/");
  });

  it("renders the navigation it is given", () => {
    const shell = m.shellFromModel(model, brand, "sidebar", false, [{ title: "Mine", items: [{ label: "Home", path: "/home" }] }]);
    expect(shell.navigation.map((s) => s.title)).toEqual(["Mine"]);
    expect(shell.home).toBe("/home");
    expect(shell.titles?.["/catalogs/customers"]).toBeUndefined();
  });
});

describe("isEntitySurface", () => {
  it("accepts only catalog/document/register routes", () => {
    expect(m.isEntitySurface("/catalogs/customers")).toBe(true);
    expect(m.isEntitySurface("/documents/orders/o1?x=1")).toBe(true);
    expect(m.isEntitySurface("/registers/stock")).toBe(true);
    expect(m.isEntitySurface("/catalogs")).toBe(false);
    expect(m.isEntitySurface("/")).toBe(false);
    expect(m.isEntitySurface("/reports/sales")).toBe(false);
  });
});
