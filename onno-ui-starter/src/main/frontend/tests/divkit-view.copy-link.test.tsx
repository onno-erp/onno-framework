import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { toast } from "sonner";
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
}));

vi.mock("sonner", () => ({
  toast: { success: vi.fn(), error: vi.fn(), loading: vi.fn(), dismiss: vi.fn() },
}));

describe("DivKitView copy link", () => {
  let writeText: ReturnType<typeof vi.fn>;

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
    writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText } });
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
    toast.success.mockReset();
    toast.error.mockReset();
  });

  it("copies the tab's absolute deep link from its right-click menu", async () => {
    render(
      <MemoryRouter initialEntries={["/catalogs/products"]}>
        <DivKitView />
      </MemoryRouter>
    );

    const tab = await screen.findByTitle("Products");
    fireEvent.contextMenu(tab, { clientX: 100, clientY: 100 });

    const copy = await screen.findByRole("button", { name: "Copy link" });
    fireEvent.click(copy);

    await waitFor(() =>
      expect(writeText).toHaveBeenCalledWith(`${window.location.origin}/catalogs/products`)
    );
    await waitFor(() => expect(toast.success).toHaveBeenCalledWith("Link copied"));

    // The menu closes after the action.
    expect(screen.queryByRole("button", { name: "Copy link" })).not.toBeInTheDocument();
  });
});
