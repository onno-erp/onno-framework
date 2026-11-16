import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
vi.mock("@/lib/presence-store", () => ({ useViewersById: () => new Map() }));
vi.mock("@/components/maps/list-map-view", () => ({ ListMapView: () => null }));
import { CrmInbox } from "../../onno-crm-starter/src/main/ui/CrmInbox";

class Observer { observe() {} unobserve() {} disconnect() {} }
const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
const config = { folders: [], actions: ["reply", "sendReply"] };
const rows = [{ id: "chat-anna", customer: "anna", customerDisplay: "Anna", channel: "EMAIL", lastMessageAt: "2026-11-10T09:00:00Z" }];

function mount(groups: unknown = []) {
  let current = groups;
  const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = new URL(String(input), "http://localhost").pathname;
    if (path === "/api/crm/inbox-workspaces") return response([{ key: "support" }]);
    if (path === "/api/crm/inbox-workspaces/support") return response({ key: "support", config, rows, total: 1, hasMore: false, nextCursor: null,
      list: { columns: [], filters: [], searchable: false, sort: { column: null, descending: false }, pageSize: 50 } });
    if (path === "/api/crm/workspace") return response({ workspace: { version: 0, config, fields: [] } });
    if (path === "/api/crm/chat-groups") {
      if (current === null) return response({}, 404);
      if (init?.method === "POST") {
        const change = JSON.parse(String(init.body));
        current = [{ key: "group_1", label: change.label, customerIds: ["anna"] }];
      }
      return response(current);
    }
    if (path.endsWith("/activity")) return response({ rows: [], hasMore: false });
    if (path.startsWith("/api/crm/contacts/")) return response({ fields: {}, identities: [] });
    if (path.endsWith("/delivery")) return response({ connected: true, label: "Email", replyCapability: "AVAILABLE", maxTextLength: 8000 });
    return response([]);
  });
  vi.stubGlobal("fetch", fetcher);
  render(<CrmInbox workspace="support" toolbar={{ placement: "renderer" }} showAvatars={false} />);
  return fetcher;
}

beforeEach(() => {
  vi.stubGlobal("ResizeObserver", Observer);
  vi.stubGlobal("IntersectionObserver", Observer);
  vi.stubGlobal("requestAnimationFrame", (callback: FrameRequestCallback) => window.setTimeout(() => callback(0), 0));
  vi.stubGlobal("cancelAnimationFrame", (id: number) => window.clearTimeout(id));
  vi.spyOn(HTMLElement.prototype, "clientWidth", "get").mockReturnValue(1200);
  vi.spyOn(HTMLElement.prototype, "getClientRects").mockReturnValue([{}] as unknown as DOMRectList);
  Object.defineProperty(HTMLElement.prototype, "scrollIntoView", { configurable: true, value: vi.fn() });
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); delete (HTMLElement.prototype as Partial<HTMLElement>).scrollIntoView; });

it("creates a folder from the list header with the open chat in it", async () => {
  const fetcher = mount();
  fireEvent.click(await screen.findByRole("button", { name: "New group" }));
  const name = await screen.findByRole("textbox", { name: "Group name" });
  expect(screen.getByText("Anna moves into the new group.")).toBeInTheDocument();
  const create = screen.getByRole("button", { name: "Create group" });
  expect(create).toBeDisabled();
  fireEvent.change(name, { target: { value: "Signed" } });
  fireEvent.click(create);
  await waitFor(() => expect(fetcher.mock.calls.some(([url, init]) => String(url).startsWith("/api/crm/chat-groups") && init?.method === "POST"
    && JSON.parse(String(init.body)).label === "Signed" && JSON.parse(String(init.body)).conversationId === "chat-anna"
    && JSON.parse(String(init.body)).operation === "create")).toBe(true));
  expect(await screen.findByRole("button", { name: /^Signed/ })).toBeInTheDocument();
});

it("hides folder creation where chat groups are off", async () => {
  mount(null);
  await screen.findByRole("button", { name: /^Anna/ });
  expect(screen.queryByRole("button", { name: "New group" })).not.toBeInTheDocument();
});

it("opens a row's menu from its visible options button and moves focus into it", async () => {
  mount([{ key: "vip", label: "VIP", customerIds: [] }]);
  const row = await screen.findByRole("button", { name: /^Anna/ });
  const options = within(row.closest("[data-row-menu-host]") as HTMLElement).getByRole("button", { name: "Chat options" });
  expect(options).toHaveAttribute("aria-expanded", "false");
  await waitFor(() => expect(screen.getByRole("button", { name: "New group" })).toBeInTheDocument());
  fireEvent.click(options);
  const menu = await screen.findByRole("menu");
  expect(options).toHaveAttribute("aria-expanded", "true");
  expect(within(menu).getByRole("menuitem", { name: "Open conversation" })).toBeInTheDocument();
  expect(within(menu).getByRole("menuitem", { name: "Move to group" })).toBeInTheDocument();
  await waitFor(() => expect(within(menu).getByRole("menuitem", { name: "Open conversation" })).toHaveFocus());
  fireEvent.keyDown(menu, { key: "ArrowDown" });
  expect(within(menu).getByRole("menuitem", { name: "Move to group" })).toHaveFocus();
  fireEvent.keyDown(window, { key: "Escape" });
  await waitFor(() => expect(screen.queryByRole("menu")).not.toBeInTheDocument());
  await waitFor(() => expect(options).toHaveFocus());
});
