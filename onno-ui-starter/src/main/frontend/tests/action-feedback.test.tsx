import { act, cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ActionFeedbackHost, applyActionResult, presentActionFeedback } from "@/lib/action-feedback";

afterEach(cleanup);

describe("typed action feedback (#273)", () => {
  it("shows successful acknowledgement dialogs from ActionResult", async () => {
    render(<ActionFeedbackHost />);

    act(() => {
      applyActionResult({
        feedback: {
          severity: "success",
          presentation: "dialog",
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

  it("falls back to an accessible dialog when inline feedback has no open form", async () => {
    render(<ActionFeedbackHost />);

    act(() => {
      presentActionFeedback({
        severity: "error",
        presentation: "inline",
        title: "Approval blocked",
        message: "The room is occupied",
      });
    });

    expect(await screen.findByRole("alertdialog", { name: "Approval blocked" })).toBeTruthy();
  });
});

describe("ActionResult refresh (#447)", () => {
  it("calls refresh when the server requests it", () => {
    const refresh = vi.fn();

    applyActionResult({ refresh: true }, { refresh });

    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it("does not refresh when the server does not request it", () => {
    const refresh = vi.fn();

    applyActionResult({ refresh: false }, { refresh });

    expect(refresh).not.toHaveBeenCalled();
  });
});
