import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { EntityListWidget, type ListDescriptor, type ListRendererProps } from "@/components/entity-list-widget";
import { conversationCount, ConversationList } from "../../../../../onno-crm-starter/src/main/widgets/ConversationList";

vi.mock("@onno/widget-sdk", async () => ({ ...(await import("@/components/ui/button")) }));
vi.mock("@/lib/presence-store", () => ({ useViewersById: () => new Map() }));
vi.mock("@/components/list-map-view", () => ({ ListMapView: () => null }));
class ResizeObserverMock { observe() {} unobserve() {} disconnect() {} }
const response = (body: unknown) => new Response(JSON.stringify(body));
const page = (ids: string[], nextCursor: string | null) => response({ rows: ids.map(id => ({ id, description: id })), nextCursor, hasMore: !!nextCursor, total: 6 });
const feed = "/api/crm/inbox-workspaces/support";
const list: ListDescriptor = {
  kind: "catalogs", name: "crm_conversations", title: "Inbox", columns: [], canWrite: false,
  searchable: false, sort: { column: null, descending: false }, newUrl: null, pageSize: 2,
  embedded: true, fill: true, feed, custom: { type: "pagination-test", defaultView: true },
  filters: [{ key: "subject", column: "subject", label: "Subject", type: "contains", options: [] }],
};
function Inbox(props: ListRendererProps) {
  return <ConversationList {...props}><span>{conversationCount(props.total, props.rows.length)}</span>{props.rows.map(row => <p key={String(row.id)}>{String(row.description)}</p>)}</ConversationList>;
}
function scroll() {
  const pane = screen.getByLabelText("Conversation list");
  Object.defineProperties(pane, { clientHeight: { configurable: true, value: 300 }, scrollHeight: { configurable: true, value: 1000 }, scrollTop: { configurable: true, value: 700 } });
  fireEvent.scroll(pane);
}
function deferred() {
  let resolve!: (response: Response) => void;
  const promise = new Promise<Response>(done => { resolve = done; });
  return { promise, resolve };
}
describe("CRM inner-pane keyset pagination", () => {
  beforeEach(() => {
    Object.defineProperty(HTMLElement.prototype, "scrollIntoView", { configurable: true, value: vi.fn() });
    vi.stubGlobal("ResizeObserver", ResizeObserverMock);
    vi.stubGlobal("requestAnimationFrame", (cb: FrameRequestCallback) => window.setTimeout(() => cb(0), 0));
    vi.stubGlobal("cancelAnimationFrame", (id: number) => window.clearTimeout(id));
  });
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); delete (HTMLElement.prototype as Partial<HTMLElement>).scrollIntoView; });
  it("loads three bounded pages from the scoped feed and deduplicates overlapping rows", async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(page(["one", "two"], "cursor-2"))
      .mockResolvedValueOnce(page(["two", "three", "four"], "cursor-3"))
      .mockResolvedValueOnce(page(["five", "six"], null));
    vi.stubGlobal("fetch", fetcher);
    render(<EntityListWidget list={list} renderer={Inbox} />);
    await screen.findByText("one"); scroll(); scroll();
    await screen.findByText("four");
    scroll();
    await screen.findByText("six");
    expect(screen.getAllByText("two")).toHaveLength(1);
    // Nothing is left to page, so the pane is idle and no control is offered.
    expect(screen.getAllByLabelText("Conversation list")[0]).toHaveAttribute("aria-busy", "false");
    scroll(); expect(fetcher).toHaveBeenCalledTimes(3);
    expect(fetcher.mock.calls.map(([url]) => new URL(url, "http://localhost").searchParams.get("cursor"))).toEqual([null, "cursor-2", "cursor-3"]);
    for (const [url, options] of fetcher.mock.calls) {
      expect(new URL(url, "http://localhost").pathname).toBe(feed);
      expect(new URL(url, "http://localhost").searchParams.get("limit")).toBe("2");
      expect(options.credentials).toBe("include");
    }
  });
  it("retains rows and the cursor after failure, stops automatic retries, and exposes an explicit retry", async () => {
    const pending = deferred();
    const fetcher = vi.fn().mockResolvedValueOnce(page(["one", "two"], "cursor-2"))
      .mockResolvedValueOnce(new Response(null, { status: 503 })).mockReturnValueOnce(pending.promise);
    vi.stubGlobal("fetch", fetcher);
    render(<EntityListWidget list={list} renderer={Inbox} />);
    await screen.findByText("one"); scroll();
    await screen.findByRole("alert"); scroll();
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(screen.getByText("one")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Retry loading conversations" }));
    expect(screen.getAllByLabelText("Conversation list")[0]).toHaveAttribute("aria-busy", "true");
    scroll(); expect(fetcher).toHaveBeenCalledTimes(3);
    await act(async () => pending.resolve(page(["three"], null)));
    await screen.findByText("three");
    expect(fetcher.mock.calls[1][0]).toBe(fetcher.mock.calls[2][0]);
  });
  it.each([200, 503])("discards a superseded filter page (%s) without clearing the newer request's busy guard", async status => {
    const old = deferred(), current = deferred();
    const fetcher = vi.fn().mockResolvedValueOnce(page(["old-one"], "old-cursor"))
      .mockReturnValueOnce(old.promise).mockResolvedValueOnce(page(["new-one"], "new-cursor"))
      .mockReturnValueOnce(current.promise);
    vi.stubGlobal("fetch", fetcher);
    render(<EntityListWidget list={list} renderer={Inbox} />);
    await screen.findByText("old-one"); scroll();
    fireEvent.click(screen.getByRole("button", { name: "Subject" }));
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "new" } });
    fireEvent.keyDown(screen.getByRole("textbox"), { key: "Enter" });
    await screen.findByText("new-one");
    // Idle and ready to page the newer query.
    expect(screen.getAllByLabelText("Conversation list")[0]).toHaveAttribute("aria-busy", "false");
    scroll();
    await act(async () => old.resolve(status === 200 ? page(["stale"], "stale-cursor") : new Response(null, { status })));
    expect(screen.queryByText("stale")).not.toBeInTheDocument();
    expect(screen.getAllByLabelText("Conversation list")[0]).toHaveAttribute("aria-busy", "true");
    scroll(); expect(fetcher).toHaveBeenCalledTimes(4);
    await act(async () => current.resolve(page(["new-two"], null)));
    await screen.findByText("new-two");
    const requested = new URL(fetcher.mock.calls[3][0], "http://localhost");
    expect(requested.searchParams.get("cursor")).toBe("new-cursor");
    expect(requested.searchParams.get("like")).toBe("subject,new");
  });
  it("renders a slow first window despite continuous refresh events and coalesces the follow-up", async () => {
    const first = deferred(), refresh = deferred();
    const fetcher = vi.fn().mockReturnValueOnce(first.promise).mockReturnValueOnce(refresh.promise);
    vi.stubGlobal("fetch", fetcher);
    const { rerender } = render(<EntityListWidget list={list} renderer={Inbox} refreshKey={0} />);
    for (let key = 1; key <= 5; key++) {
      rerender(<EntityListWidget list={list} renderer={Inbox} refreshKey={key} />);
      act(() => window.dispatchEvent(new CustomEvent("onno:dataevent", { detail: {
        type: "created", entityType: "catalog", entityName: "crm_conversations", id: `new-${key}`,
      } })));
    }
    expect(fetcher).toHaveBeenCalledTimes(1);
    await act(async () => first.resolve(page(["first-visible"], "next")));
    await screen.findByText("first-visible");
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    await act(async () => refresh.resolve(page(["refreshed"], null)));
    await screen.findByText("refreshed");
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
  it("allows a pending next page to render before a queued live refresh", async () => {
    const next = deferred(), refresh = deferred();
    const fetcher = vi.fn().mockResolvedValueOnce(page(["one", "two"], "next"))
      .mockReturnValueOnce(next.promise).mockReturnValueOnce(refresh.promise);
    vi.stubGlobal("fetch", fetcher);
    const { rerender } = render(<EntityListWidget list={list} renderer={Inbox} refreshKey={0} />);
    await screen.findByText("one"); scroll();
    rerender(<EntityListWidget list={list} renderer={Inbox} refreshKey={1} />);
    rerender(<EntityListWidget list={list} renderer={Inbox} refreshKey={2} />);
    expect(fetcher).toHaveBeenCalledTimes(2);
    await act(async () => next.resolve(page(["three", "four"], null)));
    await screen.findByText("four");
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(3));
    expect(new URL(fetcher.mock.calls[2][0], "http://localhost").searchParams.get("limit")).toBe("4");
    await act(async () => refresh.resolve(new Response(null, { status: 503 })));
    expect(screen.getByText("four")).toBeInTheDocument();
  });
  it("lets the user load more while a slow background refresh is running", async () => {
    const staleRefresh = deferred(), next = deferred(), refresh = deferred();
    const fetcher = vi.fn().mockResolvedValueOnce(page(["one", "two"], "next"))
      .mockReturnValueOnce(staleRefresh.promise).mockReturnValueOnce(next.promise).mockReturnValueOnce(refresh.promise);
    vi.stubGlobal("fetch", fetcher);
    const { rerender } = render(<EntityListWidget list={list} renderer={Inbox} refreshKey={0} />);
    await screen.findByText("one");
    rerender(<EntityListWidget list={list} renderer={Inbox} refreshKey={1} />);
    scroll(); expect(fetcher).toHaveBeenCalledTimes(3);
    await act(async () => staleRefresh.resolve(page(["stale"], null)));
    expect(screen.queryByText("stale")).not.toBeInTheDocument();
    await act(async () => next.resolve(page(["three", "four"], null)));
    await screen.findByText("four");
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(4));
    await act(async () => refresh.resolve(page(["one", "two", "three", "four"], null)));
    expect(screen.getByText("four")).toBeInTheDocument();
  });
  it("shows the server total across pagination and replaces it when filters change", async () => {
    const fetcher = vi.fn()
      .mockResolvedValueOnce(response({ rows: [{id:"one",description:"one"}], total:5046, hasMore:true, nextCursor:"next" }))
      .mockResolvedValueOnce(response({ rows: [{id:"two",description:"two"}], hasMore:false, nextCursor:null }))
      .mockResolvedValueOnce(response({ rows: [{id:"filtered",description:"filtered"}], total:3206, hasMore:false, nextCursor:null }));
    vi.stubGlobal("fetch",fetcher);
    render(<EntityListWidget list={list} renderer={Inbox} />);
    await screen.findByText("5046 chats");
    scroll(); await screen.findByText("two");
    expect(screen.getByText("5046 chats")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Subject" }));
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "filtered" } });
    fireEvent.keyDown(screen.getByRole("textbox"), { key: "Enter" });
    await screen.findByText("3206 chats");
    expect(screen.queryByText("5046 chats")).not.toBeInTheDocument();
  });
  it("distinguishes an unknown total from a real zero", () => {
    expect(conversationCount(undefined,50)).toBe("50 loaded chats");
    expect(conversationCount(null,50)).toBe("50 loaded chats");
    expect(conversationCount(0,0)).toBe("0 chats");
  });
  it("does not page hidden folder panes and works without pagination props", () => {
    const loadMore = vi.fn();
    const { rerender } = render(<ConversationList active={false} hasMore loadMore={loadMore}>Folder</ConversationList>);
    scroll(); expect(loadMore).not.toHaveBeenCalled();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
    rerender(<ConversationList active hasMore loadMore={loadMore}>Folder</ConversationList>);
    scroll(); expect(loadMore).toHaveBeenCalledTimes(1);
    rerender(<ConversationList>Static</ConversationList>);
    scroll(); expect(screen.getByText("Static")).toBeInTheDocument();
  });
});
