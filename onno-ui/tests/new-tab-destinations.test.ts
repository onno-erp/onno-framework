import { describe, expect, it } from "vitest";
import { filterDestinations, newTabDestinations } from "@/lib/new-tab-destinations";
import type { ShellNavSection } from "@/lib/view-types";
import { entity } from "./app-shell-fixtures";

const navigation: ShellNavSection[] = [
  {
    title: "Sales",
    items: [
      { label: "Orders", path: "/documents/orders" },
      { title: "Work", items: [{ label: "Tasks", path: "/tasks" }] },
    ],
  },
  { title: null, items: [{ label: "Reports", path: "/reports" }] },
];

const entities = [
  entity("catalogs", "customers", { title: "Customers" }),
  entity("documents", "orders", { title: "Orders" }),
  entity("catalogs", "books", { title: "Books" }),
  entity("catalogs", "suppliers", { title: "Suppliers", canWrite: false }),
  entity("registers", "stock", { title: "Stock" }),
];

const source = { navigation, entities, home: "/", homeLabel: "Dashboard" };

describe("newTabDestinations", () => {
  it("lists navigation screens with their place, then a New form per creatable entity", () => {
    expect(newTabDestinations(source)).toEqual([
      { path: "/", label: "Dashboard", icon: undefined, group: "screens" },
      { path: "/documents/orders", label: "Orders", hint: "Sales", icon: undefined, group: "screens" },
      { path: "/tasks", label: "Tasks", hint: "Sales / Work", icon: undefined, group: "screens" },
      { path: "/reports", label: "Reports", hint: undefined, icon: undefined, group: "screens" },
      { path: "/documents/orders/new", label: "Orders", icon: undefined, group: "create" },
      { path: "/catalogs/books/new", label: "Books", icon: undefined, group: "create" },
      { path: "/catalogs/customers/new", label: "Customers", icon: undefined, group: "create" },
    ]);
  });

  it("does not repeat a home the navigation already reaches, and leads with the shown entity", () => {
    const list = newTabDestinations({
      ...source,
      home: "/documents/orders",
      currentEntityRoute: "/catalogs/customers",
      titles: { "/catalogs/customers": "Клиенты" },
    });
    expect(list[0].path).toBe("/documents/orders");
    expect(list.filter((d) => d.group === "create").map((d) => d.label)).toEqual(["Клиенты", "Orders", "Books"]);
  });
});

describe("filterDestinations", () => {
  const all = newTabDestinations(source);

  it("matches every word against the label and its place, ignoring case and accents", () => {
    expect(filterDestinations(all, "  ").length).toBe(all.length);
    expect(filterDestinations(all, "sales ta").map((d) => d.path)).toEqual(["/tasks"]);
    expect(filterDestinations(all, "ORD").map((d) => d.path)).toEqual(["/documents/orders", "/documents/orders/new"]);
    expect(filterDestinations(all, "zzz")).toEqual([]);
  });

  it("ranks a label that starts with the query first, screens before New forms", () => {
    const list = [
      { path: "/a", label: "Stock report", group: "screens" as const },
      { path: "/b", label: "Reports", group: "screens" as const },
      { path: "/c/new", label: "Report", group: "create" as const },
    ];
    expect(filterDestinations(list, "rep").map((d) => d.path)).toEqual(["/b", "/a", "/c/new"]);
  });
});
