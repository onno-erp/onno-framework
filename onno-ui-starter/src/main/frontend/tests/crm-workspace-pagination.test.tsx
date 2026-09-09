import { act, waitFor, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import type { ComponentType } from "react";
import { resolveWidget } from "@/lib/widget-registry";
vi.mock("@/lib/presence-store", () => ({ useViewersById: () => new Map() }));
vi.mock("@/components/list-map-view", () => ({ ListMapView: () => null }));
vi.mock("@onno/widget-sdk", async importOriginal => { await import("@/lib/plugin-host"); return importOriginal(); });
class ResizeObserverMock { observe() {} unobserve() {} disconnect() {} }
import "../../../../../onno-crm-starter/src/main/widgets/CrmInbox";
afterEach(() => { cleanup(); vi.unstubAllGlobals(); delete (HTMLElement.prototype as Partial<HTMLElement>).scrollIntoView; });
it.each([false, true])("keeps selection through pagination and live read patches (broken feed: %s)", async (brokenFeed) => {
  Object.defineProperty(HTMLElement.prototype, "scrollIntoView", { configurable: true, value: vi.fn() });
  vi.stubGlobal("ResizeObserver", ResizeObserverMock);
  vi.stubGlobal("IntersectionObserver", ResizeObserverMock);
  vi.stubGlobal("requestAnimationFrame", (cb: FrameRequestCallback) => window.setTimeout(() => cb(0), 0));
  vi.stubGlobal("cancelAnimationFrame", (id: number) => window.clearTimeout(id));
  const config = { folders: [], fields: [], actions: [], showAvatar: false, listTitle: "customerDisplay", listSubtitle: "subject", listPreview: "lastMessagePreview" };
  const list = { columns: [], searchable: false, sort: { column: null, descending: false }, pageSize: 2, custom: { type: "crmInbox", defaultView: true } };
  const response = (body: unknown) => new Response(JSON.stringify(body));
  const fetcher = vi.fn(async (input: RequestInfo | URL) => {
    const url = new URL(String(input), "http://localhost");
    if (url.pathname === "/api/crm/inbox-workspaces") return response([{ key: "support" }]);
    if (url.pathname === "/api/crm/inbox-workspaces/support") {
      if (url.searchParams.has("ids")) {
        return response({ rows: [brokenFeed
          ? { id: "chat-1", customer: "customer-1", customerDisplay: "Person 1", channel: "test" }
          : { id: "chat-6", customer: "customer-6", customerDisplay: "Person 6", channel: "test", unreadCount: 0 }] });
      }
      const start = Number(url.searchParams.get("cursor") ?? 0);
      return response({ key: "support", config, list, total: 6, hasMore: start < 4, nextCursor: start < 4 ? String(start + 2) : null,
        rows: Array.from({ length: Number(url.searchParams.get("limit") ?? 2) }, (_, i) => start + i + 1).map(i => ({ id: `chat-${i}`, customer: `customer-${i}`, customerDisplay: `Person ${i}`, channel: "test" })) });
    }
    if (url.pathname === "/api/crm/workspace") return response({ config });
    if (url.pathname.endsWith("/activity")) return response({ entries: [], hasMore: false });
    if (url.pathname.includes("/contacts/")) return response({ fields: {}, identities: [] });
    if (url.pathname.endsWith("/delivery")) return response({ connected: false, replyCapability: "READ_ONLY" });
    return response([]);
  });
  vi.stubGlobal("fetch", fetcher);
  const Workspace = resolveWidget("crmInboxWorkspaces") as ComponentType<any>;
  render(<Workspace widget={{ extraConfig: { workspace: "support" } }} />);
  await screen.findByText("6 chats");
  await screen.findByText("Person 2");
  fireEvent.click(screen.getByRole("button", { name: "Load more conversations" }));
  await screen.findByText("Person 4");
  expect(screen.getByText("6 chats")).toBeInTheDocument();
  const pane = screen.getAllByLabelText("Conversation list")[0];
  Object.defineProperties(pane, { clientHeight: { value: 300 }, scrollHeight: { value: 1000 }, scrollTop: { value: 700 } });
  fireEvent.scroll(pane);
  await screen.findByText("Person 6");
  expect(screen.getByText("6 chats")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Load more conversations" })).not.toBeInTheDocument();
  expect(fetcher.mock.calls.filter(([input]) => String(input).includes("cursor="))).toHaveLength(2);
  fireEvent.click(screen.getByRole("button", { name: /Person 6/ }));
  expect(screen.getByRole("button", { name: /Person 6/ })).toHaveAttribute("aria-current", "true");
  await act(async () => {
    window.dispatchEvent(new CustomEvent("onno:dataevent", { detail: {
      type: "updated", entityType: "catalog", entityName: "crm_conversations", id: "chat-6",
    } }));
  });
  await waitFor(() => expect(fetcher.mock.calls.some(([input]) => String(input).includes("ids=chat-6"))).toBe(true));
  expect(screen.getByRole("button", { name: /Person 6/ })).toHaveAttribute("aria-current", "true");
  expect(screen.getByRole("button", { name: /Person 1/ })).not.toHaveAttribute("aria-current", "true");
});
