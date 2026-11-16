import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { AnimatedNumber } from "@/components/ui/animated-number";

describe("AnimatedNumber", () => {
  it("renders one animated character per formatted glyph and staggers the final two", () => {
    const { container } = render(<AnimatedNumber value="$42" />);

    expect(screen.getByLabelText("$42")).toHaveClass("t-digit-group");
    const digits = container.querySelectorAll<HTMLElement>(".t-digit");
    expect(Array.from(digits, (digit) => digit.textContent).join("")).toBe("$42");
    expect(digits[1]).toHaveAttribute("data-stagger", "1");
    expect(digits[2]).toHaveAttribute("data-stagger", "2");
  });
});
