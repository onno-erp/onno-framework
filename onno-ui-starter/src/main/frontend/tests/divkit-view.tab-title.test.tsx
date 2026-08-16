import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { DivKitView } from "@/views/divkit-view";

vi.mock("@divkitframework/react", () => ({
  DivKit: ({
    id,
    onCustomAction,
  }: {
    id: string;
    onCustomAction?: (action: { url: string }) => void;
  }) => (
    <div data-testid={`divkit-${id}`}>
      {id.startsWith("nav:") ? (
        <button type="button" onClick={() => onCustomAction?.({ url: "onno://" })}>
          App logo
        </button>
      ) : null}
    </div>
  ),
}));

vi.mock("@divkitframework/divkit/client-hydratable", () => ({
  createGlobalVariablesController: () => ({ setVariable: vi.fn() }),
  createVariable: () => ({ setValue: vi.fn() }),
}));

// DivKitView drives presence (usePanePresence) — a no-op here so it doesn't issue a stray fetch that
// would consume the single mocked shell Response before the title-map load reads it.
vi.mock("@/lib/presence-store", () => ({
  usePanePresence: vi.fn(),
  useRecordViewers: () => [],
  useEntityViewers: () => [],
  useViewersById: () => new Map(),
  startPresence: vi.fn(),
}));

vi.mock("@/providers/auth-provider", () => ({
  useAuth: () => ({ logout: vi.fn().mockResolvedValue(undefined) }),
}));

vi.mock("@/providers/theme-provider", () => ({
  useTheme: () => ({ theme: "light", setTheme: vi.fn() }),
}));

vi.mock("@/providers/branding-provider", () => ({
  useBranding: () => ({}),
}));

vi.mock("@/hooks/use-ui-events", () => ({
  useUiEvents: vi.fn(),
}));

vi.mock("@/views/content-pane", () => ({
  ContentPane: ({ path }: { path: string }) => (
    <div data-testid={`content-${path}`}>Content for {path}</div>
  ),
}));

vi.mock("@/lib/icon-bridge", () => ({
  ICON_CUSTOM_COMPONENTS: new Map(),
  setIconActivePath: vi.fn(),
  DynamicLucide: ({ name }: { name: string }) => <svg data-testid={`icon-${name}`} />,
}));

vi.mock("@/components/ui/toast", () => ({
  toast: { success: vi.fn(), error: vi.fn(), loading: vi.fn(), dismiss: vi.fn() },
}));

// The shell payload carries a route-path → localized title map (built server-side from the same
// nav the sidebar renders). The workspace tab titles itself from it.
function mockShell(home = "/") {
  vi.spyOn(globalThis, "fetch").mockResolvedValue(
    new Response(
      JSON.stringify({
        navStyle: "sidebar",
        home,
        nav: { type: "nav" },
        account: { type: "account" },
        titles: { "/catalogs/customers": "Клиенты" },
        icons: { "/catalogs/customers": "users" },
      }),
      { headers: { "Content-Type": "application/json" }, status: 200 }
    )
  );
}

describe("DivKitView tab titles", () => {
  beforeEach(() => {
    Object.defineProperty(window, "innerWidth", { configurable: true, value: 1280 });
    Object.defineProperty(window, "matchMedia", {
      configurable: true,
      value: vi.fn().mockImplementation(() => ({
        matches: false,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
      })),
    });
    mockShell();
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("routes the logo home intent to a dashboard-less profile's real landing page", async () => {
    mockShell("/documents/orders");
    render(
      <MemoryRouter initialEntries={["/tasks"]}>
        <DivKitView />
      </MemoryRouter>
    );

    fireEvent.click(await screen.findByRole("button", { name: "App logo" }));

    const orders = await screen.findByTestId("content-/documents/orders");
    expect(orders.parentElement).toHaveStyle({ visibility: "visible" });
    expect(screen.getByTestId("content-/").parentElement).toHaveStyle({ visibility: "hidden" });
  });

  it("titles a list tab from the shell's localized title map, not the URL segment", async () => {
    render(
      <MemoryRouter initialEntries={["/catalogs/customers"]}>
        <DivKitView />
      </MemoryRouter>
    );

    expect(await screen.findByTitle("Клиенты")).toBeInTheDocument();
    expect(screen.queryByTitle("Customers")).not.toBeInTheDocument();
  });

  it("canonicalizes a logical entity name before resolving the route and title", async () => {
    render(
      <MemoryRouter initialEntries={["/catalogs/Customers"]}>
        <DivKitView />
      </MemoryRouter>
    );

    expect(await screen.findByTestId("content-/catalogs/customers")).toBeInTheDocument();
    expect(await screen.findByTitle("Клиенты")).toBeInTheDocument();
    expect(screen.queryByTestId("content-/catalogs/Customers")).not.toBeInTheDocument();
  });

  it("canonicalizes logical entity names dispatched by custom widgets", async () => {
    render(
      <MemoryRouter initialEntries={["/tasks"]}>
        <DivKitView />
      </MemoryRouter>
    );

    fireEvent(
      window,
      new CustomEvent("onno:action", { detail: "onno://catalogs/Customers" })
    );

    expect(await screen.findByTestId("content-/catalogs/customers")).toBeInTheDocument();
    expect(await screen.findByTitle("Клиенты")).toBeInTheDocument();
  });

  it("uses the authored navigation icon", async () => {
    render(
      <MemoryRouter initialEntries={["/catalogs/customers"]}>
        <DivKitView />
      </MemoryRouter>
    );

    expect(await screen.findByTestId("icon-users")).toBeInTheDocument();
  });

  it("keeps the pane border neutral and emphasizes the active tab", async () => {
    render(
      <MemoryRouter initialEntries={["/catalogs/customers"]}>
        <DivKitView />
      </MemoryRouter>
    );

    const tab = await screen.findByTitle("Клиенты");
    expect(tab.closest("section")).toHaveStyle({ borderColor: "#EBEBEB" });
    expect(tab).toHaveClass("font-medium");
    expect(screen.getByTestId("icon-users").parentElement).toHaveClass("opacity-100");
  });

  it("falls back to the humanized route token for an entity absent from the map", async () => {
    render(
      <MemoryRouter initialEntries={["/catalogs/products"]}>
        <DivKitView />
      </MemoryRouter>
    );

    // "products" isn't in titles, so it humanizes to "Products".
    expect(await screen.findByTitle("Products")).toBeInTheDocument();
  });

  it("templates the new-record tab around the localized entity title", async () => {
    render(
      <MemoryRouter initialEntries={["/catalogs/customers/new"]}>
        <DivKitView />
      </MemoryRouter>
    );

    // tab.new = "New {entity}" with the localized entity name substituted.
    expect(await screen.findByTitle("New Клиенты")).toBeInTheDocument();
    expect(screen.getByTestId("icon-users")).toBeInTheDocument();
  });

});
