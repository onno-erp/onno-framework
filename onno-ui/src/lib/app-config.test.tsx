import { describe, expect, it } from "vitest";
import { act, render, screen } from "@testing-library/react";
import { AppConfigProvider, buildNavigation, useAppConfig, useCommandPresentations, useEntityCustomizations, type AppDefinition, type PageDefinition } from "./app-config";
import type { AppModel } from "./app-model";
import { matchRoute } from "./routes";
import { setModel } from "./model-store";

const entity = (logicalName: string, kind: "catalogs" | "documents" = "documents") => ({
  kind, name: logicalName.toLowerCase(), logicalName, title: logicalName, context: "",
  route: `/${kind}/${logicalName.toLowerCase()}`, canWrite: true,
});

const model = (
  roles: string[],
  entities = [entity("Orders"), entity("Employees", "catalogs")],
  access: { permissions?: string[]; unrestricted?: boolean } = {},
): AppModel => ({
  user: {
    username: "u", displayName: "U", avatarUrl: "", roles,
    permissions: access.permissions ?? [], unrestricted: access.unrestricted ?? false,
  },
  roles: [],
  features: { comments: false, notifications: false },
  entities,
  informationRegisters: [],
  constants: [],
});

const Page = () => null;
const allowAll = (page: PageDefinition) => !page.roles?.length;

describe("buildNavigation", () => {
  it("resolves entity names and page paths, dropping what the user cannot use", () => {
    const app: AppDefinition = {
      // An entity's icon comes from its customization (else the default for its kind).
      entities: { Orders: { icon: "file" } },
      pages: [
        { path: "/ops", component: Page, label: "Ops", icon: "activity" },
        { path: "/settings", component: Page, label: "Settings", roles: ["HR"] },
      ],
      navigation: [
        { title: "Sales", items: ["Orders", "Invoices", "/ops"] },
        { title: "People", items: [{ entity: "Employees", roles: ["HR"] }] },
        { title: "System", items: ["/settings"] },
      ],
    };
    expect(buildNavigation(app, model(["MANAGER"]), allowAll)).toEqual([
      { title: "Sales", icon: undefined, items: [
        { label: "Orders", icon: "file", path: "/documents/orders" },
        { label: "Ops", icon: "activity", path: "/ops" },
      ] },
    ]);
    expect(buildNavigation(app, model(["HR"]), () => true).map((s) => s.title)).toEqual(["Sales", "People", "System"]);
  });

  it("lets no role through implicitly and compares role names normalized", () => {
    const app: AppDefinition = { navigation: [{ title: "People", items: [{ entity: "Employees", roles: ["manager"] }] }] };
    // A role literally called ADMIN is an ordinary role: it does not pass another role's gate.
    expect(buildNavigation(app, model(["ADMIN"]), allowAll)).toEqual([]);
    expect(buildNavigation(app, model(["MANAGER"]), allowAll).map((s) => s.title)).toEqual(["People"]);
  });

  it("gates entries on a permission, which every user holds in an app without grants", () => {
    const app: AppDefinition = {
      pages: [{ path: "/settings", component: Page, label: "Settings" }],
      navigation: [{ title: "System", permission: "settings.edit", items: ["/settings"] }],
    };
    expect(buildNavigation(app, model([]), allowAll)).toEqual([]);
    expect(buildNavigation(app, model([], undefined, { permissions: ["settings.edit"] }), allowAll)
      .map((s) => s.title)).toEqual(["System"]);
    expect(buildNavigation(app, model([], undefined, { unrestricted: true }), allowAll)
      .map((s) => s.title)).toEqual(["System"]);
  });

  it("puts labelled pages first when the app declares no navigation", () => {
    const nav = buildNavigation({ pages: [{ path: "/ops", component: Page, label: "Ops" }] }, model([]), allowAll);
    expect(nav[0]).toEqual({ title: null, items: [{ label: "Ops", icon: undefined, path: "/ops" }] });
  });
});

