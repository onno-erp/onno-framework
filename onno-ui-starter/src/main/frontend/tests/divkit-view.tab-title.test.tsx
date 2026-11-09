import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, useLocation } from "react-router";
import { DivKitView, workspaceTabState } from "@/views/divkit-view";
import { openRoute } from "@/lib/navigation";
import { PageActionsBar } from "@/components/page-actions-bar";
import { api } from "@/lib/api";

function CurrentRoute() {
  const location = useLocation();
  return <output data-testid="current-route">{location.pathname + location.search}</output>;
}

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
        <>
          <button type="button" onClick={() => onCustomAction?.({ url: "onno://" })}>
            App logo
          </button>
          <button type="button" onClick={() => onCustomAction?.({ url: "onno://catalogs/products" })}>
            Open products
          </button>
        </>
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
    <div data-testid={`content-${path}`}>
      Content for {path}
      <input aria-label={`Editor ${path}`} />
    </div>
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

  it("separates pane-active tabs from the one global command target", () => {
    expect(workspaceTabState(true, true)).toBe("command-target");
    expect(workspaceTabState(true, false)).toBe("pane-active");
    expect(workspaceTabState(false, true)).toBe("open");
    expect(workspaceTabState(false, false)).toBe("open");
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

  it("keeps merely open tabs neutral without making the strip scroll vertically", async () => {
    render(
      <MemoryRouter initialEntries={["/catalogs/customers"]}>
        <DivKitView />
      </MemoryRouter>
    );

    const customers = await screen.findByTitle("Клиенты");
    const strip = customers.closest("[data-tab-strip]");
    expect(strip).toHaveClass("h-11", "overflow-x-auto", "overflow-y-hidden");

    fireEvent.click(screen.getByRole("button", { name: "Open products" }));

    expect(await screen.findByTitle("Products")).toHaveAttribute("data-active", "true");
    expect(customers).toHaveAttribute("data-active", "false");
    expect(customers).toHaveClass("text-muted-foreground");
    expect(customers).not.toHaveClass("bg-muted");
    expect(customers).not.toHaveStyle({ background: "hsl(var(--accent))" });
  });

  it("closes the focused tab with Escape even when a plain form field has focus", async () => {
    render(
      <MemoryRouter initialEntries={["/catalogs/customers"]}>
        <DivKitView />
      </MemoryRouter>
    );

    const customers = await screen.findByTitle("Клиенты");
    fireEvent.click(screen.getByRole("button", { name: "Open products" }));
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
        <DivKitView />
      </MemoryRouter>
    );

    // "products" isn't in titles, so it humanizes to "Products".
    expect(await screen.findByTitle("Products")).toBeInTheDocument();
  });

  it("preserves new-form prefills without opening a second blank tab", async () => {
    render(<MemoryRouter initialEntries={["/catalogs/customers"]}><DivKitView /></MemoryRouter>);
    await screen.findByTestId("content-/catalogs/customers");
    const path = "/documents/event_invoices/new?event=example-event";
    act(() => window.dispatchEvent(new CustomEvent("onno:action", { detail: "onno://" + path.slice(1) })));
    await screen.findByTestId("content-" + path);
    expect(screen.queryByTestId("content-/documents/event_invoices/new")).not.toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "New Event Invoices", exact: true })).toHaveLength(1);
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

  it("opens a page action's application path and preserves the existing tab's draft", async () => {
    render(<MemoryRouter initialEntries={["/catalogs/customers"]}>
      <DivKitView />
      <CurrentRoute />
      <PageActionsBar route="/" buttons={[
        { key: "docs", label: "Documentation", server: false, url: "/documentation?topic=orders" },
      ]} />
    </MemoryRouter>);
    const editor = await screen.findByRole("textbox", { name: "Editor /catalogs/customers" });
    fireEvent.change(editor, { target: { value: "Unsaved draft" } });
    const button = screen.getByRole("button", { name: "Documentation" });
    fireEvent.click(button);
    await waitFor(() => expect(screen.getByTestId("current-route"))
      .toHaveTextContent("/documentation?topic=orders"));
    await screen.findByTestId("content-/documentation?topic=orders");
    expect(screen.getAllByRole("button", { name: "Documentation", exact: true })).toHaveLength(2);
    expect(editor).toHaveValue("Unsaved draft");
    fireEvent.click(button);
    expect(screen.getAllByTestId("content-/documentation?topic=orders")).toHaveLength(1);
  });

  it.each(["catalogs", "documents"])("uses the same detail pane for %s paths and onno URLs", async (kind) => {
    render(<MemoryRouter initialEntries={["/catalogs/customers"]}><DivKitView /></MemoryRouter>);
    const list = await screen.findByTestId("content-/catalogs/customers");
    const path = `/${kind}/example/r-1`;
    act(() => openRoute(path));
    const record = await screen.findByTestId("content-" + path);
    expect(list.parentElement).toHaveStyle({ visibility: "visible" });
    act(() => openRoute("onno://" + path.slice(1)));
    expect(screen.getAllByTestId("content-" + path)).toHaveLength(1);
    expect(record.parentElement).toHaveStyle({ visibility: "visible" });
  });

  it("treats /app as a page rather than the onno://app profile-switch command", async () => {
    render(<MemoryRouter initialEntries={["/catalogs/customers"]}><DivKitView /></MemoryRouter>);
    await screen.findByTestId("content-/catalogs/customers");
    act(() => openRoute("/app"));
    await screen.findByTestId("content-/app");
    expect(screen.getByTestId("content-/catalogs/customers")).toBeInTheDocument();
  });

  it("opens application paths on mobile", async () => {
    Object.defineProperty(window, "innerWidth", { configurable: true, value: 500 });
    render(<MemoryRouter initialEntries={["/catalogs/customers"]}><DivKitView /></MemoryRouter>);
    await screen.findByTestId("content-/catalogs/customers");
    act(() => openRoute("/documentation"));
    expect(await screen.findByTestId("content-/documentation")).toBeInTheDocument();
  });

  it("opens the record returned by a server page action without discarding another tab's draft", async () => {
    const route = "/documents/invoices/created-invoice";
    const run = vi.spyOn(api, "runPageAction").mockResolvedValue({ navigateTo: route });
    render(<MemoryRouter initialEntries={["/catalogs/customers"]}>
      <DivKitView />
      <CurrentRoute />
      <PageActionsBar route="/billing" buttons={[
        { key: "createInvoice", label: "Create invoice", server: true },
      ]} />
    </MemoryRouter>);
    const editor = await screen.findByRole("textbox", { name: "Editor /catalogs/customers" });
    fireEvent.change(editor, { target: { value: "Unsaved draft" } });
    fireEvent.click(screen.getByRole("button", { name: "Create invoice" }));
    await waitFor(() => expect(screen.getByTestId("current-route")).toHaveTextContent(route));
    expect(run).toHaveBeenCalledOnce();
    expect(screen.getAllByTestId("content-" + route)).toHaveLength(1);
    expect(editor).toHaveValue("Unsaved draft");
  });

});
