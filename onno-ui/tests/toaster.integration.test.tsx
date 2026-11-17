import { act, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { toast } from "@/components/ui/toast";
import { Toaster } from "@/components/ui/toast";
import { CommandFeedbackProvider, useCommandFeedback } from "@/lib/command-feedback";

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

  it("shows short feedback as a toast: title over message", async () => {
    let present: ReturnType<typeof useCommandFeedback>["present"] = () => {};
    function Probe() {
      present = useCommandFeedback().present;
      return null;
    }
    render(<><Toaster /><CommandFeedbackProvider><Probe /></CommandFeedbackProvider></>);

    await act(async () => {
      present({
        severity: "warning",
        title: "Stock is running low",
        message: "Two lines need attention.",
      });
    });

    expect(await screen.findByText("Stock is running low")).toBeInTheDocument();
    expect(screen.getByText("Two lines need attention.")).toBeInTheDocument();
  });
});
