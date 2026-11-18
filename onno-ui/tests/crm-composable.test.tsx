import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { AppConfigProvider } from "@/lib/app-config";
import type { UiExtension } from "@/lib/extension-types";
vi.mock("@/lib/presence-store", () => ({ useViewersById: () => new Map() }));
vi.mock("@/components/maps/list-map-view", () => ({ ListMapView: () => null }));
import { CrmInbox, type CrmInboxProps } from "../../onno-crm-starter/src/main/ui/CrmInbox";
import { composerDrafts } from "../../onno-crm-starter/src/main/ui/composerDrafts";
import {
  ConversationRow, CrmConversation, CrmContactPage, type ConversationRowProps, type CrmTimelineSource,
} from "../../onno-crm-starter/src/main/ui/index";

class Observer { observe() {} unobserve() {} disconnect() {} }
const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
const config = { folders: [], actions: ["reply", "sendReply", "note", "sendNote", "edit"] };
const rows = [
  { id: "chat-anna", customer: "anna", customerDisplay: "Anna", channel: "WEB_CHAT", subject: "Villa", inboxDisplay: "Website chat", lastMessageAt: "2026-11-10T09:00:00Z", unreadCount: 1 },
  { id: "chat-ben", customer: "ben", customerDisplay: "Ben", channel: "WEB_CHAT", subject: "Dates", inboxDisplay: "Website chat", lastMessageAt: "2026-11-09T09:00:00Z" },
];
const commands = [
  { id: "assign", title: "Assign to me", visible: true, enabled: true, group: "", inputs: false },
  { id: "status-open", title: "Open", visible: true, enabled: true, group: "Change status", inputs: false },
  { id: "status-closed", title: "Closed", visible: true, enabled: true, group: "Change status", inputs: false },
  { id: "reassign", title: "Reassign…", visible: true, enabled: true, group: "", inputs: true },
];

function mount(props: Partial<CrmInboxProps> = {}, extensions: UiExtension[] = [], element?: React.ReactElement) {
  const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = new URL(String(input), "http://localhost").pathname;
    if (path === "/api/crm/inbox-workspaces") return response([{ key: "support" }]);
    const feed = { key: "support", config, rows, total: 2, hasMore: false, nextCursor: null,
      list: { columns: [], filters: [], searchable: false, sort: { column: null, descending: false }, pageSize: 50 } };
    if (path === "/api/crm/inbox-workspaces/support") return response(feed);
    if (path.startsWith("/api/crm/inbox-workspaces/support/conversation/")) return response(feed);
    if (path === "/api/crm/workspace") return response({ workspace: { version: 0, config, fields: ["customer.description"] } });
    const activity = path.match(/^\/api\/crm\/contacts\/(\w+)\/activity$/);
    if (activity) return response({ hasMore: false, rows: [{ id: `message:${activity[1]}`, conversationId: `chat-${activity[1]}`, kind: "CUSTOMER_MESSAGE", direction: "INBOUND",
      channel: "WEB_CHAT", authorName: activity[1], body: `Hello from ${activity[1]}`, at: "2026-11-09T08:00:00Z", attachments: [] }] });
    const contact = path.match(/^\/api\/crm\/contacts\/(\w+)$/);
    if (contact) return response({ catalogName: "Customers", canWrite: true, fields: { id: contact[1], description: contact[1] === "anna" ? "Anna" : "Ben" }, identities: [],
      conversations: [{ id: `chat-${contact[1]}`, subject: "Villa", channel: "WEB_CHAT" }] });
    if (path.match(/\/conversations\/[\w-]+\/delivery$/)) return response({ connected: true, label: "Website chat", replyCapability: "AVAILABLE", maxTextLength: 4000 });
    if (path.endsWith("/actions") && !init?.method) return response(commands);
    if (path.includes("/actions/") && init?.method === "POST") return response({ message: "Done" });
    if (path.endsWith("/read")) return response({ unreadCount: 0 });
    if (path.endsWith("/messages") && init?.method === "POST") return response({ id: "sent" });
    if (path === "/api/crm/chat-groups") return response({}, 404);
    return response([]);
  });
  vi.stubGlobal("fetch", fetcher);
  render(<AppConfigProvider app={{ extensions }}>{element ?? <CrmInbox workspace="support" toolbar={{ placement: "renderer" }} showAvatars={false} {...props} />}</AppConfigProvider>);
  const posts = () => fetcher.mock.calls.filter(([, init]) => init?.method === "POST").map(([url, init]) => ({ url: String(url), body: JSON.parse(String(init?.body)) }));
  return { fetcher, posts };
}

beforeEach(() => {
  window.localStorage.clear();
  composerDrafts.reset();
  vi.stubGlobal("ResizeObserver", Observer);
  vi.stubGlobal("IntersectionObserver", Observer);
  vi.stubGlobal("requestAnimationFrame", (callback: FrameRequestCallback) => window.setTimeout(() => callback(0), 0));
  vi.stubGlobal("cancelAnimationFrame", (id: number) => window.clearTimeout(id));
  vi.spyOn(HTMLElement.prototype, "clientWidth", "get").mockReturnValue(1200);
  vi.spyOn(HTMLElement.prototype, "getClientRects").mockReturnValue([{}] as unknown as DOMRectList);
  Object.defineProperty(HTMLElement.prototype, "scrollIntoView", { configurable: true, value: vi.fn() });
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); composerDrafts.reset(); window.localStorage.clear(); delete (HTMLElement.prototype as Partial<HTMLElement>).scrollIntoView; });

