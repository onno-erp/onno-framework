import { act, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { toast } from "sonner";
import { AppToaster } from "@/components/ui/toaster";
import { presentActionFeedback } from "@/lib/action-feedback";

vi.mock("@/providers/theme-provider", () => ({
  useTheme: () => ({ theme: "dark" }),
}));

describe("AppToaster Sonner integration", () => {
  afterEach(async () => {
    await act(async () => {
      toast.dismiss();
    });
  });

  it("renders messages published through the shared toast API", async () => {
    render(<AppToaster />);

    await act(async () => {
      toast.success("Saved cleanly");
    });

    expect(await screen.findByText("Saved cleanly")).toBeInTheDocument();
  });

  it("preserves typed title, message, details, and dismiss action hierarchy", async () => {
    render(<AppToaster />);

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
    expect(screen.getByRole("button", { name: "Dismiss" })).toBeInTheDocument();
  });
});
