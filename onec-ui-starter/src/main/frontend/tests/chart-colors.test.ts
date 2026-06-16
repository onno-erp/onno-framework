import { describe, expect, it } from "vitest";
import { chartPalette, parseColors, resolveColor, resolveColors, resolveColorToken } from "@/lib/chart-colors";

describe("resolveColorToken", () => {
  it("maps named aliases to theme CSS vars", () => {
    expect(resolveColorToken("primary")).toBe("hsl(var(--primary))");
    expect(resolveColorToken("success")).toBe("hsl(var(--success))");
    expect(resolveColorToken("muted")).toBe("hsl(var(--muted-foreground))");
    // both "destructive" and the friendlier "danger" point at the destructive var
    expect(resolveColorToken("danger")).toBe("hsl(var(--destructive))");
  });

  it("is case-insensitive for aliases and slots", () => {
    expect(resolveColorToken("PRIMARY")).toBe("hsl(var(--primary))");
    expect(resolveColorToken("Chart-2")).toBe("hsl(var(--chart-2))");
  });

  it("maps chart-N slots to palette vars", () => {
    expect(resolveColorToken("chart-1")).toBe("hsl(var(--chart-1))");
    expect(resolveColorToken("chart-8")).toBe("hsl(var(--chart-8))");
  });

  it("passes literal CSS colors through untouched", () => {
    expect(resolveColorToken("#8b5cf6")).toBe("#8b5cf6");
    expect(resolveColorToken("hsl(270 70% 60%)")).toBe("hsl(270 70% 60%)");
    expect(resolveColorToken("rebeccapurple")).toBe("rebeccapurple");
    // chart-9 isn't a real slot — treat it as a (bogus) literal, don't pretend it's a var
    expect(resolveColorToken("chart-9")).toBe("chart-9");
  });
});

describe("parseColors", () => {
  it("splits, trims, and drops empties", () => {
    expect(parseColors(" success , #abc ,, primary ")).toEqual([
      "hsl(var(--success))",
      "#abc",
      "hsl(var(--primary))",
    ]);
  });

  it("returns [] for null/empty", () => {
    expect(parseColors(undefined)).toEqual([]);
    expect(parseColors("")).toEqual([]);
  });
});

describe("resolveColors", () => {
  it("falls back to the palette when there's no override", () => {
    expect(resolveColors(3)).toEqual(chartPalette().slice(0, 3));
  });

  it("applies overrides slot-by-slot, palette fills the rest", () => {
    const palette = chartPalette();
    expect(resolveColors(3, "success,#abc")).toEqual([
      "hsl(var(--success))",
      "#abc",
      palette[2],
    ]);
  });

  it("cycles the palette when count exceeds its length", () => {
    const palette = chartPalette();
    const colors = resolveColors(10);
    expect(colors).toHaveLength(10);
    expect(colors[8]).toBe(palette[0]);
    expect(colors[9]).toBe(palette[1]);
  });
});

describe("resolveColor", () => {
  it("returns the first override token, else the lead palette slot", () => {
    expect(resolveColor("warning")).toBe("hsl(var(--warning))");
    expect(resolveColor()).toBe(chartPalette()[0]);
  });
});
