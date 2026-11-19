import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { toast } from "@/components/ui/toast";
import { AppShell, clearModelCache } from "@/views/app-shell";
import { appModel, mockModelFetch } from "./app-shell-fixtures";

// AppShell drives presence (usePanePresence) — a no-op here so it issues no stray fetch.
vi.mock("@/lib/presence-store", () => ({
  usePanePresence: vi.fn(),
  useRecordViewers: () => [],
  useEntityViewers: () => [],
  useViewersById: () => new Map(),
}));

vi.mock("@/providers/auth-provider", () => ({
  useAuth: () => ({ logout: vi.fn().mockResolvedValue(undefined) }),
}));

vi.mock("@/providers/theme-provider", () => ({
  useTheme: () => ({ theme: "light", resolvedTheme: "light", setTheme: vi.fn() }),
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

vi.mock("@/lib/icons", () => ({
  ICON_CUSTOM_COMPONENTS: new Map(),
  setIconActivePath: vi.fn(),
  Icon: ({ name }: { name: string }) => <svg data-testid={`icon-${name}`} />,
}));

vi.mock("@/components/ui/toast", () => ({
  toast: { success: vi.fn(), error: vi.fn(), loading: vi.fn(), dismiss: vi.fn() },
}));

describe("AppShell copy link", () => {
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
    mockModelFetch(appModel());
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    clearModelCache();
    vi.mocked(toast.success).mockReset();
    vi.mocked(toast.error).mockReset();
  });

  it("copies the tab's absolute deep link from its right-click menu", async () => {
    render(
      <MemoryRouter initialEntries={["/catalogs/products"]}>
        <AppShell />
      </MemoryRouter>
    );

    const tab = await screen.findByTitle("Products");
    fireEvent.contextMenu(tab, { clientX: 100, clientY: 100 });

    // Menu entries are proper menu items (role="menuitem") since the shared context-menu component.
    const copy = await screen.findByRole("menuitem", { name: "Copy link" });
    fireEvent.click(copy);

    await waitFor(() =>
      expect(writeText).toHaveBeenCalledWith(`${window.location.origin}/catalogs/products`)
    );
    await waitFor(() => expect(toast.success).toHaveBeenCalledWith("Link copied"));

    // The menu closes after the action.
    expect(screen.queryByRole("menuitem", { name: "Copy link" })).not.toBeInTheDocument();
  });
});
