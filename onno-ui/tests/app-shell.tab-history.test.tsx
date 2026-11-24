import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter, useLocation } from "react-router";
import { AppShell, clearModelCache } from "@/views/app-shell";
import { appModel, entity, mockModelFetch } from "./app-shell-fixtures";
import { closeRoute, openRoute } from "@/lib/navigation";

function CurrentRoute() {
  const location = useLocation();
  return <output data-testid="current-route">{location.pathname + location.search}</output>;
}

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

// The brand comes from /api/branding (BrandingProvider), not from the model.
vi.mock("@/providers/branding-provider", () => ({
  useBranding: () => ({ appName: "Acme" }),
}));

vi.mock("@/hooks/use-ui-events", () => ({
  useUiEvents: vi.fn(),
}));

vi.mock("@/views/content-pane", () => ({
  ContentPane: ({ path }: { path: string }) => (
    <div data-testid={`content-${path}`}>
      Content for {path}
      <input aria-label={`Editor ${path}`} />
    </div>
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

// Navigation (and with it the tab title/icon maps) derives from the model: entity title + icon.
// "products" is deliberately absent so its tab falls back to the humanized route token.
function mockShell(...entities: ReturnType<typeof entity>[]) {
  mockModelFetch(appModel(entities.length ? entities : [
    entity("catalogs", "customers", { title: "Клиенты", context: "Sales" }),
  ]));
}

const route = () => screen.getByTestId("current-route").textContent;
const tabs = () => [...document.querySelectorAll<HTMLElement>("[data-tab]")].map((el) => el.dataset.tab);

function renderShell(initial = "/catalogs/customers") {
  // The workspace seeds its first tab from the browser URL, which a real browser shares with the router.
  window.history.replaceState(null, "", initial);
  render(
    <MemoryRouter initialEntries={[initial]}>
      <AppShell />
      <CurrentRoute />
    </MemoryRouter>
  );
}

describe("AppShell tab history", () => {
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
    window.history.replaceState(null, "", "/");
    vi.restoreAllMocks();
    clearModelCache();
    vi.unstubAllGlobals();
  });

  it("opens a route in the same tab and walks Back / Forward with the strip arrows", async () => {
    renderShell();
    await screen.findByTitle("Клиенты");
    const back = screen.getByRole("button", { name: "Back" });
    const forward = screen.getByRole("button", { name: "Forward" });
    expect(back).toBeDisabled();
    expect(forward).toBeDisabled();

    act(() => openRoute("/reports"));
    await screen.findByTestId("content-/reports");
    expect(tabs()).toEqual(["/reports"]);
    expect(screen.queryByTestId("content-/catalogs/customers")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Back" }));
    await screen.findByTestId("content-/catalogs/customers");
    expect(tabs()).toEqual(["/catalogs/customers"]);
    expect(route()).toBe("/catalogs/customers");
    expect(screen.getByRole("button", { name: "Forward" })).toBeEnabled();

    fireEvent.click(screen.getByRole("button", { name: "Forward" }));
    await screen.findByTestId("content-/reports");
    expect(route()).toBe("/reports");
    expect(screen.getByRole("button", { name: "Forward" })).toBeDisabled();
  });

  it("steps back with Alt+Left and the mouse's back button, but not inside a text field", async () => {
    renderShell();
    await screen.findByTitle("Клиенты");
    act(() => openRoute("/reports"));
    const editor = await screen.findByRole("textbox", { name: "Editor /reports" });

    fireEvent.keyDown(editor, { key: "ArrowLeft", altKey: true });
    expect(screen.getByTestId("content-/reports")).toBeInTheDocument();

    fireEvent.keyDown(window, { key: "ArrowLeft", altKey: true });
    await screen.findByTestId("content-/catalogs/customers");

    fireEvent.mouseUp(window, { button: 4 });
    await screen.findByTestId("content-/reports");
    fireEvent.mouseUp(window, { button: 3 });
    await screen.findByTestId("content-/catalogs/customers");
  });

  it("opens a new tab only when asked for one, and closes the other tabs from the tab menu", async () => {
    renderShell();
    await screen.findByTitle("Клиенты");
    act(() => openRoute("/reports", { newWorkspaceTab: true }));
    act(() => openRoute("/pipeline", { newWorkspaceTab: true }));
    await screen.findByTestId("content-/pipeline");
    expect(tabs()).toEqual(["/catalogs/customers", "/reports", "/pipeline"]);

    fireEvent.contextMenu(document.querySelector('[data-tab="/reports"]')!);
    fireEvent.click(await screen.findByText("Close other tabs"));
    await waitFor(() => expect(tabs()).toEqual(["/reports"]));
    expect(route()).toBe("/reports");
  });

  it("returns a closed form to the surface it was opened from", async () => {
    renderShell();
    await screen.findByTitle("Клиенты");
    act(() => openRoute("/reports"));
    await screen.findByTestId("content-/reports");
    act(() => openRoute("/reports/new-thing"));
    await screen.findByTestId("content-/reports/new-thing");
    act(() => closeRoute("/reports/new-thing"));
    await screen.findByTestId("content-/reports");
    expect(tabs()).toEqual(["/reports"]);
    expect(route()).toBe("/reports");
    expect(screen.getByRole("button", { name: "Forward" })).toBeDisabled();
  });

  it("offers every screen and New form from the strip's + and opens the pick as a new tab", async () => {
    vi.restoreAllMocks();
    mockShell(
      entity("catalogs", "customers", { title: "Клиенты", context: "Sales" }),
      entity("documents", "orders", { title: "Orders", context: "Sales" }),
      entity("catalogs", "suppliers", { title: "Suppliers", context: "Sales", canWrite: false }),
    );
    renderShell("/documents/orders");
    await screen.findByRole("button", { name: "Клиенты" });
    fireEvent.click(screen.getByRole("button", { name: "New tab" }));
    const options = () => screen.getAllByRole("option").map((option) => option.dataset.path);
    await screen.findByRole("listbox");
    // Screens in navigation order, then New forms: the shown entity first, read-only ones absent.
    expect(options()).toEqual([
      "/catalogs/customers", "/documents/orders", "/catalogs/suppliers",
      "/documents/orders/new", "/catalogs/customers/new",
    ]);

    fireEvent.change(screen.getByRole("combobox"), { target: { value: "кли" } });
    expect(options()).toEqual(["/catalogs/customers", "/catalogs/customers/new"]);
    fireEvent.keyDown(screen.getByRole("combobox"), { key: "Enter" });
    await screen.findByTestId("content-/catalogs/customers");
    expect(tabs()).toEqual(["/documents/orders", "/catalogs/customers"]);
    expect(route()).toBe("/catalogs/customers");
    expect(screen.queryByRole("listbox")).toBeNull();
  });

  it("means the same in a detail island: the pick opens as a new tab there", async () => {
    vi.restoreAllMocks();
    mockShell(
      entity("catalogs", "customers", { title: "Клиенты", context: "Sales" }),
      entity("documents", "orders", { title: "Orders", context: "Sales" }),
    );
    renderShell("/documents/orders");
    // Record placement depends on the shell's nav style, so wait for the model's navigation.
    await screen.findByRole("button", { name: "Клиенты" });
    act(() => openRoute("/catalogs/customers/c1"));
    await screen.findByTestId("content-/catalogs/customers/c1");
    const panes = document.querySelectorAll<HTMLElement>("[data-workspace-pane-id]");
    expect(panes).toHaveLength(2);

    fireEvent.click(within(panes[1]).getByRole("button", { name: "New tab" }));
    // The entity the island shows leads the New forms.
    const create = (await screen.findAllByRole("option")).map((o) => o.dataset.path).filter((p) => p?.endsWith("/new"));
    expect(create).toEqual(["/catalogs/customers/new", "/documents/orders/new"]);
    fireEvent.click(document.querySelector<HTMLElement>('[role="option"][data-path="/documents/orders/new"]')!);
    await screen.findByTestId("content-/documents/orders/new");
    const detailTabs = [...panes[1].querySelectorAll<HTMLElement>("[data-tab]")].map((el) => el.dataset.tab);
    expect(detailTabs).toEqual(["/catalogs/customers/c1", "/documents/orders/new"]);
    expect(route()).toBe("/documents/orders/new");
  });

  it("opens the pick in a new island with Shift+Enter, and switches to a screen that is already open", async () => {
    renderShell("/reports");
    await screen.findByTestId("content-/reports");
    await screen.findByRole("button", { name: "Клиенты" });
    fireEvent.click(screen.getByRole("button", { name: "New tab" }));
    fireEvent.change(await screen.findByRole("combobox"), { target: { value: "Клиенты" } });
    fireEvent.keyDown(screen.getByRole("combobox"), { key: "Enter", shiftKey: true });
    await screen.findByTestId("content-/catalogs/customers");
    const panes = document.querySelectorAll<HTMLElement>("[data-workspace-pane-id]");
    expect(panes).toHaveLength(2);
    expect(panes[1].querySelector("[data-tab]")?.getAttribute("data-tab")).toBe("/catalogs/customers");

    // Already open: the palette marks it and a pick activates that tab rather than duplicating it.
    fireEvent.click(within(panes[0]).getByRole("button", { name: "New tab" }));
    fireEvent.change(await screen.findByRole("combobox"), { target: { value: "Клиенты" } });
    expect(screen.getAllByRole("option")[0]).toHaveTextContent("Open");
    fireEvent.keyDown(screen.getByRole("combobox"), { key: "Enter" });
    await waitFor(() => expect(route()).toBe("/catalogs/customers"));
    expect(document.querySelectorAll("[data-tab]")).toHaveLength(2);
  });
});
