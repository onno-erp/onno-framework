import { afterEach, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { useState } from "react";
import { FacetSheet, SheetDoneButton } from "@/components/ui/facet-sheet";
import { stubScreenWidth } from "./viewport-fixtures";

// Keep the drawer visible in both states to inspect the lifecycle handed to Vaul.
// Vaul owns CSS presence and swipe motion; the framework must not unmount its root.
vi.mock("vaul", () => ({ Drawer: {
  Root: ({ open, children }: any) => <div data-testid="drawer" data-state={open ? "open" : "closed"}>{children}</div>,
  Portal: ({ children }: any) => <>{children}</>,
  Overlay: () => null,
  Content: ({ children }: any) => <div>{children}</div>,
  Handle: () => null,
  Title: ({ children }: any) => <span>{children}</span>,
} }));

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

it("keeps the drawer mounted for Done, close, and Escape, and can reopen", () => {
  stubScreenWidth(390);
  function Example() {
    const [open, setOpen] = useState(true);
    return <>
      <button onClick={() => setOpen(true)}>Open</button>
      <FacetSheet open={open} label="Status" onClose={() => setOpen(false)}
        footer={<SheetDoneButton onClick={() => setOpen(false)}>Done</SheetDoneButton>}>
        Options
      </FacetSheet>
    </>;
  }
  render(<Example />);
  const drawer = screen.getByTestId("drawer");
  for (const dismiss of [
    () => fireEvent.click(screen.getByText("Done")),
    () => fireEvent.click(screen.getByRole("button", { name: "Close" })),
    () => fireEvent.keyDown(document, { key: "Escape" }),
  ]) {
    expect(drawer).toHaveAttribute("data-state", "open");
    dismiss();
    expect(screen.getByTestId("drawer")).toBe(drawer);
    expect(drawer).toHaveAttribute("data-state", "closed");
    fireEvent.click(screen.getByText("Open"));
  }
});