it("draws the app's own conversation row, keeping selection through the built-in one", async () => {
  function VipRow(props: ConversationRowProps) {
    return <div data-testid={`vip-${props.row.id}`}>{props.row.customer === "anna" && <span>VIP</span>}<ConversationRow {...props} /></div>;
  }
  mount({ components: { ConversationRow: VipRow } });
  expect(await screen.findByTestId("vip-chat-anna")).toHaveTextContent("VIP");
  fireEvent.click(within(screen.getByTestId("vip-chat-ben")).getByRole("button", { name: /^Ben/ }));
  expect(await screen.findByText("Hello from ben")).toBeInTheDocument();
});

it("puts the app's own entries into the chat's timeline, in date order", async () => {
  const orders: CrmTimelineSource[] = [{ id: "orders", useEntries: ({ customerId }) => [
    { id: "o-1", at: "2026-11-09T07:00:00Z", type: "custom", render: () => <div data-testid="order-card">Order SO-1 for {customerId}</div> },
  ] }];
  mount({ timelineSources: orders });
  const card = await screen.findByTestId("order-card");
  expect(card).toHaveTextContent("Order SO-1 for anna");
  const message = await screen.findByText("Hello from anna");
  // The order (07:00) comes before the message (08:00).
  expect(card.compareDocumentPosition(message) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
});

it("lets the app wrap or replace any timeline entry", async () => {
  mount({ renderEntry: (entry, render) => entry.type === "message"
    ? <div data-testid="wrapped">{render()}<small>seen by team</small></div> : render() });
  const wrapped = await screen.findByTestId("wrapped");
  expect(wrapped).toHaveTextContent("Hello from anna");
  expect(wrapped).toHaveTextContent("seen by team");
});

it("gives each message an actions outlet with the message itself", async () => {
  const quoted = vi.fn();
  mount({}, [{ id: "test.quote", slot: "crm.chat.message.actions",
    component: ({ context }) => <button type="button" onClick={() => { quoted(context.record?.body); context.insertDraft?.(`> ${String(context.record?.body)}`); }}>Quote</button> }]);
  await screen.findByText("Hello from anna");
  await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Quote" })); });
  expect(quoted).toHaveBeenCalledWith("Hello from anna");
  expect(await screen.findByRole("textbox", { name: "Write a reply" })).toHaveValue("> Hello from anna");
});

it("lists the conversation's commands in the row menu, grouped, and runs them", async () => {
  const { posts } = mount({ rowMenu: { open: false } });
  const row = await screen.findByRole("button", { name: /^Anna/ });
  fireEvent.contextMenu(row.closest("[data-row-menu-host]")!);
  const menu = await screen.findByRole("menu");
  expect(within(menu).queryByRole("menuitem", { name: "Open conversation" })).not.toBeInTheDocument();
  expect(await within(menu).findByRole("menuitem", { name: "Assign to me" })).toBeInTheDocument();
  expect(within(menu).getByRole("menuitem", { name: /Change status/ })).toBeInTheDocument();
  // A command that asks for input first is left to the conversation's own actions.
  expect(within(menu).queryByRole("menuitem", { name: "Reassign…" })).not.toBeInTheDocument();
  await act(async () => { fireEvent.click(within(menu).getByRole("menuitem", { name: "Assign to me" })); });
  await waitFor(() => expect(posts().some(post => post.url.endsWith("/conversations/chat-anna/actions/assign"))).toBe(true));
});

it("replaces the contact panel and the empty chat", async () => {
  mount({ components: {
    ContactPanel: ({ customerId }) => <div data-testid="custom-contact">Profile of {customerId}</div>,
  } });
  fireEvent.click(await screen.findByRole("button", { name: "Show contact details" }));
  expect(await screen.findByTestId("custom-contact")).toHaveTextContent("Profile of anna");
});

it("shows one conversation anywhere, with its composer", async () => {
  const { posts } = mount({}, [], <div style={{ height: 600 }}><CrmConversation workspace="support" conversation="chat-ben" showAvatars={false} /></div>);
  expect(await screen.findByText("Hello from ben")).toBeInTheDocument();
  const box = await screen.findByRole("textbox", { name: "Write a reply" });
  fireEvent.change(box, { target: { value: "See you Friday" } });
  fireEvent.keyDown(box, { key: "Enter", ctrlKey: true });
  await waitFor(() => expect(posts().find(post => post.url.includes("/conversations/chat-ben/messages"))?.body).toMatchObject({ body: "See you Friday" }));
});

it("builds a contact page from the profile, the contact's conversations and the chat", async () => {
  mount({}, [{ id: "test.aside", slot: "crm.contact.page.aside", component: ({ context }) => <p>Orders of {String(context.recordId)}</p> }],
    <CrmContactPage contact="anna" workspace="support" showAvatars={false} />);
  expect(await screen.findByText("Orders of anna")).toBeInTheDocument();
  expect(await screen.findByRole("button", { name: /Villa/ })).toHaveAttribute("aria-current", "true");
  expect(await screen.findByText("Hello from anna")).toBeInTheDocument();
});
