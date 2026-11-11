import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import type { ComponentType } from "react";
import { resolveWidget } from "@/lib/widget-registry";
vi.mock("@/lib/presence-store", () => ({ useViewersById: () => new Map() }));
vi.mock("@/components/list-map-view", () => ({ ListMapView: () => null }));
vi.mock("@onno/widget-sdk", async importOriginal => { await import("@/lib/plugin-host"); return importOriginal(); });
import "../../../../../onno-crm-starter/src/main/widgets/CrmInbox";
import { MAILBOX_ENDPOINTS } from "../../../../../onno-crm-starter/src/main/widgets/mailboxApi";

class Observer { observe() {} unobserve() {} disconnect() {} }
const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
const config = { folders: [], fields: [], showAvatar: false, listTitle: "customerDisplay", listSubtitle: "subject", listPreview: "lastMessagePreview",
  actions: [{ key: "reply", label: "Reply", visible: true }, { key: "sendReply", label: "Send reply", visible: true }] };
const list = { columns: [], filters: [], searchable: false, sort: { column: null, descending: false }, pageSize: 50, custom: { type: "crmInbox", defaultView: true } };
const anna = { id: "chat-anna", customer: "anna", customerDisplay: "Anna", channel: "EMAIL", lastMessageAt: "2026-11-10T09:00:00Z" };
const carla = { id: "chat-carla", customer: "carla", customerDisplay: "Carla", channel: "EMAIL", lastMessageAt: "2025-03-01T09:00:00Z" };
const accounts = [{ address: "office@studio.test", label: "Office", canModify: true }, { address: "info@studio.test", label: "Info", canModify: false }];
const firstPage = { nextPageToken: "page-2", messages: [
  { id: "m1", threadId: "t1", from: "Carla <carla@example.com>", subject: "Villa contract", snippet: "Signed copy attached", date: "1740819600000", archived: true, conversationId: null },
  { id: "m2", threadId: "t2", from: "Anna <anna@example.com>", subject: "Dates", snippet: "June works", date: "2026-11-01T10:00:00Z", archived: false, conversationId: "chat-anna" },
] };

