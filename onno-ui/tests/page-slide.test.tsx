import { cleanup, render } from "@testing-library/react";
import { useRef } from "react";
import { afterEach, expect, it, vi } from "vitest";
import { usePageSlide } from "@/lib/use-page-slide";
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });
it("snaps on mount and breakpoints, slides forward/back, and cancels interrupted motion", async () => {
  const cancel = vi.fn();
  const animate = vi.fn((_keyframes: Keyframe[]) => ({ finished: new Promise(() => {}), cancel }));
  Object.defineProperty(HTMLElement.prototype, "animate", {configurable:true, value:animate});
  vi.stubGlobal("matchMedia", () => ({matches:false}));
  function Pages({page, enabled = true}: {page:string; enabled?:boolean}) {
    const ref = useRef<HTMLDivElement>(null);
    usePageSlide(ref, page, enabled);
    return <div ref={ref}><div data-slide-page="list"/><div data-slide-page="detail"/></div>;
  }
  const view = render(<Pages page="detail"/>);
  expect(animate).not.toHaveBeenCalled();
  view.rerender(<Pages page="list"/>);
  expect(animate.mock.calls[0][0][0].transform).toBe("translateX(-100%)");
  view.rerender(<Pages page="detail"/>);
  expect(cancel).toHaveBeenCalledTimes(2);
  expect(animate.mock.calls[2][0][0].transform).toBe("translateX(100%)");
  animate.mockClear();
  view.rerender(<Pages page="list" enabled={false}/>);
  view.rerender(<Pages page="list"/>);
  expect(animate).not.toHaveBeenCalled();
  vi.stubGlobal("matchMedia", () => ({matches:true}));
  view.rerender(<Pages page="detail"/>);
  expect(animate).not.toHaveBeenCalled();
  delete (HTMLElement.prototype as Partial<HTMLElement>).animate;
});
