import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { HintIcon } from "@/components/ui/hint-icon";

afterEach(cleanup);

describe("HintIcon", () => {
  it("renders nothing when there is no hint text", () => {
    const { container } = render(<HintIcon text={undefined} />);
    expect(container).toBeEmptyDOMElement();
    expect(screen.queryByRole("button")).toBeNull();
  });

  it("renders nothing for blank/whitespace-only text", () => {
    const { container } = render(<HintIcon text="   " />);
    expect(container).toBeEmptyDOMElement();
  });

  it("renders a help affordance carrying the hint as its accessible name", () => {
    render(<HintIcon text="Stock-keeping unit; unique per product." />);
    // The text is exposed via aria-label so it's reachable without opening the tooltip.
    const trigger = screen.getByRole("button", {
      name: "Stock-keeping unit; unique per product.",
    });
    expect(trigger).toBeInTheDocument();
    // It's a help affordance, not a form submit button.
    expect(trigger).toHaveAttribute("type", "button");
  });
});
