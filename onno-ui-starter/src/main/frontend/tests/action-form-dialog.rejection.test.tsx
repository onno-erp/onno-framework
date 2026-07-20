import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";

import { ActionFormDialog } from "@/components/action-form-dialog";
import { ApiError } from "@/lib/api";

afterEach(cleanup);

describe("ActionFormDialog business rejection (#273)", () => {
  it("retains entered values and renders form and field errors", async () => {
    const onClose = vi.fn();
    const onSubmit = vi.fn().mockRejectedValue(
      new ApiError("Approval blocked", 422, undefined, {
        severity: "error",
        presentation: "inline",
        title: "Approval blocked",
        message: "The room is occupied",
        formErrors: ["A justification cannot override a hard conflict"],
        fieldErrors: { reason: ["Only soft conflicts may be justified"] },
        keepFormOpen: true,
      })
    );

    render(
      <ActionFormDialog
        title="Approve"
        dialog={{ title: "Approve event", submitLabel: "Approve", cancelLabel: "Back", tone: "warning" }}
        fields={[{ key: "reason", label: "Justification", type: "text" }]}
        onSubmit={onSubmit}
        onClose={onClose}
      />
    );

    const input = screen.getByRole("textbox", { name: "Justification" });
    fireEvent.change(input, { target: { value: "Schedule exception" } });
    fireEvent.click(screen.getByRole("button", { name: "Approve" }));

    await screen.findByText("The room is occupied");
    expect(screen.getByText("A justification cannot override a hard conflict")).toBeTruthy();
    expect(screen.getByText("Only soft conflicts may be justified")).toBeTruthy();
    expect(input).toHaveValue("Schedule exception");
    expect(onClose).not.toHaveBeenCalled();
    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith({ reason: "Schedule exception" }));
  });

  it("exposes accessible dialog labelling", () => {
    render(
      <ActionFormDialog
        title="Run"
        dialog={{ title: "Run report", description: "Choose report options" }}
        fields={[]}
        onSubmit={vi.fn()}
        onClose={vi.fn()}
      />
    );

    const dialog = screen.getByRole("dialog", { name: "Run report" });
    expect(dialog).toHaveAccessibleDescription("Choose report options");
  });
});
