import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { ColorPicker, normalizeHexColor } from "@/components/color-picker";

afterEach(cleanup);

describe("ColorPicker", () => {
  it("normalizes short and unprefixed hex colors", () => {
    expect(normalizeHexColor("abc")).toBe("#aabbcc");
    expect(normalizeHexColor("A1B2C3")).toBe("#a1b2c3");
    expect(normalizeHexColor("not-a-color")).toBe("not-a-color");
  });

  it("renders the stored color and exposes the visual picker", () => {
    const onChange = vi.fn();
    render(<ColorPicker value="#12ab34" onChange={onChange} />);

    expect(screen.getByRole("textbox", { name: "Hex color" })).toHaveValue("#12ab34");
    expect(screen.getByRole("button", { name: "Choose color" }).firstElementChild).toHaveStyle({
      backgroundColor: "#12ab34",
    });

    fireEvent.click(screen.getByRole("button", { name: "Choose color" }));
    expect(document.querySelector(".react-colorful")).toBeInTheDocument();
  });
});
