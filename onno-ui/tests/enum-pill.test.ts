import { describe, expect, it } from "vitest";
import { enumPillStyle } from "@/lib/utils";

describe("enumPillStyle", () => {
  it("returns null for empty/missing input (falls back to plain text)", () => {
    expect(enumPillStyle(undefined)).toBeNull();
    expect(enumPillStyle(null)).toBeNull();
    expect(enumPillStyle("")).toBeNull();
    expect(enumPillStyle("not-a-color")).toBeNull();
  });

  it("keeps a light background's text near-black", () => {
    // #F4C7C3 (light salmon, the «Новый» pill) is bright → dark text.
    expect(enumPillStyle("#F4C7C3")).toEqual({ backgroundColor: "#f4c7c3", color: "#1f2937" });
  });

  it("flips a dark background's text to white", () => {
    // #1155CC (strong blue, «Скачивается») is dark → white text.
    expect(enumPillStyle("#1155CC")).toEqual({ backgroundColor: "#1155cc", color: "#ffffff" });
  });

  it("accepts 3-digit hex and an optional leading #", () => {
    expect(enumPillStyle("#fff")).toEqual({ backgroundColor: "#ffffff", color: "#1f2937" });
    expect(enumPillStyle("000")).toEqual({ backgroundColor: "#000000", color: "#ffffff" });
  });
});
