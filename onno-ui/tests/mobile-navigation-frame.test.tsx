import {act, cleanup, fireEvent, render, screen, waitFor} from "@testing-library/react";
import {afterEach, beforeEach, expect, it, vi} from "vitest";
import {MobileNavigationFrame, mobileParent, useMobileDetail} from "@/components/shell/mobile-navigation-frame";
beforeEach(() => { vi.stubGlobal("ResizeObserver", class { observe() {} disconnect() {} }); });
afterEach(() => {cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals();});
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
  // An inbox page whose open chat is a child state with its own Back.
  function Inbox({chatOpen}: {chatOpen: boolean}) {
    useMobileDetail(chatOpen);
    return <div/>;
  }
  let chatOpen = false;
  const props = {roots:["/inbox"],back,backLabel:"Back",navigation:<nav>Tabs</nav>,
    render:(path:string)=>path === "/inbox" ? <Inbox chatOpen={chatOpen}/> : <div/>};
  const view = render(<MobileNavigationFrame {...props} path="/catalogs/contacts/123/edit"/>);
  fireEvent.click(screen.getByRole("button"));
  expect(back).toHaveBeenCalledWith("/catalogs/contacts");
  view.rerender(<MobileNavigationFrame {...props} path="/inbox"/>);
  expect(screen.getByRole("navigation")).toBeVisible();
  chatOpen = true;
  view.rerender(<MobileNavigationFrame {...props} path="/inbox"/>);
  expect(screen.queryByRole("navigation")).toBeNull();
  chatOpen = false;
  view.rerender(<MobileNavigationFrame {...props} path="/inbox"/>);
  expect(screen.getByRole("navigation")).toBeVisible();
  expect(mobileParent("/custom/child")).toBe("/custom");
});
it("retains an exiting record until Back finishes animating", async () => {
  vi.spyOn(window, "scrollTo").mockImplementation(() => {});
  const resolvers: Array<() => void> = [];
  const animate = vi.fn(() => ({cancel: vi.fn(), finished: new Promise<void>(resolve => resolvers.push(resolve))}));
  Object.defineProperty(HTMLElement.prototype, "animate", {configurable:true, value:animate});
  const props = {roots:["/catalogs/contacts"],back:vi.fn(),backLabel:"Back",navigation:<nav>Tabs</nav>,render:(path:string)=><div>{path}</div>};
  const view = render(<MobileNavigationFrame {...props} path="/catalogs/contacts"/>);
  expect(animate).not.toHaveBeenCalled();
  view.rerender(<MobileNavigationFrame {...props} path="/catalogs/contacts/123"/>);
  expect(animate).toHaveBeenCalledTimes(2);
  await act(async () => { resolvers.splice(0).forEach(resolve => resolve()); });
  view.rerender(<MobileNavigationFrame {...props} path="/catalogs/contacts"/>);
  const exiting = view.container.querySelector('[data-slide-page="/catalogs/contacts/123"]');
  expect(exiting).toBeInTheDocument();
  expect(exiting).toHaveAttribute("aria-hidden", "true");
  await act(async () => { resolvers.splice(0).forEach(resolve => resolve()); });
  expect(exiting).not.toBeInTheDocument();
  delete (HTMLElement.prototype as Partial<HTMLElement>).animate;
});
it("fades section changes quickly but still slides the next detail and Back", async () => {
  vi.spyOn(window, "scrollTo").mockImplementation(() => {});
  const animate = vi.fn((_keyframes:Keyframe[], _options?:KeyframeAnimationOptions) => ({cancel:vi.fn(),finished:Promise.resolve()}));
  Object.defineProperty(HTMLElement.prototype,"animate",{configurable:true,value:animate});
  const props = {roots:["/inbox","/catalogs/contacts"],back:vi.fn(),backLabel:"Back",navigation:<nav>Tabs</nav>,render:(path:string)=><div>{path}</div>};
  const view = render(<MobileNavigationFrame {...props} path="/inbox"/>);
  expect(animate).not.toHaveBeenCalled();
  await act(async () => view.rerender(<MobileNavigationFrame {...props} path="/catalogs/contacts"/>));
  expect(animate.mock.calls[0][0]).toEqual([{opacity:0,transform:"translateX(0)"},{opacity:1,transform:"translateX(0)"}]);
  expect(animate.mock.calls[0][1]).toMatchObject({duration:120});
  animate.mockClear();
  await act(async () => view.rerender(<MobileNavigationFrame {...props} path="/catalogs/contacts/123"/>));
  expect(animate.mock.calls[0][0][0]).toEqual({transform:"translateX(100%)"});
  animate.mockClear();
  await act(async () => view.rerender(<MobileNavigationFrame {...props} path="/catalogs/contacts"/>));
  expect(animate.mock.calls[0][0][0]).toEqual({transform:"translateX(-100%)"});
  delete (HTMLElement.prototype as Partial<HTMLElement>).animate;
});

it("integrates one Back into the active page header and preserves the retained parent", async () => {
  vi.spyOn(window, "scrollTo").mockImplementation(() => {});
  function Page({path}: {path: string}) {
    return <><div data-onno-page-header="" role="banner"><h1>{path}</h1><button>Actions</button></div><input aria-label="Draft" /></>;
  }
  const back = vi.fn();
  const props = {roots:["/documents/weddings"],back,backLabel:"Back",navigation:<nav>Tabs</nav>,render:(path:string)=><Page path={path}/>};
  const view = render(<MobileNavigationFrame {...props} path="/documents/weddings"/>);
  fireEvent.change(screen.getByRole("textbox"), {target:{value:"Retained"}});
  view.rerender(<MobileNavigationFrame {...props} path="/documents/weddings/123"/>);
  await waitFor(() => expect(screen.getAllByRole("banner")).toHaveLength(1));
  const button = screen.getByRole("button", {name:"Back"});
  expect(screen.getByRole("banner")).toContainElement(button);
  expect(screen.getByRole("banner")).toContainElement(screen.getByRole("button", {name:"Actions"}));
  fireEvent.click(button);
  expect(back).toHaveBeenCalledWith("/documents/weddings");
  view.rerender(<MobileNavigationFrame {...props} path="/documents/weddings"/>);
  expect(screen.queryByRole("button", {name:"Back"})).toBeNull();
  expect(screen.getByRole("textbox")).toHaveValue("Retained");
});
