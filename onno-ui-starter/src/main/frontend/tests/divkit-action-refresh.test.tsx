import { useEffect } from "react";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router";

const { runAction, refreshed } = vi.hoisted(() => ({ runAction: vi.fn(), refreshed: vi.fn() }));
vi.mock("@/lib/api", () => ({ api: { runAction }, ApiError: class ApiError extends Error {} }));
vi.mock("@divkitframework/react", () => ({ DivKit: () => <div /> }));
vi.mock("@divkitframework/divkit/client-hydratable", () => ({
  createGlobalVariablesController: () => ({ setVariable: vi.fn() }),
  createVariable: () => ({ setValue: vi.fn() }),
}));
vi.mock("@/lib/presence-store", () => ({
  usePanePresence: vi.fn(), useRecordViewers: () => [], useEntityViewers: () => [],
  useViewersById: () => new Map(), startPresence: vi.fn(),
}));
vi.mock("@/providers/auth-provider", () => ({ useAuth: () => ({ logout: vi.fn() }) }));
vi.mock("@/providers/theme-provider", () => ({ useTheme: () => ({ theme: "light", setTheme: vi.fn() }) }));
vi.mock("@/providers/branding-provider", () => ({ useBranding: () => ({}) }));
vi.mock("@/hooks/use-ui-events", () => ({ useUiEvents: vi.fn() }));
vi.mock("@/lib/icon-bridge", () => ({
  ICON_CUSTOM_COMPONENTS: new Map(), setIconActivePath: vi.fn(), DynamicLucide: () => null,
}));
vi.mock("@/components/ui/toast", () => ({
  toast: { success: vi.fn(), error: vi.fn(), loading: vi.fn(), dismiss: vi.fn() },
}));
vi.mock("@/views/content-pane", () => ({
  ContentPane: ({ path, onAction }: { path: string; onAction: (action: { url: string; surfacePath: string }) => void }) => {
    useEffect(() => subscribeSurfaceRefresh(path, () => refreshed(path)), [path]);
    return <div>
      <input aria-label={`Draft ${path}`} defaultValue="Keep my draft" />
      <button onClick={() => onAction({ url: "onno://action/documents/orders/advance/o1", surfacePath: path })}>
        Run header action {path}
      </button>
    </div>;
  },
}));

import { subscribeSurfaceRefresh } from "@/lib/surface-refresh";
import { DivKitView } from "@/views/divkit-view";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  runAction.mockReset();
  refreshed.mockClear();
});

describe("DivKit header ActionResult.refresh (#447)", () => {
  it("refreshes the source tab after focus moves during a server action", async () => {
    Object.defineProperty(window, "innerWidth", { configurable: true, value: 1280 });
    vi.stubGlobal("matchMedia", vi.fn(() => ({ matches: false, addEventListener() {}, removeEventListener() {} })));
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({
      navStyle: "sidebar", home: "/", nav: {}, account: {}, titles: {}, icons: {},
    }), { headers: { "Content-Type": "application/json" } })));
    let finish!: (result: { refresh: boolean }) => void;
    runAction.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    const source = "/documents/orders/o1";
    const other = "/catalogs/customers";
    render(<MemoryRouter initialEntries={[source]}><DivKitView /></MemoryRouter>);
    await waitFor(() => expect(document.querySelector(`[data-tab="${source}"]`)).not.toBeNull());
    fireEvent.click(screen.getByRole("button", { name: `Run header action ${source}` }));
    expect(runAction).toHaveBeenCalledWith("documents", "orders", "advance", "o1");
    act(() => window.dispatchEvent(new CustomEvent("onno:action", { detail: "onno://catalogs/customers" })));
    const otherDraft = await screen.findByLabelText(`Draft ${other}`);
    fireEvent.change(otherDraft, { target: { value: "Typing in another tab" } });
    await act(async () => finish({ refresh: true }));
    await waitFor(() => expect(refreshed).toHaveBeenCalledExactlyOnceWith(source));
    expect(screen.getByLabelText(`Draft ${other}`)).toBe(otherDraft);
    expect(otherDraft).toHaveValue("Typing in another tab");
  });
});
