import { act, cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { useState } from "react";
import { afterEach, beforeAll, beforeEach, expect, it, vi } from "vitest";
import {
  FloatingWindow,
  WindowLayer,
  resetWindowsForTests,
  useOpenWindows,
  useWindow,
  windows,
} from "../src/components/ui/window-manager";

beforeAll(() => {
  // jsdom has no PointerEvent; without one, fireEvent drops clientX/clientY.
  if (!("PointerEvent" in window)) {
    class PointerEvent extends MouseEvent {
      pointerId: number;
      constructor(type: string, init: PointerEventInit = {}) {
        super(type, init);
        this.pointerId = init.pointerId ?? 1;
      }
    }
    Object.assign(window, { PointerEvent });
  }
});

beforeEach(() => {
  window.localStorage.clear();
  Object.assign(window, { innerWidth: 1280, innerHeight: 800 });
});
afterEach(() => {
  cleanup();
  act(() => resetWindowsForTests());
  window.localStorage.clear();
});

const frame = (name: string) => screen.getByRole("dialog", { name });
const box = (element: HTMLElement) => ({
  left: parseFloat(element.style.left), top: parseFloat(element.style.top),
  width: parseFloat(element.style.width), height: parseFloat(element.style.height),
});

function drag(target: Element, from: [number, number], to: [number, number]) {
  fireEvent.pointerDown(target, { button: 0, clientX: from[0], clientY: from[1], pointerId: 1 });
  fireEvent.pointerMove(target, { clientX: to[0], clientY: to[1], pointerId: 1 });
  fireEvent.pointerUp(target, { clientX: to[0], clientY: to[1], pointerId: 1 });
}

it("opens a non-modal window beside the page, which stays interactive", () => {
  const clicked = vi.fn();
  render(<>
    <button onClick={clicked}>Page button</button>
    <FloatingWindow title="Notes"><p>Body</p></FloatingWindow>
  </>);
  const notes = frame("Notes");
  expect(notes).toHaveAttribute("aria-modal", "false");
  expect(notes).toHaveAttribute("data-window-state", "floating");
  fireEvent.click(screen.getByRole("button", { name: "Page button" }));
  expect(clicked).toHaveBeenCalled();
});

it("docks, minimises with its content still mounted, maximises and restores", () => {
  function Draft() {
    const [text, setText] = useState("");
    return <textarea aria-label="Draft" value={text} onChange={event => setText(event.target.value)} />;
  }
  render(<FloatingWindow title="Compose" initialState="docked"><Draft /></FloatingWindow>);
  const compose = frame("Compose");
  expect(compose).toHaveAttribute("data-window-state", "docked");
  // Docked: the bottom-right corner.
  expect(box(compose).top + box(compose).height).toBe(800);
  expect(box(compose).left + box(compose).width).toBe(1280 - 16);

  fireEvent.change(screen.getByRole("textbox", { name: "Draft" }), { target: { value: "half a thought" } });
  fireEvent.click(within(compose).getByRole("button", { name: "Minimize" }));
  expect(compose).toHaveAttribute("data-window-state", "minimized");
  expect(box(compose).height).toBe(44);
  fireEvent.click(within(compose).getByRole("button", { name: "Restore" }));
  expect(screen.getByRole("textbox", { name: "Draft" })).toHaveValue("half a thought");

  fireEvent.click(within(compose).getByRole("button", { name: "Maximize" }));
  expect(compose).toHaveAttribute("data-window-state", "maximized");
  fireEvent.click(within(compose).getByRole("button", { name: "Restore" }));
  // Restoring returns to where it was, not to floating.
  expect(compose).toHaveAttribute("data-window-state", "docked");
});

it("leaves most of a short screen to the page beside a docked window", () => {
  Object.assign(window, { innerWidth: 1280, innerHeight: 600 });
  render(<FloatingWindow title="Compose" initialState="docked" height={580}>x</FloatingWindow>);
  expect(box(frame("Compose"))).toMatchObject({ height: 420, top: 180 });
});

it("lines docked and minimised windows up along the bottom, right to left", () => {
  render(<>
    <FloatingWindow title="First" initialState="docked" width={400}>a</FloatingWindow>
    <FloatingWindow title="Second" initialState="docked" width={300}>b</FloatingWindow>
  </>);
  expect(box(frame("First")).left).toBe(1280 - 16 - 400);
  expect(box(frame("Second")).left).toBe(1280 - 16 - 400 - 12 - 300);
  fireEvent.click(within(frame("First")).getByRole("button", { name: "Minimize" }));
  expect(box(frame("Second")).left).toBe(1280 - 16 - 280 - 12 - 300);
});

it("drags a floating window by its title bar and resizes it from an edge, never below its minimum", () => {
  render(<FloatingWindow title="Panel" x={100} y={100} width={500} height={400} minWidth={360}>x</FloatingWindow>);
  const panel = frame("Panel");
  drag(within(panel).getByRole("heading", { name: "Panel" }), [200, 120], [260, 170]);
  expect(box(panel)).toMatchObject({ left: 160, top: 150, width: 500, height: 400 });

  const east = panel.querySelector('[data-window-resize="e"]')!;
  drag(east, [660, 300], [460, 300]);
  expect(box(panel).width).toBe(360);
  const south = panel.querySelector('[data-window-resize="s"]')!;
  drag(south, [300, 550], [300, 650]);
  expect(box(panel).height).toBe(500);
});

it("lifts a docked window out of the corner when its title bar is dragged", () => {
  render(<FloatingWindow title="Compose" initialState="docked">x</FloatingWindow>);
  const compose = frame("Compose");
  drag(within(compose).getByRole("heading", { name: "Compose" }), [900, 300], [700, 200]);
  expect(compose).toHaveAttribute("data-window-state", "floating");
});

it("remembers state, position and size under its persist key", () => {
  const { unmount } = render(<FloatingWindow title="Panel" persist="panel" x={50} y={60}>x</FloatingWindow>);
  drag(within(frame("Panel")).getByRole("heading", { name: "Panel" }), [100, 70], [140, 110]);
  unmount();
  render(<FloatingWindow title="Panel" persist="panel" x={50} y={60}>x</FloatingWindow>);
  expect(box(frame("Panel"))).toMatchObject({ left: 90, top: 100 });
});

it("keeps a window's title bar on screen", () => {
  render(<FloatingWindow title="Panel" x={5000} y={5000} width={400} height={300}>x</FloatingWindow>);
  expect(box(frame("Panel"))).toMatchObject({ left: 1280 - 400, top: 800 - 44 });
});

it("closes through onOpenChange and leaves the decision to the owner", () => {
  function Owner() {
    const [open, setOpen] = useState(true);
    return <FloatingWindow title="Notes" open={open} onOpenChange={setOpen}>x</FloatingWindow>;
  }
  render(<Owner />);
  fireEvent.click(within(frame("Notes")).getByRole("button", { name: "Close" }));
  expect(screen.queryByRole("dialog", { name: "Notes" })).not.toBeInTheDocument();
});

it("keeps Escape inside the window", () => {
  const pageEscape = vi.fn();
  document.addEventListener("keydown", pageEscape);
  render(<FloatingWindow title="Notes"><input aria-label="Field" /></FloatingWindow>);
  fireEvent.keyDown(screen.getByRole("textbox", { name: "Field" }), { key: "Escape" });
  expect(pageEscape).not.toHaveBeenCalled();
  document.removeEventListener("keydown", pageEscape);
});

it("opens app-owned windows from anywhere, without duplicates, and honours a close guard", () => {
  function Content() {
    const window_ = useWindow();
    return <button onClick={() => window_.setState("minimized")}>Hide me</button>;
  }
  function Count() {
    return <output aria-label="Open windows">{useOpenWindows().length}</output>;
  }
  render(<><WindowLayer /><Count /></>);
  let allowClose = false;
  act(() => { windows.open("compose:1", { title: "Reply to Anna", content: <Content />, onClose: () => allowClose }); });
  act(() => { windows.open("compose:1", { title: "Reply to Anna", content: <Content />, onClose: () => allowClose }); });
  expect(screen.getAllByRole("dialog", { name: "Reply to Anna" })).toHaveLength(1);
  expect(screen.getByLabelText("Open windows")).toHaveTextContent("1");

  fireEvent.click(screen.getByRole("button", { name: "Hide me" }));
  expect(frame("Reply to Anna")).toHaveAttribute("data-window-state", "minimized");
  // Opening it again brings it back.
  act(() => { windows.open("compose:1", { title: "Reply to Anna", content: <Content /> , onClose: () => allowClose }); });
  expect(frame("Reply to Anna")).toHaveAttribute("data-window-state", "floating");

  fireEvent.click(within(frame("Reply to Anna")).getByRole("button", { name: "Close" }));
  expect(frame("Reply to Anna")).toBeInTheDocument();
  allowClose = true;
  fireEvent.click(within(frame("Reply to Anna")).getByRole("button", { name: "Close" }));
  expect(screen.queryByRole("dialog", { name: "Reply to Anna" })).not.toBeInTheDocument();
  expect(windows.isOpen("compose:1")).toBe(false);
});

it("brings the window the user touches to the front", () => {
  render(<>
    <FloatingWindow title="One">1</FloatingWindow>
    <FloatingWindow title="Two">2</FloatingWindow>
  </>);
  const z = (name: string) => Number(frame(name).style.zIndex);
  expect(z("Two")).toBeGreaterThan(z("One"));
  fireEvent.pointerDown(frame("One"));
  expect(z("One")).toBeGreaterThan(z("Two"));
});

it("fills a phone screen and drops the desktop-only controls", () => {
  Object.assign(window, { innerWidth: 390, innerHeight: 844 });
  render(<FloatingWindow title="Compose" initialState="docked">x</FloatingWindow>);
  const compose = frame("Compose");
  expect(box(compose)).toMatchObject({ left: 0, top: 0, width: 390, height: 844 });
  expect(within(compose).queryByRole("button", { name: "Maximize" })).not.toBeInTheDocument();
  expect(within(compose).queryByRole("button", { name: "Dock to the corner" })).not.toBeInTheDocument();
  expect(compose.querySelector("[data-window-resize]")).toBeNull();
});
