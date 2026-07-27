import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import {
  Drawer,
  DrawerClose,
  DrawerContent,
  DrawerTitle,
} from "@/components/ui/drawer";

describe("Drawer", () => {
  it("composes the Base UI portal, backdrop, viewport, popup, and close behavior", () => {
    const onOpenChange = vi.fn();
    render(
      <Drawer open onOpenChange={onOpenChange} swipeDirection="right">
        <DrawerContent className="right-3">
          <DrawerTitle>Notifications</DrawerTitle>
          <DrawerClose>Close</DrawerClose>
        </DrawerContent>
      </Drawer>
    );

    expect(screen.getByRole("dialog", { name: "Notifications" })).toHaveAttribute(
      "data-swipe-direction",
      "right"
    );
    expect(document.querySelector('[data-slot="drawer-overlay"]')).toBeInTheDocument();
    expect(document.querySelector('[data-slot="drawer-viewport"]')).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Close" }));
    expect(onOpenChange).toHaveBeenCalledWith(false, expect.any(Object));
  });
});
