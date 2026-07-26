import { act, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { toast } from "sonner";
import { AppToaster } from "@/components/ui/toaster";

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
});
