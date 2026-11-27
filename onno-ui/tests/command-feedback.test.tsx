import { act, cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { CommandFeedbackProvider, feedbackNeedsDialog, useCommandFeedback, type CommandFeedbackHandlers } from "@/lib/command-feedback";
import { ApiError } from "@/lib/api";
import { toast } from "@/components/ui/toast";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

/** Renders the provider and hands back the handlers a component inside it gets. */
function renderProvider(): CommandFeedbackHandlers {
  let handlers: CommandFeedbackHandlers | undefined;
  function Probe() {
    handlers = useCommandFeedback();
    return null;
  }
  render(<CommandFeedbackProvider><Probe /></CommandFeedbackProvider>);
  return handlers!;
}

describe("typed command feedback (#273)", () => {
  it("shows successful acknowledgement dialogs from CommandResult", async () => {
    const feedback = renderProvider();

    act(() => {
      feedback.applyResult({
        feedback: {
          severity: "success",
          title: "Import completed",
          message: "124 records were imported",
          details: ["3 rows were skipped"],
        },
      });
    });

    expect(await screen.findByRole("dialog", { name: "Import completed" })).toBeTruthy();
    expect(screen.getByText("124 records were imported")).toBeTruthy();
    expect(screen.getByText("3 rows were skipped")).toBeTruthy();
  });

  it("decides display from meaning: errors and details need a dialog, short news a toast", () => {
    expect(feedbackNeedsDialog({ severity: "success", message: "Saved" })).toBe(false);
    expect(feedbackNeedsDialog({ severity: "warning", title: "Low stock", message: "Reorder soon" })).toBe(false);
    expect(feedbackNeedsDialog({ severity: "error", message: "Carrier rejected the hand-off" })).toBe(true);
    expect(feedbackNeedsDialog({ severity: "info", message: "Imported", details: ["3 skipped"] })).toBe(true);
    expect(feedbackNeedsDialog({ severity: "warning", fieldErrors: { reason: ["Required"] } })).toBe(true);
  });

  it("shows an error as an accessible alert dialog", async () => {
    const feedback = renderProvider();

    act(() => {
      feedback.present({
        severity: "error",
        title: "Approval blocked",
        message: "The room is occupied",
      });
    });

    expect(await screen.findByRole("alertdialog", { name: "Approval blocked" })).toBeTruthy();
  });
});

describe("CommandResult refresh (#447)", () => {
  it("calls refresh when the server requests it", () => {
    const refresh = vi.fn();

    renderProvider().applyResult({ refresh: true }, { refresh });

    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it("does not refresh when the server does not request it", () => {
    const refresh = vi.fn();

    renderProvider().applyResult({ refresh: false }, { refresh });

    expect(refresh).not.toHaveBeenCalled();
  });
});

describe("CommandResult navigation (#449)", () => {
  it.each(["/documents/invoices/new?customer=c-1", "onno://catalogs/customers/c-1"])(
    "opens %s through the shared workspace navigation", (route) => {
      const feedback = renderProvider();
      const dispatch = vi.spyOn(window, "dispatchEvent");
      feedback.applyResult({ navigateTo: route });
      expect(dispatch).toHaveBeenCalledOnce();
      expect(dispatch.mock.calls[0][0]).toMatchObject({ type: "onno:open", detail: route });
    }
  );

  it("refreshes the source before navigating", () => {
    const feedback = renderProvider();
    const order: string[] = [];
    const dispatch = window.dispatchEvent.bind(window);
    vi.spyOn(window, "dispatchEvent").mockImplementation((event) => {
      order.push(event.type);
      return dispatch(event);
    });
    act(() => feedback.applyResult({ refresh: true, navigateTo: "/documents/invoices/i-1" },
      { refresh: () => order.push("refresh") }));
    expect(order).toEqual(["refresh", "onno:open"]);
  });

  it.each([
    "https://example.com",
    "onno://redirect/https://evil.example",
    "onno://open/https://evil.example",
    "onno://logout",
    "onno://delete/documents/invoices/i-1",
    "onno://action/documents/invoices/approve/i-1",
  ])("refuses the command or external URL %s from a server result", (route) => {
    const feedback = renderProvider();
    const dispatch = vi.spyOn(window, "dispatchEvent");
    feedback.applyResult({ navigateTo: route });
    expect(dispatch).not.toHaveBeenCalled();
  });
});

describe("a failed command is reported once", () => {
  it("presents typed feedback like a command result, not as a toast", async () => {
    const toastError = vi.spyOn(toast, "error");
    const feedback = renderProvider();
    const error = new ApiError("Approval blocked", 422, undefined, {
      severity: "error",
      title: "Approval blocked",
      message: "The room is occupied",
    });

    act(() => feedback.notifyError(error));

    expect(await screen.findByRole("alertdialog", { name: "Approval blocked" })).toBeTruthy();
    expect(error.notified).toBe(true);
    expect(toastError).not.toHaveBeenCalled();
  });

  it("toasts a failure that carries no typed feedback", () => {
    const toastError = vi.spyOn(toast, "error");

    renderProvider().notifyError(new TypeError("Failed to fetch"));

    expect(toastError.mock.calls).toEqual([["Failed to fetch"]]);
  });

  it("outside a provider presents typed feedback as one toast, once", () => {
    const toastError = vi.spyOn(toast, "error");
    let handlers: CommandFeedbackHandlers | undefined;
    function Probe() {
      handlers = useCommandFeedback();
      return null;
    }
    render(<Probe />);
    const error = new ApiError("Approval blocked", 422, undefined, {
      severity: "error",
      title: "Approval blocked",
      message: "The room is occupied",
    });

    handlers!.notifyError(error);
    handlers!.notifyError(error);

    expect(toastError).toHaveBeenCalledOnce();
    expect(toastError.mock.calls[0][0]).toBe("Approval blocked");
    expect(error.notified).toBe(true);
  });

  it("reports the same failure only once when it is reported twice", () => {
    const toastError = vi.spyOn(toast, "error");
    const feedback = renderProvider();
    const error = new ApiError("Database unavailable", 500);

    feedback.notifyError(error);
    feedback.notifyError(error);

    expect(toastError.mock.calls).toEqual([["Database unavailable"]]);
  });

  it("adds nothing to a failure the api layer has shown", () => {
    const toastError = vi.spyOn(toast, "error");
    const error = new ApiError("Database unavailable", 500);
    error.notified = true;

    renderProvider().notifyError(error);

    expect(toastError).not.toHaveBeenCalled();
  });
});
