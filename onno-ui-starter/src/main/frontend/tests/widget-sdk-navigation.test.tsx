import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterAll, afterEach, beforeAll, beforeEach, expect, it, vi } from "vitest";
import "@/lib/plugin-host";
import * as sdk from "@onno/widget-sdk";

vi.hoisted(() => vi.stubGlobal("__onnoBasePath", "/workspace"));

const actions: string[] = [];
const captureAction = (event: Event) => actions.push((event as CustomEvent<string>).detail);

beforeAll(() => {
  window.addEventListener("onno:action", captureAction);
});

beforeEach(() => { actions.length = 0; });

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

afterAll(() => {
  window.removeEventListener("onno:action", captureAction);
  vi.unstubAllGlobals();
});

it("sends app paths and encoded record paths through the workspace", () => {
  sdk.navigate("/reports?period=month");
  sdk.openRecord("catalogs", "sales orders", "a/b");
  expect(actions).toEqual(["/reports?period=month", "/catalogs/sales%20orders/a%2Fb"]);
});

it("opens an explicit new browser tab under the configured UI prefix", () => {
  const open = vi.spyOn(window, "open").mockReturnValue(null);
  sdk.navigate("/reports?period=month", { newTab: true });
  expect(open).toHaveBeenCalledWith("/workspace/reports?period=month", "_blank", "noopener,noreferrer");
  expect(actions).toEqual([]);
});

it.each(["https://example.com", "//example.com", "/\n/example.com", "/%2e%2e/settings", "onno://logout"])(
  "ignores %j as a widget route",
  (path) => {
    const open = vi.spyOn(window, "open").mockReturnValue(null);
    sdk.navigate(path);
    sdk.navigate(path, { newTab: true });
    render(<sdk.Link to={path}>Invalid route</sdk.Link>);
    expect(screen.getByText("Invalid route")).not.toHaveAttribute("href");
    expect(open).not.toHaveBeenCalled();
    expect(actions).toEqual([]);
  },
);

it("renders a working href and uses workspace navigation for an ordinary click", () => {
  render(<sdk.Link to="/reports?period=month">Reports</sdk.Link>);
  const link = screen.getByRole("link", { name: "Reports" });
  expect(link).toHaveAttribute("href", "/workspace/reports?period=month");
  expect(fireEvent.click(link)).toBe(false);
  expect(actions).toEqual(["/reports?period=month"]);
});

it("leaves modified clicks, middle clicks and a separate target to the browser", () => {
  render(<>
    <sdk.Link to="/reports">Reports</sdk.Link>
    <sdk.Link to="/reports" target="_blank">Reports in a new tab</sdk.Link>
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

it("honors a widget click handler that prevents navigation", () => {
  render(<sdk.Link to="/reports" onClick={(event) => event.preventDefault()}>Reports</sdk.Link>);
  fireEvent.click(screen.getByRole("link", { name: "Reports" }));
  expect(actions).toEqual([]);
});
