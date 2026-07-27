import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { toast } from "@/components/ui/toast";
import { Toaster } from "@/components/ui/toast";
import { presentActionFeedback } from "@/lib/action-feedback";

describe("Base UI toast integration", () => {
  afterEach(async () => {
    await act(async () => {
      toast.dismiss();
    });
  });

  it("renders messages published through the shared toast API", async () => {
    render(<Toaster />);

    await act(async () => {
      toast.success("Saved cleanly");
    });

    expect(await screen.findByText("Saved cleanly")).toBeInTheDocument();
  });

  it("preserves typed title, message, details, and dismiss action hierarchy", async () => {
    render(<Toaster />);

    await act(async () => {
      presentActionFeedback({
        severity: "warning",
        presentation: "toast",
        title: "Stock is running low",
        message: "Two lines need attention.",
        details: ["Review quantities", "Create replenishment"],
        dismissLabel: "Dismiss",
      });
    });

    expect(await screen.findByText("Stock is running low")).toBeInTheDocument();
    expect(screen.getByText("Two lines need attention.")).toBeInTheDocument();
    expect(screen.getByText("Review quantities")).toBeInTheDocument();
    expect(screen.getByText("Create replenishment")).toBeInTheDocument();
    fireEvent.mouseEnter(document.querySelector(".onno-toaster")!);
    expect(screen.getByRole("button", { name: "Dismiss" })).toHaveClass("onno-toast__cancel");
  });
});
