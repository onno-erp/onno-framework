import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { ChatMessageBody, ChatMessageRenderers, type ChatMessage, type ChatMessageRenderer } from "../../onno-crm-starter/src/main/ui/chatMessageRenderers";
const message: ChatMessage = { id: "one", kind: "CUSTOMER_MESSAGE", direction: "INBOUND", channel: "TELEGRAM", authorName: "Customer", body: "Hello", sentAt: "2026-09-07T10:00:00Z", deliveryStatus: "RECEIVED" };
const body = (renderers: ChatMessageRenderer[], fallback: React.ReactNode = "Hello") =>
  <ChatMessageRenderers renderers={renderers}><ChatMessageBody message={message} fallback={fallback} /></ChatMessageRenderers>;
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
describe("custom chat message bodies", () => {
  it("falls back to text without a matching renderer and follows the renderers it is handed", () => {
    const view = render(body([], <p>Hello</p>));
    expect(screen.getByText("Hello")).toBeTruthy();
    view.rerender(body([{ id: "app.telegram", matches: m => m.channel === "TELEGRAM", component: ({ message }) => <button>{message.authorName}</button> }]));
    expect(screen.getByRole("button").textContent).toBe("Customer");
    view.rerender(body([], <p>Hello</p>));
    expect(screen.getByText("Hello")).toBeTruthy();
  });
  it("uses priority and tolerates a failing predicate", () => {
    render(body([
      { id: "broken", priority: 100, matches: () => { throw Error("bad predicate"); }, component: () => null },
      { id: "low", matches: () => true, component: () => <p>low</p> },
      { id: "high", priority: 10, matches: () => true, component: () => <p>high</p> },
    ]));
    expect(screen.getByText("high")).toBeTruthy();
  });
  it("isolates render failures and recovers when the renderer is replaced", () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    const view = render(body([{ id: "app.card", matches: () => true, component: () => { throw Error("broken card"); } }]));
    expect(screen.getByText("Hello")).toBeTruthy();
    view.rerender(body([{ id: "app.fixed", matches: () => true, component: () => <p>Recovered</p> }]));
    expect(screen.getByText("Recovered")).toBeTruthy();
  });
});
