import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import type { ComponentType } from "react";
import { resolveWidget } from "@/lib/widget-registry";
import { registerExtension } from "@/lib/ui-extensions";
vi.mock("@/lib/presence-store", () => ({ useViewersById: () => new Map() }));
vi.mock("@/components/list-map-view", () => ({ ListMapView: () => null }));
vi.mock("@onno/widget-sdk", async importOriginal => { await import("@/lib/plugin-host"); return importOriginal(); });
import "../../../../../onno-crm-starter/src/main/widgets/CrmInbox";
import { composerDrafts } from "../../../../../onno-crm-starter/src/main/widgets/composerDrafts";

class Observer { observe() {} unobserve() {} disconnect() {} }
const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
const config = { folders: [], fields: [], showAvatar: false, listTitle: "customerDisplay", listSubtitle: "subject", listPreview: "lastMessagePreview",
  actions: [{ key: "reply", label: "Reply", visible: true }, { key: "sendReply", label: "Send reply", visible: true }, { key: "note", label: "Note", visible: true }, { key: "sendNote", label: "Add note", visible: true }] };
const rows = [
  { id: "chat-anna", customer: "anna", customerDisplay: "Anna", channel: "EMAIL", subject: "Villa", inboxDisplay: "Gmail · office@studio.test", lastMessageAt: "2026-11-10T09:00:00Z" },
  { id: "chat-ben", customer: "ben", customerDisplay: "Ben", channel: "EMAIL", subject: "Dates", inboxDisplay: "Gmail · office@studio.test", lastMessageAt: "2026-11-09T09:00:00Z" },
];
const connected = { connected: true, label: "Email", replyCapability: "AVAILABLE", maxAttachments: 2, maxTextLength: 8000 };
const mailboxes = { senders: [{ id: "office", label: "office@studio.test" }, { id: "wedding", label: "wedding@studio.test" }], sender: "office" };

