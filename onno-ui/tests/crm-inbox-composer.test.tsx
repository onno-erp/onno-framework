import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { AppConfigProvider } from "@/lib/app-config";
import type { UiExtension } from "@/lib/extension-types";
vi.mock("@/lib/presence-store", () => ({ useViewersById: () => new Map() }));
vi.mock("@/components/maps/list-map-view", () => ({ ListMapView: () => null }));
import { CrmInbox } from "../../onno-crm-starter/src/main/ui/CrmInbox";
import { composerDrafts } from "../../onno-crm-starter/src/main/ui/composerDrafts";
import { composerWindows } from "../../onno-crm-starter/src/main/ui/composerWindows";

class Observer { observe() {} unobserve() {} disconnect() {} }
const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
let extensions: UiExtension[] = [];
const config = { folders: [], actions: ["reply", "sendReply", "note", "sendNote"] };
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
      list: { columns: [], filters: [], searchable: false, sort: { column: null, descending: false }, pageSize: 50 } });
    if (path === "/api/crm/workspace") return response({ workspace: { version: 0, config, fields: [] } });
    const activity = path.match(/^\/api\/crm\/contacts\/(\w+)\/activity$/);
    if (activity) return response({ hasMore: false, rows: [{ id: `message:${activity[1]}`, conversationId: `chat-${activity[1]}`, kind: "CUSTOMER_MESSAGE", direction: "INBOUND",
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
  const view = render(<AppConfigProvider app={{ extensions }}><CrmInbox workspace="support" toolbar={{ placement: "renderer" }} showAvatars={false} actionLabels={{ note: "Note" }} /></AppConfigProvider>);
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
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); composerDrafts.reset(); composerWindows.reset(); window.localStorage.clear(); delete (HTMLElement.prototype as Partial<HTMLElement>).scrollIntoView; });

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

it("opens the composer in a non-modal window that edits the same draft and sends it", async () => {
  const { sent } = mount({ "chat-anna": mailboxes });
  const inline = await openChat("Anna");
  fireEvent.change(inline, { target: { value: "Started inline" } });
  fireEvent.click(screen.getByRole("button", { name: "Open in a larger window" }));

  const dialog = await screen.findByRole("dialog", { name: "Reply to Anna" });
  const field = within(dialog).getByRole("textbox", { name: "Write a reply" });
  expect(field).toHaveValue("Started inline");
  expect(within(dialog).getByRole("combobox", { name: "Send from" })).toHaveTextContent("office@studio.test");
  fireEvent.change(field, { target: { value: "Started inline, finished in the window" } });
  // A window, not a modal: it opens docked in the corner and the inbox behind it stays usable.
  expect(dialog).toHaveAttribute("aria-modal", "false");
  expect(dialog).toHaveAttribute("data-window-state", "docked");
  expect(screen.getByTestId("crm-inbox-workspace")).not.toHaveAttribute("aria-hidden");
  fireEvent.click(within(dialog).getByRole("button", { name: "Maximize" }));
  expect(dialog).toHaveAttribute("data-window-state", "maximized");
  fireEvent.click(within(dialog).getByRole("button", { name: "Restore" }));
  expect(dialog).toHaveAttribute("data-window-state", "docked");
  // Minimised, the draft stays mounted and comes back intact.
  fireEvent.click(within(dialog).getByRole("button", { name: "Minimize" }));
  expect(dialog).toHaveAttribute("data-window-state", "minimized");
  fireEvent.click(within(dialog).getByRole("button", { name: "Restore" }));
  expect(within(dialog).getByRole("textbox", { name: "Write a reply" })).toHaveValue("Started inline, finished in the window");

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

it("keeps a composer window on its own conversation, minimised, while another chat is open", async () => {
  const { sent } = mount();
  await openChat("Anna");
  fireEvent.click(screen.getByRole("button", { name: "Open in a larger window" }));
  const window = await screen.findByRole("dialog", { name: "Reply to Anna" });
  fireEvent.change(within(window).getByRole("textbox", { name: "Write a reply" }), { target: { value: "Dear Anna, the villa is free" } });
  fireEvent.click(within(window).getByRole("button", { name: "Minimize" }));

  // Another chat: its own empty composer inline, Anna's window still there, minimised, with her draft.
  const ben = await openChat("Ben");
  expect(ben).toHaveValue("");
  const parked = await screen.findByRole("dialog", { name: "Reply to Anna" });
  expect(parked).toHaveAttribute("data-window-state", "minimized");
  fireEvent.change(ben, { target: { value: "Ben's own reply" } });
  fireEvent.click(within(parked).getByRole("button", { name: "Restore" }));
  expect(within(parked).getByRole("textbox", { name: "Write a reply" })).toHaveValue("Dear Anna, the villa is free");

  // Sent from the window, the reply goes to Anna's conversation and the window closes; Ben's draft stays.
  fireEvent.click(within(parked).getByRole("button", { name: "Send reply" }));
  await waitFor(() => expect(sent()).toHaveLength(1));
  expect(sent()[0]).toMatchObject({ url: expect.stringContaining("chat-anna"), body: { body: "Dear Anna, the villa is free" } });
  await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  expect(await replyBox()).toHaveValue("Ben's own reply");

  // Back on Anna: her composer is inline again and empty.
  expect(await openChat("Anna")).toHaveValue("");
});

it("hands the window back to the chat's own composer when that chat is chosen again", async () => {
  mount();
  await openChat("Anna");
  fireEvent.click(screen.getByRole("button", { name: "Open in a larger window" }));
  const window = await screen.findByRole("dialog", { name: "Reply to Anna" });
  fireEvent.change(within(window).getByRole("textbox", { name: "Write a reply" }), { target: { value: "Still writing" } });
  fireEvent.click(within(window).getByRole("button", { name: "Minimize" }));
  await openChat("Ben");
  fireEvent.click(await screen.findByRole("button", { name: /^Anna/ }));
  await screen.findByText("Hello from anna");
  // One window, the same one, still minimised; the inline composer says the draft is in the window.
  const dialogs = await screen.findAllByRole("dialog", { name: "Reply to Anna" });
  expect(dialogs).toHaveLength(1);
  expect(dialogs[0]).toHaveAttribute("data-window-state", "minimized");
  expect(screen.queryByRole("textbox", { name: "Write a reply" })).not.toBeInTheDocument();
  fireEvent.click(within(dialogs[0]).getByRole("button", { name: "Close" }));
  expect(await replyBox()).toHaveValue("Still writing");
});

it("keeps insertDraft adding to the open conversation's draft", async () => {
  extensions = [{ id: "test.insert-draft", slot: "crm.chat.composer",
    component: ({ context }) => <button type="button" onClick={() => context.insertDraft?.("Suggested reply")}>Suggest</button> }];
  try {
    mount();
    const body = await openChat("Anna");
    fireEvent.change(body, { target: { value: "Hi Anna" } });
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Suggest" })); });
    expect(body).toHaveValue("Hi Anna\n\nSuggested reply");
    await openChat("Ben");
    expect(await openChat("Anna")).toHaveValue("Hi Anna\n\nSuggested reply");
  } finally { extensions = []; }
});
