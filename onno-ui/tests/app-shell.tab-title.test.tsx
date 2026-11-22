import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AppConfigProvider } from "@/lib/app-config";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter, useLocation } from "react-router";
import { AppShell, clearModelCache, workspaceTabState } from "@/views/app-shell";
import { appModel, entity, mockModelFetch } from "./app-shell-fixtures";
import { openRoute } from "@/lib/navigation";
import { useCommandFeedback } from "@/lib/command-feedback";

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

// Opens products beside the current surface, in a new workspace tab.
function openProducts() {
  act(() => openRoute("onno://catalogs/products", { newWorkspaceTab: true }));
}

describe("AppShell tab titles", () => {
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
    clearModelCache();
    vi.unstubAllGlobals();
  });

  it("separates pane-active tabs from the one global command target", () => {
    expect(workspaceTabState(true, true)).toBe("command-target");
    expect(workspaceTabState(true, false)).toBe("pane-active");
    expect(workspaceTabState(false, true)).toBe("open");
    expect(workspaceTabState(false, false)).toBe("open");
  });

  it("routes the logo home intent to a dashboard-less app's real landing page", async () => {
    vi.restoreAllMocks();
    mockShell(entity("documents", "orders", { title: "Orders" }));
    render(
      <MemoryRouter initialEntries={["/tasks"]}>
        <AppShell />
      </MemoryRouter>
    );

    fireEvent.click(await screen.findByRole("button", { name: "Acme" }));

    // The home intent navigates the focused tab, like a link in a browser window; Back returns.
    const orders = await screen.findByTestId("content-/documents/orders");
    expect(orders.parentElement).toHaveStyle({ visibility: "visible" });
    expect(screen.queryByTestId("content-/tasks")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Back" })).toBeEnabled();
  });

  it("titles a list tab from the shell's localized title map, not the URL segment", async () => {
    render(
      <MemoryRouter initialEntries={["/catalogs/customers"]}>
        <AppShell />
      </MemoryRouter>
    );

    expect(await screen.findByTitle("Клиенты")).toBeInTheDocument();
    expect(screen.queryByTitle("Customers")).not.toBeInTheDocument();
  });

  it("uses the entity's icon from the app's customization", async () => {
    render(
      <AppConfigProvider app={{ entities: { customers: { icon: "users" } } }}>
        <MemoryRouter initialEntries={["/catalogs/customers"]}>
          <AppShell />
        </MemoryRouter>
      </AppConfigProvider>
    );

    // The sidebar renders the same icon; assert the tab's own glyph.
    const tab = await screen.findByTitle("Клиенты");
    expect(within(tab).getByTestId("icon-users")).toBeInTheDocument();
  });

  it("keeps the pane border neutral and emphasizes the active tab", async () => {
    render(
      <MemoryRouter initialEntries={["/catalogs/customers"]}>
        <AppShell />
      </MemoryRouter>
    );

    const tab = await screen.findByTitle("Клиенты");
    expect(tab.closest("section")).toHaveStyle({ borderColor: "#EBEBEB" });
    expect(tab).toHaveClass("font-medium");
    expect(within(tab).getByTestId("icon-book-marked").parentElement).toHaveClass("opacity-100");
  });

  it("keeps merely open tabs neutral without making the strip scroll vertically", async () => {
    render(
      <MemoryRouter initialEntries={["/catalogs/customers"]}>
        <AppShell />
      </MemoryRouter>
    );

    const customers = await screen.findByTitle("Клиенты");
    const strip = customers.closest("[data-tab-strip]");
    expect(strip).toHaveClass("h-11", "overflow-x-auto", "overflow-y-hidden");

    openProducts();

    expect(await screen.findByTitle("Products")).toHaveAttribute("data-active", "true");
    expect(customers).toHaveAttribute("data-active", "false");
    expect(customers).toHaveClass("text-muted-foreground");
    expect(customers).not.toHaveClass("bg-muted");
    expect(customers).not.toHaveStyle({ background: "hsl(var(--accent))" });
  });

  it("closes the focused tab with Escape even when a plain form field has focus", async () => {
    render(
      <MemoryRouter initialEntries={["/catalogs/customers"]}>
        <AppShell />
      </MemoryRouter>
    );

    const customers = await screen.findByTitle("Клиенты");
    openProducts();
    expect(await screen.findByTitle("Products")).toHaveAttribute("data-active", "true");

    const editor = screen.getByRole("textbox", { name: "Editor /catalogs/products" });
    await act(async () => {
      editor.focus();
      fireEvent.keyDown(editor, { key: "Escape" });
      await new Promise((resolve) => window.setTimeout(resolve, 25));
    });

    await waitFor(() => expect(screen.queryByTitle("Products")).not.toBeInTheDocument());
    expect(customers).toHaveAttribute("data-active", "true");
  });

  it("falls back to the humanized route token for an entity absent from the map", async () => {
    render(
      <MemoryRouter initialEntries={["/catalogs/products"]}>
        <AppShell />
      </MemoryRouter>
    );

    // "products" isn't in titles, so it humanizes to "Products".
    expect(await screen.findByTitle("Products")).toBeInTheDocument();
  });

  it("preserves new-form prefills without opening a second blank tab", async () => {
    render(<MemoryRouter initialEntries={["/catalogs/customers"]}><AppShell /></MemoryRouter>);
    await screen.findByTestId("content-/catalogs/customers");
    const path = "/documents/event_invoices/new?event=example-event";
    act(() => window.dispatchEvent(new CustomEvent("onno:open", { detail: "onno://" + path.slice(1) })));
    await screen.findByTestId("content-" + path);
    expect(screen.queryByTestId("content-/documents/event_invoices/new")).not.toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "New Event Invoices" })).toHaveLength(1);
  });

  it("templates the new-record tab around the localized entity title", async () => {
    render(
      <MemoryRouter initialEntries={["/catalogs/customers/new"]}>
        <AppShell />
      </MemoryRouter>
    );

    // tab.new = "New {entity}" with the localized entity name substituted.
    const tab = await screen.findByTitle("New Клиенты");
    expect(within(tab).getByTestId("icon-book-marked")).toBeInTheDocument();
  });

  it.each(["catalogs", "documents"])("uses the same detail pane for %s paths and onno URLs", async (kind) => {
    render(<MemoryRouter initialEntries={["/catalogs/customers"]}><AppShell /></MemoryRouter>);
    const list = await screen.findByTestId("content-/catalogs/customers");
    // Record placement depends on the shell's nav style, so wait for the model to land.
    await screen.findByTitle("Клиенты");
    const path = `/${kind}/example/r-1`;
    act(() => openRoute(path));
    const record = await screen.findByTestId("content-" + path);
    expect(list.parentElement).toHaveStyle({ visibility: "visible" });
    act(() => openRoute("onno://" + path.slice(1)));
    expect(screen.getAllByTestId("content-" + path)).toHaveLength(1);
    expect(record.parentElement).toHaveStyle({ visibility: "visible" });
  });

  it("treats /app as an ordinary page route", async () => {
    render(<MemoryRouter initialEntries={["/catalogs/customers"]}><AppShell /></MemoryRouter>);
    await screen.findByTestId("content-/catalogs/customers");
    act(() => openRoute("/app"));
    await screen.findByTestId("content-/app");
    expect(screen.queryByTestId("content-/catalogs/customers")).not.toBeInTheDocument();
  });

  it("opens application paths on mobile", async () => {
    Object.defineProperty(window, "innerWidth", { configurable: true, value: 500 });
    vi.stubGlobal("ResizeObserver", class { observe() {} unobserve() {} disconnect() {} });
    vi.stubGlobal("scrollTo", vi.fn());
    render(<MemoryRouter initialEntries={["/catalogs/customers"]}><AppShell /></MemoryRouter>);
    await screen.findByTestId("content-/catalogs/customers");
    act(() => openRoute("/documentation"));
    expect(await screen.findByTestId("content-/documentation")).toBeInTheDocument();
  });

  it("opens the record a command result navigates to without discarding another tab's draft", async () => {
    const route = "/documents/invoices/created-invoice";
    let apply!: ReturnType<typeof useCommandFeedback>["applyResult"];
    function Command() {
      apply = useCommandFeedback().applyResult;
      return null;
    }
    render(<MemoryRouter initialEntries={["/catalogs/customers"]}>
      <AppShell />
      <CurrentRoute />
      <Command />
    </MemoryRouter>);
    const editor = await screen.findByRole("textbox", { name: "Editor /catalogs/customers" });
    fireEvent.change(editor, { target: { value: "Unsaved draft" } });
    act(() => apply({ navigateTo: route }));
    await waitFor(() => expect(screen.getByTestId("current-route")).toHaveTextContent(route));
    expect(screen.getAllByTestId("content-" + route)).toHaveLength(1);
    expect(editor).toHaveValue("Unsaved draft");
  });

});
