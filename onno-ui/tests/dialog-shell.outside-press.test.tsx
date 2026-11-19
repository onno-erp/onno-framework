import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";

import { DialogShell } from "@/components/ui/dialog-shell";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { stubScreenWidth } from "./viewport-fixtures";

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
beforeEach(() => { Element.prototype.scrollIntoView = vi.fn(); });

/** A press that starts and ends outside the dialog, as React Aria and Radix both listen for it. */
function pressOutside(target: Element = document.body, pointerType = "mouse") {
  fireEvent.pointerDown(target, { button: 0, pointerType });
  fireEvent.mouseDown(target, { button: 0 });
  fireEvent.pointerUp(target, { button: 0, pointerType });
  fireEvent.mouseUp(target, { button: 0 });
  fireEvent.click(target, { button: 0 });
}

function renderDialog(onOpenChange: (open: boolean) => void) {
  render(
    <DialogShell title="Add" onOpenChange={onOpenChange}>
      <Select defaultValue="a">
        <SelectTrigger aria-label="Type"><SelectValue /></SelectTrigger>
        <SelectContent>
          <SelectItem value="a">Ceremony</SelectItem>
          <SelectItem value="b">Reception</SelectItem>
        </SelectContent>
      </Select>
    </DialogShell>
  );
}

async function openSelect() {
  fireEvent.keyDown(screen.getByRole("combobox", { name: "Type" }), { key: "ArrowDown" });
  await screen.findByRole("option", { name: "Reception" });
}

describe("DialogShell outside presses", () => {
  it("closes an open select first and the dialog only on the next press", async () => {
    const onOpenChange = vi.fn();
    renderDialog(onOpenChange);
    await openSelect();

    pressOutside();
    expect(onOpenChange).not.toHaveBeenCalled();

    // Once nothing is open inside the dialog, a press outside dismisses it as before.
    const still = screen.queryByRole("option", { name: "Reception" });
    if (still) fireEvent.click(still);
    await waitFor(() => expect(screen.queryByRole("listbox")).toBeNull());
    pressOutside();
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it("keeps the dialog when a tap on it lands on <html> under an open select", async () => {
    // An open Radix Select sets pointer-events: none on the body, so a tap anywhere, the dialog
    // included, hits the root element; Radix closes on touch only at the click.
    const onOpenChange = vi.fn();
    renderDialog(onOpenChange);
    await openSelect();

    pressOutside(document.documentElement, "touch");
    expect(onOpenChange).not.toHaveBeenCalled();
  });

  it("opens a select inside a dialog at tablet width", async () => {
    stubScreenWidth(900);
    // The dialog's own close button, plus the select's backdrop while the select is open.
    const closeButtons = () => screen.queryAllByRole("button", { name: "Close", hidden: true }).length;
    const onOpenChange = vi.fn();
    renderDialog(onOpenChange);
    expect(closeButtons()).toBe(1);
    await openSelect();

    // The open select hides its siblings from assistive tech, so look the dialog up in the DOM.
    expect(document.querySelector('[role="dialog"]')).toHaveTextContent("Add");
    await waitFor(() => expect(closeButtons()).toBe(2));
    pressOutside(document.documentElement);
    expect(onOpenChange).not.toHaveBeenCalled();

    // Closed again, no backdrop is left over the dialog.
    await waitFor(() => expect(screen.queryByRole("listbox", { hidden: true })).toBeNull());
    expect(closeButtons()).toBe(1);
  });
});
