import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";

import { ActionFormDialog } from "@/components/action-form-dialog";

afterEach(cleanup);

describe("ActionFormDialog nullable selects (#270)", () => {
  it("keeps a clear option for an optional select after a value is chosen", () => {
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
    expect(screen.getByRole("option", { name: "No selection" })).toBeTruthy();
    fireEvent.change(select, { target: { value: "" } });
    fireEvent.click(screen.getByRole("button", { name: "Run" }));

    expect(onSubmit).toHaveBeenCalledWith({ status: "" });
  });

  it("does not offer clearing for a required select with a value", () => {
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

    expect(screen.queryByRole("option", { name: "No selection" })).toBeNull();
    expect(screen.getAllByRole("option")).toHaveLength(2);
  });
});
