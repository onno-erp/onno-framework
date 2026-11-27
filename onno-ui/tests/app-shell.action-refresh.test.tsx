import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router";

const { runCommand, refreshed } = vi.hoisted(() => ({ runCommand: vi.fn(), refreshed: vi.fn() }));
vi.mock("@/lib/api", () => ({ api: { runCommand }, ApiError: class ApiError extends Error {} }));
vi.mock("@/lib/presence-store", () => ({
  usePanePresence: vi.fn(), useRecordViewers: () => [], useEntityViewers: () => [],
  useViewersById: () => new Map()
}));
vi.mock("@/providers/auth-provider", () => ({ useAuth: () => ({ logout: vi.fn() }) }));
vi.mock("@/providers/theme-provider", () => ({ useTheme: () => ({ theme: "light", setTheme: vi.fn() }) }));
vi.mock("@/providers/branding-provider", () => ({ useBranding: () => ({}) }));
vi.mock("@/hooks/use-ui-events", () => ({ useUiEvents: vi.fn() }));
vi.mock("@/lib/icons", () => ({
  ICON_CUSTOM_COMPONENTS: new Map(), setIconActivePath: vi.fn(), Icon: () => null,
}));
vi.mock("@/components/ui/toast", () => ({
  toast: { success: vi.fn(), error: vi.fn(), loading: vi.fn(), dismiss: vi.fn() },
}));
// Each pane keeps its own view of its route's entity, as lists and forms do.
vi.mock("@/views/content-pane", () => ({
  ContentPane: ({ path, onAction }: { path: string; onAction: (action: { url: string }) => void }) => {
    useEntityChanges(path.split("/").slice(1, 3).join("/"), (ids) => refreshed(path, ids));
    return <div>
      <input aria-label={`Draft ${path}`} defaultValue="Keep my draft" />
      <button onClick={() => onAction({ url: "onno://command/documents/orders/advance/o1" })}>
        Run header action {path}
      </button>
    </div>;
  },
}));

import { queryClient, useEntityChanges } from "@/lib/query";
import { AppShell, clearModelCache } from "@/views/app-shell";
import { appModel } from "./app-shell-fixtures";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  clearModelCache();
  queryClient.clear();
  runCommand.mockReset();
  refreshed.mockClear();
});

describe("shell header-action CommandResult.refresh (#447)", () => {
  it("refreshes the command's entity after focus moves during a server action", async () => {
    Object.defineProperty(window, "innerWidth", { configurable: true, value: 1280 });
    vi.stubGlobal("matchMedia", vi.fn(() => ({ matches: false, addEventListener() {}, removeEventListener() {} })));
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify(appModel()),
      { headers: { "Content-Type": "application/json" } })));
    let finish!: (result: { refresh: boolean }) => void;
    runCommand.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    const source = "/documents/orders/o1";
    const other = "/catalogs/customers";
    render(<MemoryRouter initialEntries={[source]}><AppShell /></MemoryRouter>);
    await waitFor(() => expect(document.querySelector(`[data-tab="${source}"]`)).not.toBeNull());
    fireEvent.click(screen.getByRole("button", { name: `Run header action ${source}` }));
    expect(runCommand).toHaveBeenCalledWith("documents", "orders", "advance", "o1");
    // A new tab: an ordinary open would replace the source surface in its own tab.
    act(() => window.dispatchEvent(new CustomEvent("onno:open-tab", { detail: "onno://catalogs/customers" })));
    const otherDraft = await screen.findByLabelText(`Draft ${other}`);
    fireEvent.change(otherDraft, { target: { value: "Typing in another tab" } });
    await act(async () => finish({ refresh: true }));
    await waitFor(() => expect(refreshed).toHaveBeenCalledExactlyOnceWith(source, ["o1"]));
    expect(screen.getByLabelText(`Draft ${other}`)).toBe(otherDraft);
    expect(otherDraft).toHaveValue("Typing in another tab");
  });
});
