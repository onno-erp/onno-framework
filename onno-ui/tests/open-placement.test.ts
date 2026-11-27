import { describe, expect, it, vi } from "vitest";
import { placementForClick } from "@/lib/open-placement";
import { navigationClickHandlers } from "@/components/shell/navigation";
import type { MouseEvent } from "react";

describe("placementForClick", () => {
  it("follows the browser's modifiers", () => {
    expect(placementForClick({})).toBe("tab");
    expect(placementForClick({ ctrlKey: true })).toBe("new-tab");
    expect(placementForClick({ metaKey: true })).toBe("new-tab");
    expect(placementForClick({ button: 1 })).toBe("new-tab");
    expect(placementForClick({ shiftKey: true })).toBe("new-island");
    // Ctrl/⌘ wins over Shift, as it does for a browser's "new tab in the foreground".
    expect(placementForClick({ metaKey: true, shiftKey: true })).toBe("new-tab");
  });
});

describe("navigationClickHandlers", () => {
  const click = (init: Partial<MouseEvent>) => ({ preventDefault: vi.fn(), ...init }) as unknown as MouseEvent;

  it("navigates on a plain click, opens a tab on Ctrl/⌘ or middle click, an island on Shift", () => {
    const onNavigate = vi.fn();
    const handlers = navigationClickHandlers("/reports", onNavigate);
    handlers.onClick(click({}));
    handlers.onClick(click({ metaKey: true }));
    handlers.onClick(click({ shiftKey: true }));
    handlers.onAuxClick(click({ button: 1 }));
    handlers.onAuxClick(click({ button: 2 }));
    expect(onNavigate.mock.calls).toEqual([
      ["/reports"],
      ["/reports", { newWorkspaceTab: true }],
      ["/reports", { newIsland: true }],
      ["/reports", { newWorkspaceTab: true }],
    ]);
  });
});
