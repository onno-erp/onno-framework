import { act, cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";

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
