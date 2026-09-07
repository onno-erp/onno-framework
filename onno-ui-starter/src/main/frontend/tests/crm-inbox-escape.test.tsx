import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { useRef, useState } from "react";
import { useInboxEscape } from "../../../../../onno-crm-starter/src/main/widgets/useInboxEscape";

beforeEach(() => { vi.spyOn(Element.prototype, "getClientRects").mockReturnValue([{}] as unknown as DOMRectList); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
function Inbox({ hidden = false, folder = false, initialChat = true }: { hidden?: boolean; folder?: boolean; initialChat?: boolean }) {
  const root = useRef<HTMLDivElement>(null);
  const [chat, setChat] = useState(initialChat);
  const [folderOpen, setFolderOpen] = useState(folder);
  useInboxEscape(root, chat ? () => setChat(false) : null, folderOpen ? () => setFolderOpen(false) : null);
  return <section data-workspace-pane-id="inbox"><button>Inbox tab</button><div ref={root} style={{ visibility: hidden ? "hidden" : "visible" }}>
    <textarea aria-label="Reply" />{chat && <span>Open chat</span>}{folderOpen && <span>Open folder</span>}
  </div></section>;
}
function escape(target: HTMLElement) {
  const event = new KeyboardEvent("keydown", { key: "Escape", bubbles: true, cancelable: true });
  fireEvent(target, event);
  return event;
}
it("consumes the first Escape from the composer, then allows the shell to close the tab", () => {
  render(<Inbox />);
  const closeTab = vi.fn((event: KeyboardEvent) => { if (event.defaultPrevented) return; tabClosed(); });
  const tabClosed = vi.fn();
  window.addEventListener("keydown", closeTab);
  try {
    expect(escape(screen.getByRole("textbox")).defaultPrevented).toBe(true);
    expect(screen.queryByText("Open chat")).toBeNull();
    expect(tabClosed).not.toHaveBeenCalled();
    expect(escape(screen.getByRole("textbox")).defaultPrevented).toBe(false);
    expect(tabClosed).toHaveBeenCalledOnce();
  } finally { window.removeEventListener("keydown", closeTab); }
});
it("also handles Escape from its own tab header", () => {
  render(<Inbox />);
  expect(escape(screen.getByRole("button")).defaultPrevented).toBe(true);
});
it("leaves another pane and hidden tabs alone", () => {
  render(<><Inbox hidden /><section data-workspace-pane-id="other"><button>Other tab</button></section></>);
  expect(escape(screen.getByText("Other tab")).defaultPrevented).toBe(false);
  expect(escape(screen.getByText("Inbox tab")).defaultPrevented).toBe(false);
  expect(screen.getByText("Open chat")).toBeTruthy();
});
it("lets a menu or a component consuming Escape handle it first", () => {
  const view = render(<><Inbox /><div role="menu">Tags</div></>);
  expect(escape(screen.getByRole("textbox")).defaultPrevented).toBe(false);
  expect(screen.getByText("Open chat")).toBeTruthy();
  view.rerender(<Inbox />);
  const input = screen.getByRole("textbox");
  input.addEventListener("keydown", event => event.preventDefault(), { once: true });
  escape(input);
  expect(screen.getByText("Open chat")).toBeTruthy();
});

it("steps back from chat to folder to conversations before closing the tab", () => {
  render(<Inbox folder />);
  const input = screen.getByRole("textbox");
  expect(escape(input).defaultPrevented).toBe(true);
  expect(screen.queryByText("Open chat")).toBeNull();
  expect(screen.getByText("Open folder")).toBeTruthy();
  expect(escape(input).defaultPrevented).toBe(true);
  expect(screen.queryByText("Open folder")).toBeNull();
  expect(escape(input).defaultPrevented).toBe(false);
});
it("closes an open folder when there is no selected chat", () => {
  render(<Inbox folder initialChat={false} />);
  expect(escape(screen.getByRole("textbox")).defaultPrevented).toBe(true);
  expect(screen.queryByText("Open folder")).toBeNull();
  expect(escape(screen.getByRole("textbox")).defaultPrevented).toBe(false);
});
