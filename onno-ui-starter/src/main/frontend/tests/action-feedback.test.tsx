import { act, cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ActionFeedbackHost, applyActionResult, presentActionFeedback } from "@/lib/action-feedback";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

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

  it.each(["/documents/invoices/new?customer=c-1", "onno://catalogs/customers/c-1"])(
    "opens %s through the shared workspace navigation", (route) => {
      const dispatch = vi.spyOn(window, "dispatchEvent");
      applyActionResult({ navigateTo: route });
      expect(dispatch).toHaveBeenCalledOnce();
      expect(dispatch.mock.calls[0][0]).toMatchObject({ type: "onno:action", detail: route });
    }
  );

  it("applies feedback and refreshes the source before navigating", async () => {
    render(<ActionFeedbackHost />);
    const order: string[] = [];
    const dispatch = window.dispatchEvent.bind(window);
    vi.spyOn(window, "dispatchEvent").mockImplementation((event) => {
      order.push(event.type);
      return dispatch(event);
    });
    act(() => applyActionResult({
      refresh: true,
      feedback: { severity: "success", presentation: "dialog", title: "Invoice created" },
      navigateTo: "/documents/invoices/i-1",
    }, { refresh: () => order.push("refresh") }));
    expect(order).toEqual(["onno:action-feedback", "refresh", "onno:action"]);
    expect(await screen.findByRole("dialog", { name: "Invoice created" })).toBeTruthy();
  });

  it("keeps old responses working without a navigation field", () => {
    const dispatch = vi.spyOn(window, "dispatchEvent");
    const refresh = vi.fn();
    applyActionResult(undefined, { refresh });
    applyActionResult({ refresh: false, feedback: null }, { refresh });
    expect(refresh).not.toHaveBeenCalled();
    applyActionResult({ refresh: true }, { refresh });
    expect(refresh).toHaveBeenCalledOnce();
    expect(dispatch).not.toHaveBeenCalled();
  });

  it("does not turn an unsupported response URL into an external redirect", () => {
    const dispatch = vi.spyOn(window, "dispatchEvent");
    applyActionResult({ navigateTo: "https://example.com" });
    expect(dispatch).not.toHaveBeenCalled();
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
