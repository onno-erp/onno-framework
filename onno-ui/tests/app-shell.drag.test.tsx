import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router";
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

vi.mock("@/lib/icons", () => ({
  ICON_CUSTOM_COMPONENTS: new Map(),
  setIconActivePath: vi.fn(),
  Icon: ({ name }: { name: string }) => <svg data-testid={`icon-${name}`} />,
}));

describe("AppShell tab dragging", () => {
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
    mockModelFetch(appModel());
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    clearModelCache();
  });

  it("clears the body drop overlay when the browser ends a drag without a tab dragend", async () => {
    render(
      <MemoryRouter initialEntries={["/catalogs/products"]}>
        <AppShell />
      </MemoryRouter>
    );

    const tab = await screen.findByTitle("Products");
    const dataTransfer = {
      effectAllowed: "",
      setData: vi.fn(),
      setDragImage: vi.fn(),
    };

    fireEvent.dragStart(tab, { dataTransfer, clientX: 120, clientY: 40 });
    expect(await screen.findByTestId("tab-drag-overlay")).toBeInTheDocument();
    expect(dataTransfer.setDragImage).toHaveBeenCalledTimes(1);
    const [dragImage] = dataTransfer.setDragImage.mock.calls[0];
    expect(dragImage).toHaveClass("onno-workspace-tab-drag-image");
    expect(document.body).toContainElement(dragImage);
    expect(tab).toHaveClass("onno-workspace-tab--dragging");

    fireEvent.dragOver(tab.parentElement!, { dataTransfer, clientX: 120 });
    expect(screen.getByTitle("Products")).not.toHaveClass("opacity-0");

    fireEvent.drop(window);

    await waitFor(() => {
      expect(screen.queryByTestId("tab-drag-overlay")).not.toBeInTheDocument();
    });
    expect(document.querySelector(".onno-workspace-tab-drag-image")).not.toBeInTheDocument();
    expect(screen.getByTestId("content-/catalogs/products")).toBeEnabled();
  });

});
