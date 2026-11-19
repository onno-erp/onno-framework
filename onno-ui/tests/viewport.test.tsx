import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, renderHook, screen } from "@testing-library/react";
import { BELOW_DESKTOP_QUERY, currentViewport, MOBILE_QUERY, useViewport } from "@/lib/viewport";
import { useFacetOverlay, useTouchLayout } from "@/components/ui/facet-sheet";
import { Row } from "@/components/pages/page-blocks";
import { stubScreenWidth } from "./viewport-fixtures";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("useViewport", () => {
  it.each([
    [320, "mobile"],
    [700, "mobile"],
    [767, "mobile"],
    // A fractional width under browser zoom still lands in exactly one class, right up to the bound.
    [767.5, "mobile"],
    [767.99, "mobile"],
    [768, "tablet"],
    [1023, "tablet"],
    [1023.99, "tablet"],
    [1024, "desktop"],
    [1920, "desktop"],
  ] as const)("classifies a %dpx screen as %s", (width, expected) => {
    stubScreenWidth(width);
    expect(currentViewport()).toBe(expected);
    expect(renderHook(() => useViewport()).result.current).toBe(expected);
  });

  it("is desktop where media queries are unavailable", () => {
    vi.stubGlobal("matchMedia", undefined);
    expect(currentViewport()).toBe("desktop");
    expect(renderHook(() => useViewport()).result.current).toBe("desktop");
  });

  it("re-renders when the screen crosses a breakpoint and unsubscribes on unmount", () => {
    const screenWidth = stubScreenWidth(1280);
    const { result, unmount } = renderHook(() => useViewport());
    expect(result.current).toBe("desktop");
    screenWidth.resize(900);
    expect(result.current).toBe("tablet");
    screenWidth.resize(700);
    expect(result.current).toBe("mobile");
    unmount();
    expect(screenWidth.listenerCount()).toBe(0);
  });

  it("classifies by the exact complements of Tailwind's md and lg", () => {
    expect(MOBILE_QUERY).toBe("not all and (min-width: 768px)");
    expect(BELOW_DESKTOP_QUERY).toBe("not all and (min-width: 1024px)");
  });

  it("is desktop when a stub answers no to every query", () => {
    vi.stubGlobal("matchMedia", (query: string) => ({ media: query, matches: false, addEventListener() {}, removeEventListener() {} }));
    expect(currentViewport()).toBe("desktop");
  });
});

describe("one device class across the UI at 700px", () => {
  it("opens facets as a phone sheet", () => {
    stubScreenWidth(700);
    expect(renderHook(() => useFacetOverlay()).result.current).toBe("sheet");
    expect(renderHook(() => useTouchLayout()).result.current).toBe(true);
  });

  it("stacks page rows as on a phone", () => {
    stubScreenWidth(700);
    render(<Row><div>Left</div><div>Right</div></Row>);
    expect(screen.getByText("Left").parentElement).toHaveClass("flex-col");
  });

  it("keeps tablets on the centered modal and desktops on popovers", () => {
    const screenWidth = stubScreenWidth(900);
    const { result } = renderHook(() => useFacetOverlay());
    expect(result.current).toBe("modal");
    screenWidth.resize(1280);
    expect(result.current).toBe("popover");
  });
});