function Probe({ path, entityKey }: { path: string; entityKey?: [string, string] }) {
  const config = useAppConfig();
  const customizations = useEntityCustomizations(entityKey?.[0] ?? "", entityKey?.[1] ?? "");
  const matched = matchRoute(config.routes, path);
  return (
    <p data-testid="probe">
      {matched ? `route clientOnly=${matched.options.clientOnly}` : "no route"}
      {` customizations=${customizations.length}`}
      {` allowed=${(config.app.pages ?? []).filter(config.pageAllowed).map((p) => p.path).join(",")}`}
    </p>
  );
}

describe("AppConfigProvider", () => {
  it("hands pages, routes and entity customizations to the tree below, by logical name", () => {
    act(() => setModel(model(["MANAGER"])));
    render(
      <AppConfigProvider app={{
        entities: { Orders: { list: { columns: ["number"] } } },
        pages: [{ path: "/ops", component: Page }, { path: "/documents/orders", component: Page }],
      }}>
        <Probe path="/documents/orders" entityKey={["documents", "orders"]} />
      </AppConfigProvider>,
    );
    expect(screen.getByTestId("probe").textContent).toContain("route clientOnly=false");
    expect(screen.getByTestId("probe").textContent).toContain("customizations=1");
  });

  it("shows a page guarded by an async condition once it answers yes", async () => {
    act(() => setModel(model([])));
    render(
      <AppConfigProvider app={{ pages: [{ path: "/inbox", component: Page, when: () => Promise.resolve(true) }] }}>
        <Probe path="/inbox" />
      </AppConfigProvider>,
    );
    expect(screen.getByTestId("probe").textContent).toContain("allowed=");
    expect(await screen.findByText(/allowed=\/inbox/)).toBeTruthy();
  });
});

describe("entity page registration", () => {
  it("uses model routes and matches saved, new, and duplicate record screens", () => {
    act(() => setModel(model([])));
    function Routes() {
      const { routes } = useAppConfig();
      return <>{["/documents/orders/123", "/documents/orders/new", "/documents/orders/123/duplicate"]
        .map((path) => <span key={path}>{matchRoute(routes, path) ? `matched ${path}` : "missing"}</span>)}</>;
    }
    render(<AppConfigProvider app={{ pages: [{ record: "Orders", component: Page }] }}><Routes /></AppConfigProvider>);
    expect(screen.getByText("matched /documents/orders/123")).toBeTruthy();
    expect(screen.getByText("matched /documents/orders/new")).toBeTruthy();
    expect(screen.getByText("matched /documents/orders/123/duplicate")).toBeTruthy();
  });

  it("hides an entity link when its replacement list page is gated", () => {
    const app: AppDefinition = { pages: [{ list: "Orders", component: Page, roles: ["HR"] }],
      navigation: [{ items: ["Orders"] }] };
    expect(buildNavigation(app, model([]), allowAll)).toEqual([]);
  });
});

function CommandsProbe() {
  const presentations = useCommandPresentations("catalogs", "CrmConversations");
  return <p data-testid="commands">{JSON.stringify(presentations)}</p>;
}

describe("useCommandPresentations", () => {
  it("merges an entity's command customizations, a bare placement included, by the name a surface passes", () => {
    act(() => setModel(model(["MANAGER"])));
    render(
      <AppConfigProvider app={{ entities: {
        CrmConversations: { commands: { "assign-to-me": { icon: "user-check" }, "state-closed": "hidden" } },
        "catalogs/CrmConversations": { commands: { "assign-to-me": { title: "Take it" } } },
      } }}>
        <CommandsProbe />
      </AppConfigProvider>,
    );
    expect(JSON.parse(screen.getByTestId("commands").textContent ?? "{}")).toEqual({
      "assign-to-me": { icon: "user-check", title: "Take it" },
      "state-closed": { placement: "hidden" },
    });
  });
});
