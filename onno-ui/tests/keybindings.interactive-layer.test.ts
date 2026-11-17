import { afterEach, describe, expect, it } from "vitest";
import { isInteractiveLayerOpen } from "@/lib/keybindings";

afterEach(() => {
  document.body.replaceChildren();
});

describe("interactive layer detection", () => {
  it("ignores a force-mounted closed Radix menu and its popper wrapper", () => {
    document.body.innerHTML = `
      <div data-radix-popper-content-wrapper style="position: fixed">
        <div role="menu" data-state="closed"></div>
      </div>
    `;

    expect(isInteractiveLayerOpen()).toBe(false);
  });

  it("blocks global shortcuts while a Radix menu is actually open", () => {
    document.body.innerHTML = `
      <div data-radix-popper-content-wrapper style="position: fixed">
        <div role="menu" data-state="open"></div>
      </div>
    `;

    expect(isInteractiveLayerOpen()).toBe(true);
  });

  it("continues to detect non-Radix context menus", () => {
    document.body.innerHTML = '<div role="menu" style="position: fixed"></div>';

    expect(isInteractiveLayerOpen()).toBe(true);
  });
});
