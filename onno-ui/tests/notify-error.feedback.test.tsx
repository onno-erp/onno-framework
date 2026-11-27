import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

// notifyError outside a command surface: typed feedback keeps its title and kind, and a failure is
// never shown twice. Only the toast sink is stubbed.
const toast = vi.hoisted(() => ({
  error: vi.fn(),
  success: vi.fn(),
  info: vi.fn(),
  warning: vi.fn(),
  dismiss: vi.fn(),
}));
vi.mock("@/components/ui/toast", () => ({ toast }));

import { ApiError } from "@/lib/api";
import { notifyError } from "@/lib/notify-error";
import { claimFeedback } from "@/lib/feedback-toast";
import type { CommandFeedback } from "@/lib/types";

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

const rejected = (feedback: CommandFeedback) => new ApiError(feedback.message ?? "rejected", 422, undefined, feedback);

/** The text of a toast's description, as the toaster would render it. */
function descriptionText(options: { description?: unknown } | undefined): string {
  render(<div data-testid="description">{options?.description as never}</div>);
  return screen.getByTestId("description").textContent ?? "";
}

describe("notifyError with typed feedback", () => {
  it("shows the title as the toast and the message under it, not the bare message", () => {
    notifyError(rejected({ severity: "error", title: "Record locked", message: "Locked by an import" }));

    expect(toast.error).toHaveBeenCalledOnce();
    const [title, options] = toast.error.mock.calls[0];
    expect(title).toBe("Record locked");
    expect(descriptionText(options)).toBe("Locked by an import");
  });

  it("keeps the feedback's kind", () => {
    notifyError(rejected({ severity: "warning", title: "Stock is low", message: "Reorder soon" }));

    expect(toast.error).not.toHaveBeenCalled();
    expect(toast.warning).toHaveBeenCalledOnce();
    expect(toast.warning.mock.calls[0][0]).toBe("Stock is low");
  });

  it("lists the details under the message", () => {
    notifyError(rejected({
      severity: "error",
      title: "Import blocked",
      message: "Two rows are invalid",
      details: ["Row 3: no price", "Row 7: no code"],
    }));

    expect(descriptionText(toast.error.mock.calls[0][1])).toBe("Two rows are invalidRow 3: no priceRow 7: no code");
  });

  it("a rejection that names only input errors is headed by the first, the rest listed under it", () => {
    // CommandRejectedException.builder().formError(…).fieldError(…).build(): no title, no message.
    notifyError(rejected({
      severity: "error",
      formErrors: ["Pick a warehouse"],
      fieldErrors: { quantity: ["Must be positive"] },
    }));

    const [title, options] = toast.error.mock.calls[0];
    expect(title).toBe("Pick a warehouse");
    expect(descriptionText(options)).toBe("Must be positive");
  });

  it("lists input errors under a titled rejection", () => {
    notifyError(rejected({
      severity: "error",
      title: "Cannot ship",
      fieldErrors: { quantity: ["Must be positive"] },
    }));

    const [title, options] = toast.error.mock.calls[0];
    expect(title).toBe("Cannot ship");
    expect(descriptionText(options)).toBe("Must be positive");
  });

  it("without a title, the message is the toast", () => {
    notifyError(rejected({ severity: "error", message: "The period is closed" }));

    expect(toast.error.mock.calls).toEqual([["The period is closed", { description: undefined }]]);
  });

  it("the server's wording stands: the caller's format and fallback do not apply", () => {
    notifyError(rejected({ severity: "error", title: "Record locked", message: "Locked by an import" }),
      { format: (m) => `Couldn't save: ${m}`, fallback: "Save failed" });

    expect(toast.error.mock.calls[0][0]).toBe("Record locked");
  });

  it("does not show feedback again that was already shown, even through another error", () => {
    const feedback: CommandFeedback = { severity: "error", title: "Record locked", message: "Locked by an import" };
    claimFeedback(feedback); // shown elsewhere first — a dialog, an open form, a toast

    notifyError(rejected(feedback));

    expect(toast.error).not.toHaveBeenCalled();
  });

  it("reports the same failure once", () => {
    const error = rejected({ severity: "error", title: "Record locked", message: "Locked by an import" });

    notifyError(error);
    notifyError(error);

    expect(toast.error).toHaveBeenCalledOnce();
    expect(error.notified).toBe(true);
  });
});

describe("notifyError with a lapsed session", () => {
  it("shows nothing: the auth recovery takes over", () => {
    notifyError(new ApiError("unauthenticated", 401), { fallback: "Could not claim task" });
    notifyError(new ApiError("unauthenticated", 401), { format: (m) => `Couldn't save: ${m}` });
    notifyError(new ApiError("Signed out", 401, undefined, { severity: "error", title: "Signed out" }));

    expect(toast.error).not.toHaveBeenCalled();
  });
});
