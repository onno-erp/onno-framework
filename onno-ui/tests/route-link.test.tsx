import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterAll, afterEach, beforeAll, beforeEach, expect, it, vi } from "vitest";
import { openRoute } from "@/lib/navigation";
import { RouteLink } from "@/components/pages/route-link";

vi.hoisted(() => vi.stubGlobal("__onnoBasePath", "/workspace"));

const actions: string[] = [];
const captureAction = (event: Event) => actions.push((event as CustomEvent<string>).detail);

beforeAll(() => {
  window.addEventListener("onno:open", captureAction);
});

beforeEach(() => { actions.length = 0; });

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

afterAll(() => {
  window.removeEventListener("onno:open", captureAction);
  vi.unstubAllGlobals();
});

it("sends app paths and onno:// links through the workspace", () => {
  openRoute("/reports?period=month");
  openRoute("onno://documents/sales_orders/c");
  expect(actions).toEqual(["/reports?period=month", "onno://documents/sales_orders/c"]);
});

it("links an onno:// record link to its browser URL", () => {
  render(<RouteLink to="onno://documents/sales_orders/c">Order</RouteLink>);
  expect(screen.getByRole("link", { name: "Order" })).toHaveAttribute("href", "/workspace/documents/sales_orders/c");
});

it("opens an explicit new browser tab under the configured UI prefix", () => {
  const open = vi.spyOn(window, "open").mockReturnValue(null);
  openRoute("/reports?period=month", { newTab: true });
  expect(open).toHaveBeenCalledWith("/workspace/reports?period=month", "_blank", "noopener,noreferrer");
  expect(actions).toEqual([]);
});

it.each(["https://example.com", "//example.com", "/\n/example.com", "/%2e%2e/settings", "onno://logout"])(
  "ignores %j as a link route",
  (path) => {
    const open = vi.spyOn(window, "open").mockReturnValue(null);
    openRoute(path, { newTab: true });
    render(<RouteLink to={path}>Invalid route</RouteLink>);
    expect(screen.getByText("Invalid route")).not.toHaveAttribute("href");
    expect(open).not.toHaveBeenCalled();
    expect(actions).toEqual([]);
  },
);

it("renders a working href and uses workspace navigation for an ordinary click", () => {
  render(<RouteLink to="/reports?period=month">Reports</RouteLink>);
  const link = screen.getByRole("link", { name: "Reports" });
  expect(link).toHaveAttribute("href", "/workspace/reports?period=month");
  expect(fireEvent.click(link)).toBe(false);
  expect(actions).toEqual(["/reports?period=month"]);
});

it("leaves modified clicks, middle clicks and a separate target to the browser", () => {
  render(<>
    <RouteLink to="/reports">Reports</RouteLink>
    <RouteLink to="/reports" target="_blank">Reports in a new tab</RouteLink>
  </>);
  const link = screen.getByRole("link", { name: "Reports" });
  const newTab = screen.getByRole("link", { name: "Reports in a new tab" });
  const browserClicks: boolean[] = [];
  function cancelBrowserNavigation(event: Event) {
    browserClicks.push(event.defaultPrevented);
    event.preventDefault();
  }
  document.addEventListener("click", cancelBrowserNavigation);
  document.addEventListener("auxclick", cancelBrowserNavigation);
  try {
    fireEvent.click(link, { ctrlKey: true });
    fireEvent.click(link, { metaKey: true });
    fireEvent(link, new MouseEvent("auxclick", { button: 1, bubbles: true, cancelable: true }));
    fireEvent.click(newTab);
  } finally {
    document.removeEventListener("click", cancelBrowserNavigation);
    document.removeEventListener("auxclick", cancelBrowserNavigation);
  }
  expect(browserClicks).toEqual([false, false, false, false]);
  expect(actions).toEqual([]);
});

it("honors a click handler that prevents navigation", () => {
  render(<RouteLink to="/reports" onClick={(event) => event.preventDefault()}>Reports</RouteLink>);
  fireEvent.click(screen.getByRole("link", { name: "Reports" }));
  expect(actions).toEqual([]);
});
