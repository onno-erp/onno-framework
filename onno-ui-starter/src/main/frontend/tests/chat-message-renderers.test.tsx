import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, render, screen } from "@testing-library/react";
import { ChatMessageBody, registerChatMessageRenderer, type ChatMessage } from "../src/lib/chat-message-renderers";
const message: ChatMessage = { id: "one", kind: "CUSTOMER_MESSAGE", direction: "INBOUND", channel: "TELEGRAM", authorName: "Customer", body: "Hello", sentAt: "2026-09-07T10:00:00Z", deliveryStatus: "RECEIVED" };
const dispose: (() => void)[] = [];
afterEach(() => { cleanup(); dispose.splice(0).forEach(fn => fn()); vi.restoreAllMocks(); });
describe("custom chat message bodies", () => {
  it("falls back to text, reacts to late registration, and unregisters", () => {
    render(<ChatMessageBody message={message} fallback={<p>Hello</p>} />);
    expect(screen.getByText("Hello")).toBeTruthy();
    let remove!: () => void;
    act(() => { remove = registerChatMessageRenderer({ id: "app.telegram", matches: m => m.channel === "TELEGRAM", component: ({ message }) => <button>{message.authorName}</button> }); });
    expect(screen.getByRole("button").textContent).toBe("Customer");
    act(() => remove());
    expect(screen.getByText("Hello")).toBeTruthy();
  });
  it("uses priority and tolerates a failing predicate", () => {
    dispose.push(registerChatMessageRenderer({ id: "broken", priority: 100, matches: () => { throw Error("bad predicate"); }, component: () => null }));
    dispose.push(registerChatMessageRenderer({ id: "low", matches: () => true, component: () => <p>low</p> }));
    dispose.push(registerChatMessageRenderer({ id: "high", priority: 10, matches: () => true, component: () => <p>high</p> }));
    render(<ChatMessageBody message={message} fallback="Hello" />);
    expect(screen.getByText("high")).toBeTruthy();
  });
  it("isolates render failures and recovers when the renderer is replaced", () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    const old = registerChatMessageRenderer({ id: "app.card", matches: () => true, component: () => { throw Error("broken card"); } });
    dispose.push(old);
    render(<ChatMessageBody message={message} fallback="Hello" />);
    expect(screen.getByText("Hello")).toBeTruthy();
    act(() => { dispose.push(registerChatMessageRenderer({ id: "app.card", matches: () => true, component: () => <p>Recovered</p> })); old(); });
    expect(screen.getByText("Recovered")).toBeTruthy();
  });
});
