import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render } from "@testing-library/react";
import { SsoIcon } from "@/lib/sso-icon-bridge";

afterEach(cleanup);

describe("SsoIcon", () => {
  it("renders nothing without a source", () => {
    const { container } = render(<SsoIcon src="" />);
    expect(container).toBeEmptyDOMElement();
  });

  it("renders a full-color image by default so the logo keeps its brand colors", () => {
    const { container } = render(
      <SsoIcon src="/api/auth/telegram/logo.svg" color="rgb(10, 20, 30)" size={18} />
    );
    const icon = container.querySelector("[data-onno-sso-icon]") as HTMLImageElement;
    expect(icon).not.toBeNull();
    expect(icon.tagName).toBe("IMG");
    expect(icon.getAttribute("src")).toBe("/api/auth/telegram/logo.svg");
    // No tint: a full-color mark is shown as-is, not masked/recolored.
    const style = icon.getAttribute("style") ?? "";
    expect(style).not.toMatch(/mask-image/i);
    expect(icon.style.width).toBe("18px");
    expect(icon.style.height).toBe("18px");
  });

  it("masks the provider SVG and fills it with the given color when monochrome", () => {
    const { container } = render(
      <SsoIcon src="/api/auth/telegram/logo.svg" color="rgb(10, 20, 30)" size={18} monochrome />
    );
    const icon = container.querySelector("[data-onno-sso-icon]") as HTMLElement;
    expect(icon).not.toBeNull();
    // The SVG is used as a CSS mask (so any monochrome logo URL works), painted in the supplied
    // color — equivalent to the SVG's own currentColor, but reliable for an external URL. Assert on
    // the raw inline style so the check doesn't depend on jsdom's CSSOM mask support.
    const style = icon.getAttribute("style") ?? "";
    expect(style).toContain("/api/auth/telegram/logo.svg");
    expect(style).toMatch(/mask-image/i);
    expect(icon.style.backgroundColor).toBe("rgb(10, 20, 30)");
    expect(icon.style.width).toBe("18px");
    expect(icon.style.height).toBe("18px");
  });

  it("falls back to currentColor when monochrome and no color is given", () => {
    const { container } = render(<SsoIcon src="/logo.svg" monochrome />);
    const icon = container.querySelector("[data-onno-sso-icon]") as HTMLElement;
    // jsdom normalizes the keyword to lowercase; compare case-insensitively.
    expect(icon.style.backgroundColor.toLowerCase()).toBe("currentcolor");
  });

  it("is hidden from assistive tech (the adjacent label names the button)", () => {
    const { container } = render(<SsoIcon src="/logo.svg" />);
    const icon = container.querySelector("[data-onno-sso-icon]") as HTMLElement;
    expect(icon.getAttribute("aria-hidden")).toBe("true");
  });
});
