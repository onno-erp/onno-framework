import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import type { RouteProps } from "@/lib/routes";
import { AppConfigProvider, type AppDefinition } from "@/lib/app-config";
import type { ViewModel } from "@/lib/view-types";
import { ContentPane } from "@/views/content-pane";
import { setModel } from "@/lib/model-store";

// RouteView renders once the business model is in.
setModel({
  user: { username: "u", displayName: "U", avatarUrl: "", roles: [], permissions: [], unrestricted: false },
  roles: [],
  features: { comments: false, notifications: false },
  informationRegisters: [],
  constants: [],
  entities: [],
});

// The server's default view: data only. Its renderer is stubbed so the test sees "Default body".
const view: ViewModel = { register: { title: "Orders" } };
vi.mock("@/components/lists/register-surface", () => ({ RegisterSurface: () => <p>Default body</p> }));
let fetchMock: ReturnType<typeof vi.fn>;

function OrdersOverride({ path, params, query, view, renderDefault }: RouteProps) {
  return <div>
    <p data-testid="override">{`${path} ${params.id ?? ""} ${query.get("tab") ?? ""} ${view ? "view" : "no view"}`}</p>
    <div data-testid="wrapped">{renderDefault()}</div>
  </div>;
}

// The app's pages, handed to the shell the way defineApp does.
const app: AppDefinition = {
  pages: [
    { path: "/documents/cp_orders/:id", component: OrdersOverride },
    { path: "/cp-ops", component: ({ view }: RouteProps) => <p data-testid="ops">{view ? "view" : "client page"}</p> },
  ],
};

function pane(path: string, onNotFound = vi.fn()) {
  return <AppConfigProvider app={app}>
    <ContentPane path={path} onAction={vi.fn()} skeletonBg="white" onNotFound={onNotFound} />
  </AppConfigProvider>;
}

beforeEach(() => {
  fetchMock = vi.fn(async () => new Response(JSON.stringify(view), { headers: { "Content-Type": "application/json" } }));
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("ContentPane route overrides", () => {
  it("renders a registered override with the server's default view, params and query", async () => {
    render(pane("/documents/cp_orders/o1?tab=lines"));
    expect(await screen.findByTestId("override")).toHaveTextContent("/documents/cp_orders/o1 o1 lines view");
    expect(screen.getByTestId("wrapped")).toHaveTextContent("Default body");
    expect(fetchMock).toHaveBeenCalledOnce();
    expect(String(fetchMock.mock.calls[0][0])).toBe("/api/ui/view/documents/cp_orders/o1?tab=lines");
  });

  it("renders the default view for an entity route nobody overrode", async () => {
    render(pane("/documents/cp_orders"));
    expect(await screen.findByText("Default body")).toBeInTheDocument();
    expect(screen.queryByTestId("override")).not.toBeInTheDocument();
  });

  it("renders a client-only page without fetching a server view", () => {
    render(pane("/cp-ops"));
    expect(screen.getByTestId("ops")).toHaveTextContent("client page");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("reports an unregistered client route as not found without fetching", async () => {
    const onNotFound = vi.fn();
    render(pane("/cp-nowhere", onNotFound));
    await waitFor(() => expect(onNotFound).toHaveBeenCalledWith("/cp-nowhere"));
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
