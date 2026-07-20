import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";

import { ActionFormDialog } from "@/components/action-form-dialog";

afterEach(cleanup);

describe("ActionFormDialog nullable selects (#270)", () => {
  it("keeps a clear option for an optional select after a value is chosen", async () => {
    Element.prototype.scrollIntoView = vi.fn();
    const onSubmit = vi.fn();
    render(
      <ActionFormDialog
        title="Run"
        fields={[
          {
            key: "status",
            label: "Status",
            type: "select",
            options: ["Planned", "Done"],
            value: "Planned",
          },
        ]}
        onSubmit={onSubmit}
        onClose={vi.fn()}
      />
    );

    const select = screen.getByRole("combobox");
    const dialogRoot = screen.getByRole("dialog").parentElement;
    fireEvent.keyDown(select, { key: "ArrowDown" });
    const noSelection = await screen.findByRole("option", { name: "No selection" });
    // Nested overlays must portal into the modal subtree. Portalling to document.body makes React
    // Aria's focus trap immediately dismiss the menu in the real browser.
    expect(dialogRoot?.contains(noSelection)).toBe(true);
    fireEvent.click(noSelection);
    fireEvent.click(screen.getByRole("button", { name: "Run" }));

    expect(onSubmit).toHaveBeenCalledWith({ status: "" });
  });

  it("does not offer clearing for a required select with a value", async () => {
    Element.prototype.scrollIntoView = vi.fn();
    render(
      <ActionFormDialog
        title="Run"
        fields={[
          {
            key: "status",
            label: "Status",
            type: "select",
            options: ["Planned", "Done"],
            value: "Planned",
            required: true,
          },
        ]}
        onSubmit={vi.fn()}
        onClose={vi.fn()}
      />
    );

    fireEvent.keyDown(screen.getByRole("combobox"), { key: "ArrowDown" });
    expect(screen.queryByRole("option", { name: "No selection" })).toBeNull();
    expect(await screen.findAllByRole("option")).toHaveLength(2);
  });
});
