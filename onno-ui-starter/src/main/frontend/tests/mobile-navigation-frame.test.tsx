import {act, cleanup, fireEvent, render, screen} from "@testing-library/react";
import {afterEach, expect, it, vi} from "vitest";
import {MobileNavigationFrame, mobileParent} from "@/components/mobile-navigation-frame";
afterEach(() => {cleanup(); vi.restoreAllMocks();});
it("keeps parent state and restores navigation when returning from a record", () => {
  vi.spyOn(window, "scrollTo").mockImplementation(() => {});
  const back = vi.fn();
  const props = {roots:["/catalogs/contacts"], back, backLabel:"Back", navigation:<nav>Tabs</nav>,
    render:(path:string) => <input aria-label={path} defaultValue=""/>};
  const view = render(<MobileNavigationFrame {...props} path="/catalogs/contacts"/>);
  fireEvent.change(screen.getByRole("textbox"), {target:{value:"Amelia"}});
  view.rerender(<MobileNavigationFrame {...props} path="/catalogs/contacts/123"/>);
  expect(screen.queryByRole("navigation")).toBeNull();
  fireEvent.click(screen.getByRole("button", {name:"Back"}));
  expect(back).toHaveBeenCalledWith("/catalogs/contacts");
  view.rerender(<MobileNavigationFrame {...props} path="/catalogs/contacts"/>);
  expect(screen.getByRole("textbox")).toHaveValue("Amelia");
  expect(screen.getByRole("navigation")).toBeVisible();
});
it("handles deep links and scoped widget detail states", () => {
  vi.spyOn(window, "scrollTo").mockImplementation(() => {});
  const back = vi.fn();
  const props = {roots:["/inbox"],back,backLabel:"Back",navigation:<nav>Tabs</nav>,render:()=><div/>};
  const view = render(<MobileNavigationFrame {...props} path="/catalogs/contacts/123/edit"/>);
  fireEvent.click(screen.getByRole("button"));
  expect(back).toHaveBeenCalledWith("/catalogs/contacts");
  view.rerender(<MobileNavigationFrame {...props} path="/inbox"/>);
  act(() => window.dispatchEvent(new CustomEvent("onno:mobile-detail",{detail:{id:"chat",path:"/inbox",active:true}})));
  expect(screen.queryByRole("navigation")).toBeNull();
  act(() => window.dispatchEvent(new CustomEvent("onno:mobile-detail",{detail:{id:"chat",active:false}})));
  expect(screen.getByRole("navigation")).toBeVisible();
  expect(mobileParent("/custom/child")).toBe("/custom");
});
