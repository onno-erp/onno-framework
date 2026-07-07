import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
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
    <button type="button" data-testid={`content-${path}`}>
      Content for {path}
    </button>
  ),
}));

vi.mock("@/lib/icon-bridge", () => ({
  ICON_CUSTOM_COMPONENTS: new Map(),
  setIconActivePath: vi.fn(),
}));

describe("DivKitView tab dragging", () => {
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
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(
        JSON.stringify({
          navStyle: "sidebar",
          home: "/",
          nav: { type: "nav" },
          account: { type: "account" },
        }),
        { headers: { "Content-Type": "application/json" }, status: 200 }
      )
    );
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("clears the body drop overlay when the browser ends a drag without a tab dragend", async () => {
    render(
      <MemoryRouter initialEntries={["/catalogs/products"]}>
        <DivKitView />
      </MemoryRouter>
    );

    const tab = await screen.findByTitle("Products");
    const dataTransfer = {
      effectAllowed: "",
      setData: vi.fn(),
    };

    fireEvent.dragStart(tab, { dataTransfer });
    expect(await screen.findByTestId("tab-drag-overlay")).toBeInTheDocument();

    fireEvent.drop(window);

    await waitFor(() => {
      expect(screen.queryByTestId("tab-drag-overlay")).not.toBeInTheDocument();
    });
    expect(screen.getByTestId("content-/catalogs/products")).toBeEnabled();
  });
});
