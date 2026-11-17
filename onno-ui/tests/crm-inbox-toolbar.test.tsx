import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
vi.mock("@/lib/presence-store", () => ({ useViewersById: () => new Map() }));
vi.mock("@/components/maps/list-map-view", () => ({ ListMapView: () => null }));
import { AppConfigProvider } from "@/lib/app-config";
import type { ExtensionProps, UiExtension } from "@/lib/extension-types";
import { CrmInbox, type CrmInboxProps } from "../../onno-crm-starter/src/main/ui/CrmInbox";

class Observer { observe() {} unobserve() {} disconnect() {} }
const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
const folder = { key: "email", label: "Email chats", channels: ["EMAIL"], channelIds: ["EMAIL"], statuses: [], statusIds: [], priorities: [], priorityIds: [], unreadOnly: false, conversationIds: [] };
const rows = [{ id: "chat-anna", customer: "anna", customerDisplay: "Anna", channel: "EMAIL", lastMessageAt: "2026-11-10T09:00:00Z" }];

function mount(props: Partial<CrmInboxProps> = {}, extensions: UiExtension[] = [], folders = [folder]) {
  const config = { actions: ["reply", "sendReply"], folders };
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    const path = new URL(String(input), "http://localhost").pathname;
    if (path === "/api/crm/inbox-workspaces") return response([{ key: "support", label: "Support" }]);
    if (path === "/api/crm/inbox-workspaces/support") return response({ key: "support", config, rows, total: 1, hasMore: false, nextCursor: null, channelAccounts: [],
      list: { columns: [], filters: [], searchable: false, sort: { column: null, descending: false }, pageSize: 50 } });
    if (path === "/api/crm/workspace") return response({ workspace: { version: 0, config, fields: [] } });
    if (path === "/api/crm/chat-groups") return response({}, 404);
    if (path.endsWith("/activity")) return response({ entries: [], hasMore: false });
    if (path.startsWith("/api/crm/contacts/")) return response({ fields: {}, identities: [] });
    if (path.endsWith("/delivery")) return response({ connected: true, label: "Email", replyCapability: "AVAILABLE", maxTextLength: 8000 });
    return response([]);
  }));
  render(<AppConfigProvider app={{ extensions }}><CrmInbox workspace="support" toolbar={{ placement: "renderer" }} showAvatars={false} {...props} /></AppConfigProvider>);
}

beforeEach(() => {
  vi.stubGlobal("ResizeObserver", Observer);
  vi.stubGlobal("IntersectionObserver", Observer);
  vi.spyOn(HTMLElement.prototype, "clientWidth", "get").mockReturnValue(1200);
  Object.defineProperty(HTMLElement.prototype, "scrollIntoView", { configurable: true, value: vi.fn() });
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); delete (HTMLElement.prototype as Partial<HTMLElement>).scrollIntoView; });

it("offers Folders/Everything where the workspace has folders, and Everything drops the folding", async () => {
  mount({ folders: { email: { label: "Mail" } } });
  expect(await screen.findByRole("button", { name: /^Mail/ })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Folders" })).toHaveAttribute("aria-pressed", "true");
  fireEvent.click(screen.getByRole("button", { name: "Everything" }));
  await waitFor(() => expect(screen.queryByRole("button", { name: /^Mail/ })).not.toBeInTheDocument());
  expect(screen.getByRole("button", { name: /^Anna/ })).toBeInTheDocument();
});

it("has no switch without folders, or where the app turns it off", async () => {
  mount({}, [], []);
  await screen.findByRole("button", { name: /^Anna/ });
  expect(screen.queryByRole("button", { name: "Everything" })).toBeNull();
  cleanup();
  mount({ grouping: false });
  await screen.findByRole("button", { name: /^Email chats/ });
  expect(screen.queryByRole("button", { name: "Everything" })).toBeNull();
});

it("hands the app's toolbar contributions the inbox's view state", async () => {
  const Flatten = ({ context }: ExtensionProps) =>
    <button type="button" onClick={() => context.view?.set("grouping", "flat")}>Ours</button>;
  mount({ grouping: false }, [{ id: "app.flatten", slot: "crm.inbox.toolbar", component: Flatten }]);
  fireEvent.click(await screen.findByRole("button", { name: "Ours" }));
  await waitFor(() => expect(screen.queryByRole("button", { name: /^Email chats/ })).not.toBeInTheDocument());
});
