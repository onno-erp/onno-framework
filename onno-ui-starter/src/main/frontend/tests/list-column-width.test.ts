import { describe, expect, it } from "vitest";
import { listColumnPixelWidth } from "../src/components/list-column-width";

describe("list column widths", () => {
  it("accepts only explicit positive whole-pixel widths", () => {
    expect(listColumnPixelWidth("240")).toBe(240);
    expect(listColumnPixelWidth("240px")).toBe(240);
    expect(listColumnPixelWidth(" 240px ")).toBe(240);
  });

  it("does not collapse form-layout fractions into one-pixel table tracks", () => {
    expect(listColumnPixelWidth("1/2")).toBeNull();
    expect(listColumnPixelWidth("half")).toBeNull();
    expect(listColumnPixelWidth("50%")).toBeNull();
    expect(listColumnPixelWidth("1px-not-really")).toBeNull();
    expect(listColumnPixelWidth("0")).toBeNull();
  });
});