function mount(delivery: Record<string, object> = {}) {
  const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = new URL(String(input), "http://localhost").pathname;
    if (path === "/api/crm/inbox-workspaces") return response([{ key: "support" }]);
    if (path === "/api/crm/inbox-workspaces/support") return response({ key: "support", config, rows, total: 2, hasMore: false, nextCursor: null,
      list: { columns: [], filters: [], searchable: false, sort: { column: null, descending: false }, pageSize: 50, custom: { type: "crmInbox", defaultView: true } } });
    if (path === "/api/crm/workspace") return response({ config });
    const activity = path.match(/^\/api\/crm\/contacts\/(\w+)\/activity$/);
    if (activity) return response({ hasMore: false, entries: [{ id: `message:${activity[1]}`, conversationId: `chat-${activity[1]}`, kind: "CUSTOMER_MESSAGE", direction: "INBOUND",
      channel: "EMAIL", authorName: activity[1], body: `Hello from ${activity[1]}`, at: "2026-11-09T08:00:00Z", attachments: [] }] });
    if (path.startsWith("/api/crm/contacts/")) return response({ fields: {}, identities: [] });
    const conversation = path.match(/^\/api\/crm\/conversations\/([\w-]+)\/delivery$/);
    if (conversation) return response({ ...connected, recipient: `${conversation[1]}@example.com`, ...delivery[conversation[1]] });
    if (path.endsWith("/read")) return response({ unreadCount: 0 });
    if (path.endsWith("/messages") && init?.method === "POST") return response({ id: "sent" });
    if (path === "/api/crm/chat-groups") return response({}, 404);
    return response([]);
  });
  vi.stubGlobal("fetch", fetcher);
  const Workspace = resolveWidget("crmInboxWorkspaces") as ComponentType<any>;
  const view = render(<Workspace widget={{ extraConfig: { workspace: "support", toolbarPlacement: "renderer" } }} />);
  return { ...view, fetcher, sent: () => fetcher.mock.calls.filter(([url, init]) => String(url).includes("/messages") && init?.method === "POST").map(([url, init]) => ({ url: String(url), body: JSON.parse(String(init?.body)) })) };
}
const replyBox = () => screen.findByRole("textbox", { name: "Write a reply" });
async function openChat(name: string) {
  fireEvent.click(await screen.findByRole("button", { name: new RegExp(`^${name}`) }));
  await screen.findByText(`Hello from ${name.toLowerCase()}`);
  return replyBox();
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

it("keeps each conversation's draft when switching chats, remounting and reloading", async () => {
  mount();
  let body = await openChat("Anna");
  fireEvent.change(body, { target: { value: "Dear Anna, the villa is free" } });
  fireEvent.change(screen.getByRole("textbox", { name: "Subject" }), { target: { value: "Villa in June" } });

  body = await openChat("Ben");
  await waitFor(() => expect(body).toHaveValue(""));
  expect(screen.getByRole("textbox", { name: "Subject" })).toHaveValue("Dates");
  fireEvent.change(body, { target: { value: "Ben's reply" } });

  body = await openChat("Anna");
  await waitFor(() => expect(body).toHaveValue("Dear Anna, the villa is free"));
  expect(screen.getByRole("textbox", { name: "Subject" })).toHaveValue("Villa in June");

  // Unsent text holds the page; the draft itself is already in storage.
  const unload = new Event("beforeunload", { cancelable: true });
  window.dispatchEvent(unload);
  expect(unload.defaultPrevented).toBe(true);
  expect(Object.values(window.localStorage).join("")).toContain("Dear Anna, the villa is free");

  // A reload: nothing in memory, the draft comes back from storage.
  cleanup();
  composerDrafts.reset();
  mount();
  body = await openChat("Anna");
  await waitFor(() => expect(body).toHaveValue("Dear Anna, the villa is free"));
  expect(screen.getByRole("textbox", { name: "Subject" })).toHaveValue("Villa in June");
  body = await openChat("Ben");
  await waitFor(() => expect(body).toHaveValue("Ben's reply"));
});

it("clears a draft only after it is sent", async () => {
  const { sent } = mount();
  const body = await openChat("Anna");
  fireEvent.change(body, { target: { value: "Confirmed" } });
  fireEvent.keyDown(body, { key: "Enter", ctrlKey: true });
  await waitFor(() => expect(sent()).toHaveLength(1));
  expect(sent()[0].body).toMatchObject({ body: "Confirmed", recipient: "chat-anna@example.com" });
  expect(sent()[0].body).not.toHaveProperty("from");
  // The field is redrawn once sending finishes.
  await waitFor(() => expect(screen.getByRole("textbox", { name: "Write a reply" })).toHaveValue(""));
  composerDrafts.flush();
  expect(Object.values(window.localStorage).join("")).not.toContain("Confirmed");
});

it("sends from the mailbox chosen in From and keeps the conversation switch apart", async () => {
  const { sent } = mount({ "chat-anna": mailboxes });
  const body = await openChat("Anna");
  const from = await screen.findByRole("combobox", { name: "Send from" });
  expect(from).toHaveTextContent("office@studio.test");
  fireEvent.keyDown(from, { key: "ArrowDown" });
  fireEvent.click(await screen.findByRole("option", { name: "wedding@studio.test" }));
  expect(from).toHaveTextContent("wedding@studio.test");
  fireEvent.change(body, { target: { value: "From the wedding desk" } });
  fireEvent.keyDown(body, { key: "Enter", ctrlKey: true });
  await waitFor(() => expect(sent()).toHaveLength(1));
  expect(sent()[0].body).toMatchObject({ body: "From the wedding desk", from: "wedding" });

  // A conversation without a choice shows the channel identity, not a mailbox picker.
  await openChat("Ben");
  expect(screen.queryByRole("combobox", { name: "Send from" })).not.toBeInTheDocument();
  expect(document.querySelector("[data-sender-account]")).toHaveTextContent("Gmail · office@studio.test");
});

it("opens the composer in a large window that edits the same draft and sends it", async () => {
  const { sent } = mount({ "chat-anna": mailboxes });
  const inline = await openChat("Anna");
  fireEvent.change(inline, { target: { value: "Started inline" } });
  fireEvent.click(screen.getByRole("button", { name: "Open in a larger window" }));

  const dialog = await screen.findByRole("dialog", { name: "Reply to Anna" });
  const field = within(dialog).getByRole("textbox", { name: "Write a reply" });
  expect(field).toHaveValue("Started inline");
  expect(within(dialog).getByRole("combobox", { name: "Send from" })).toHaveTextContent("office@studio.test");
  fireEvent.change(field, { target: { value: "Started inline, finished in the window" } });
  const window_ = dialog.querySelector("[data-crm-composer-window]")!;
  fireEvent.click(within(dialog).getByRole("button", { name: "Fill the screen" }));
  expect(window_).toHaveAttribute("data-maximized", "true");
  expect(within(dialog).getByRole("button", { name: "Restore the window size" })).toBeInTheDocument();

  fireEvent.click(within(dialog).getByRole("button", { name: "Close" }));
  await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  const back = await replyBox();
  expect(back).toHaveValue("Started inline, finished in the window");
  await waitFor(() => expect(back).toHaveFocus());

  fireEvent.click(screen.getByRole("button", { name: "Open in a larger window" }));
  const reopened = await screen.findByRole("dialog", { name: "Reply to Anna" });
  fireEvent.click(within(reopened).getByRole("button", { name: "Send reply" }));
  await waitFor(() => expect(sent()).toHaveLength(1));
  expect(sent()[0].body).toMatchObject({ body: "Started inline, finished in the window", from: "office" });
  await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  expect(await replyBox()).toHaveValue("");
});

it("keeps insertDraft adding to the open conversation's draft", async () => {
  const unregister = registerExtension({ id: "test.insert-draft", slot: "crm.chat.composer",
    component: ({ context }) => <button type="button" onClick={() => context.insertDraft?.("Suggested reply")}>Suggest</button> });
  try {
    mount();
    const body = await openChat("Anna");
    fireEvent.change(body, { target: { value: "Hi Anna" } });
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Suggest" })); });
    expect(body).toHaveValue("Hi Anna\n\nSuggested reply");
    await openChat("Ben");
    expect(await openChat("Anna")).toHaveValue("Hi Anna\n\nSuggested reply");
  } finally { unregister(); }
});
