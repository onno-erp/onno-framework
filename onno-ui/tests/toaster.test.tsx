import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Toaster } from "@/components/ui/toast";

describe("Toaster", () => {
  it("mounts the canonical Base UI viewport", () => {
    render(<Toaster />);

    const viewport = document.querySelector(".onno-toaster");
    expect(viewport).toBeInTheDocument();
    expect(viewport).not.toHaveAttribute("data-expanded");
  });
});
