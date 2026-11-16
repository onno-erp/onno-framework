import { act, cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { CommandFeedbackProvider, feedbackNeedsDialog, useCommandFeedback, type CommandFeedbackHandlers } from "@/lib/command-feedback";

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
