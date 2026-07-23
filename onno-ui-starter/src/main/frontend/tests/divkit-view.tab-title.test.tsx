import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { DivKitView } from "@/views/divkit-view";

vi.mock("@divkitframework/react", () => ({
  DivKit: ({ id }: { id: string }) => <div data-testid={`divkit-${id}`} />,
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

vi.mock("sonner", () => ({
  toast: { success: vi.fn(), error: vi.fn(), loading: vi.fn(), dismiss: vi.fn() },
}));

// The shell payload carries a route-path → localized title map (built server-side from the same
// nav the sidebar renders). The workspace tab titles itself from it.
function mockShell() {
  vi.spyOn(globalThis, "fetch").mockResolvedValue(
    new Response(
      JSON.stringify({
        navStyle: "sidebar",
        home: "/",
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

  it("titles a list tab from the shell's localized title map, not the URL segment", async () => {
    render(
      <MemoryRouter initialEntries={["/catalogs/customers"]}>
        <DivKitView />
      </MemoryRouter>
    );

    expect(await screen.findByTitle("Клиенты")).toBeInTheDocument();
    expect(screen.queryByTitle("Customers")).not.toBeInTheDocument();
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