function mount({ mailboxAccounts = accounts as unknown, search }: { mailboxAccounts?: unknown; search?: (url: URL) => Response } = {}) {
  const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), "http://localhost");
    const path = url.pathname;
    if (path === "/api/crm/inbox-workspaces") return response([{ key: "support" }]);
    if (path === "/api/crm/inbox-workspaces/support") return response({ key: "support", config, list, rows: [anna], total: 1, hasMore: false, nextCursor: null });
    if (path === "/api/crm/inbox-workspaces/support/conversation/chat-carla") return response({ key: "support", config, list, rows: [carla], total: 1, hasMore: false, nextCursor: null });
    if (path === "/api/crm/workspace") return response({ config });
    // null: an older backend without the mailbox search.
    if (path === `/api/crm${MAILBOX_ENDPOINTS.accounts}`) return mailboxAccounts === null ? response({ message: "Not found" }, 404) : response(mailboxAccounts);
    if (path === `/api/crm${MAILBOX_ENDPOINTS.search}`) return search ? search(url)
      : response(url.searchParams.get("pageToken") ? { messages: [{ ...firstPage.messages[0], id: "m3", subject: "Older thread" }], nextPageToken: null } : firstPage);
    if (path === `/api/crm${MAILBOX_ENDPOINTS.restore}` && init?.method === "POST") return response({ conversationId: "chat-carla", movedToInbox: true });
    const activity = path.match(/^\/api\/crm\/contacts\/(\w+)\/activity$/);
    if (activity) return response({ hasMore: false, entries: [{ id: `message:${activity[1]}`, conversationId: `chat-${activity[1]}`, kind: "CUSTOMER_MESSAGE", direction: "INBOUND",
      channel: "EMAIL", authorName: activity[1], body: `Hello from ${activity[1]}`, at: "2025-03-01T08:00:00Z", attachments: [] }] });
    if (path.startsWith("/api/crm/contacts/")) return response({ fields: {}, identities: [] });
    if (path.endsWith("/delivery")) return response({ connected: true, label: "Email", replyCapability: "AVAILABLE", maxTextLength: 8000 });
    if (path.endsWith("/read")) return response({ unreadCount: 0 });
    if (path === "/api/crm/chat-groups") return response({}, 404);
    return response([]);
  });
  vi.stubGlobal("fetch", fetcher);
  const Workspace = resolveWidget("crmInboxWorkspaces") as ComponentType<any>;
  render(<Workspace widget={{ extraConfig: { workspace: "support", toolbarPlacement: "renderer" } }} />);
  return { fetcher, calls: (path: string) => fetcher.mock.calls.filter(([input]) => new URL(String(input), "http://localhost").pathname === path) };
}
async function search(query: string) {
  fireEvent.click(await screen.findByRole("button", { name: "Find in mailbox" }));
  const dialog = await screen.findByRole("dialog", { name: "Find in mailbox" });
  fireEvent.change(within(dialog).getByRole("searchbox", { name: "Search the mailbox" }), { target: { value: query } });
  fireEvent.click(within(dialog).getByRole("button", { name: "Search" }));
  return dialog;
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

it.each([[[]], [null]])("offers no mailbox search without a connected mailbox (%j)", async payload => {
  const { calls } = mount({ mailboxAccounts: payload });
  await screen.findByText("Hello from anna");
  await waitFor(() => expect(calls(`/api/crm${MAILBOX_ENDPOINTS.accounts}`)).toHaveLength(1));
  expect(screen.queryByRole("button", { name: "Find in mailbox" })).not.toBeInTheDocument();
});

it("searches a mailbox with Gmail syntax, pages, and restores an archived message into its conversation", async () => {
  const { fetcher, calls } = mount();
  await screen.findByText("Hello from anna");
  const dialog = await search("from:carla subject:villa");
  const results = await within(dialog).findByRole("list", { name: "Messages found" });
  const first = within(results).getAllByRole("listitem")[0];
  expect(first).toHaveTextContent("Carla <carla@example.com>");
  expect(first).toHaveTextContent("Villa contract");
  expect(first).toHaveTextContent("Signed copy attached");
  expect(within(first).getByText("Archived")).toBeInTheDocument();
  const searched = new URL(String(calls(`/api/crm${MAILBOX_ENDPOINTS.search}`)[0][0]), "http://localhost");
  expect(Object.fromEntries(searched.searchParams)).toEqual({ account: "office@studio.test", q: "from:carla subject:villa" });
  expect(within(dialog).queryByRole("note")).not.toBeInTheDocument();

  fireEvent.click(within(dialog).getByRole("button", { name: "Load more" }));
  await within(dialog).findByText("Older thread");
  expect(new URL(String(calls(`/api/crm${MAILBOX_ENDPOINTS.search}`)[1][0]), "http://localhost").searchParams.get("pageToken")).toBe("page-2");
  expect(within(dialog).queryByRole("button", { name: "Load more" })).not.toBeInTheDocument();

  fireEvent.click(within(first).getByRole("button", { name: "Restore to inbox" }));
  await waitFor(() => expect(calls(`/api/crm${MAILBOX_ENDPOINTS.restore}`)).toHaveLength(1));
  const restore = fetcher.mock.calls.find(([input]) => String(input).endsWith(MAILBOX_ENDPOINTS.restore))!;
  expect(JSON.parse(String(restore[1]?.body))).toEqual({ account: "office@studio.test", messageId: "m1", toInbox: true });
  // Carla is not in the loaded feed: the inbox narrows to her conversations and opens this one.
  await screen.findByText("Hello from carla");
  await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  expect(calls("/api/crm/inbox-workspaces/support/conversation/chat-carla").length).toBeGreaterThan(0);
  fireEvent.click(screen.getByRole("button", { name: "All chats" }));
  await screen.findByText("Hello from anna");
  expect(screen.queryByRole("button", { name: "All chats" })).not.toBeInTheDocument();
});

it("opens a message the CRM already holds in place and explains read-only mailboxes", async () => {
  const { calls } = mount();
  await screen.findByText("Hello from anna");
  fireEvent.click(await screen.findByRole("button", { name: "Find in mailbox" }));
  const dialog = await screen.findByRole("dialog", { name: "Find in mailbox" });
  const mailbox = within(dialog).getByRole("combobox", { name: "Mailbox" });
  fireEvent.keyDown(mailbox, { key: "ArrowDown" });
  fireEvent.click(await screen.findByRole("option", { name: "Info · info@studio.test" }));
  expect(within(dialog).getByRole("note")).toHaveTextContent("stays archived in Gmail until the mailbox is reconnected");
  fireEvent.change(within(dialog).getByRole("searchbox", { name: "Search the mailbox" }), { target: { value: "dates" } });
  fireEvent.click(within(dialog).getByRole("button", { name: "Search" }));
  const results = await within(dialog).findByRole("list", { name: "Messages found" });
  fireEvent.click(within(within(results).getAllByRole("listitem")[1]).getByRole("button", { name: "Open" }));
  await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  expect(calls("/api/crm/inbox-workspaces/support/conversation/chat-anna")).toHaveLength(0);
  expect(screen.queryByRole("button", { name: "All chats" })).not.toBeInTheDocument();
});

it("shows empty results and recoverable errors", async () => {
  let fail = true;
  mount({ search: () => fail ? response({ message: "Gmail is unavailable" }, 502) : response({ messages: [], nextPageToken: null }) });
  await screen.findByText("Hello from anna");
  const dialog = await search("subject:nothing");
  expect(await within(dialog).findByRole("alert")).toHaveTextContent("Gmail is unavailable");
  fail = false;
  fireEvent.click(within(dialog).getByRole("button", { name: "Try again" }));
  expect(await within(dialog).findByText("No messages match this search.")).toBeInTheDocument();
});
