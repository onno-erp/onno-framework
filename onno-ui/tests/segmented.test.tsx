import { fireEvent, render, screen } from "@testing-library/react";
import { useState } from "react";
import { describe, expect, it } from "vitest";
import { Segmented } from "@/components/ui/segmented";

describe("Segmented", () => {
  it("slides the shared pill to the selected option", () => {
    function Example() {
      const [value, setValue] = useState("all");
      return (
        <Segmented
          value={value}
          onChange={setValue}
          options={[
            { value: "all", label: "All" },
            { value: "mine", label: "Mine" },
          ]}
        />
      );
    }

    const { container } = render(<Example />);
    const mine = screen.getByRole("button", { name: "Mine" });
    Object.defineProperty(mine, "offsetLeft", { configurable: true, value: 42 });
    Object.defineProperty(mine, "offsetWidth", { configurable: true, value: 58 });

    fireEvent.click(mine);

    const pill = container.querySelector<HTMLElement>(".t-tabs-pill");
    expect(pill).not.toBeNull();
    expect(pill).toHaveStyle({ transform: "translateX(42px)", width: "58px" });
    expect(mine).toHaveAttribute("aria-pressed", "true");
  });
});
