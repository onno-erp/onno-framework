import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";

import { CommandFormDialog } from "@/components/actions/command-form-dialog";
import { ApiError } from "@/lib/api";
import { toast } from "@/components/ui/toast";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("CommandFormDialog business rejection (#273)", () => {
  it("retains entered values and renders form and field errors", async () => {
    const onClose = vi.fn();
    const onSubmit = vi.fn().mockRejectedValue(
      new ApiError("Approval blocked", 422, undefined, {
        severity: "error",
        title: "Approval blocked",
        message: "The room is occupied",
        formErrors: ["A justification cannot override a hard conflict"],
        fieldErrors: { reason: ["Only soft conflicts may be justified"] },
      })
    );

    render(
      <CommandFormDialog
        title="Approve"
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
      <CommandFormDialog
        title="Run report"
        description="Choose report options"
        fields={[]}
        onSubmit={vi.fn()}
        onClose={vi.fn()}
      />
    );

    const dialog = screen.getByRole("dialog", { name: "Run report" });
    expect(dialog).toHaveAccessibleDescription("Choose report options");
  });
});

describe("CommandFormDialog failure without typed feedback", () => {
  const submitWith = (onSubmit: () => Promise<void>, onClose = vi.fn()) => {
    render(
      <CommandFormDialog
        title="Approve"
        fields={[{ key: "reason", label: "Justification", type: "text" }]}
        onSubmit={onSubmit}
        onClose={onClose}
      />
    );
    const input = screen.getByRole("textbox", { name: "Justification" });
    fireEvent.change(input, { target: { value: "Schedule exception" } });
    fireEvent.click(screen.getByRole("button", { name: "Approve" }));
    return input;
  };

  it("reports a network failure once and keeps the form open with the user's values", async () => {
    const toastError = vi.spyOn(toast, "error");
    const onClose = vi.fn();
    const input = submitWith(vi.fn().mockRejectedValue(new TypeError("Failed to fetch")), onClose);

    await waitFor(() => expect(toastError).toHaveBeenCalled());
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(toastError.mock.calls).toEqual([["Failed to fetch"]]);
    expect(input).toHaveValue("Schedule exception");
    expect(onClose).not.toHaveBeenCalled();
  });

  it("adds no toast to a failure the api layer has shown", async () => {
    const toastError = vi.spyOn(toast, "error");
    const shown = new ApiError("Database unavailable", 500);
    shown.notified = true;
    const onSubmit = vi.fn().mockRejectedValue(shown);
    submitWith(onSubmit);

    await waitFor(() => expect(onSubmit).toHaveBeenCalled());
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(toastError).not.toHaveBeenCalled();
  });
});
