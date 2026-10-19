import { describe, expect, it } from "vitest";
import { chartSpanDays, dataSpanDays } from "@/components/chart-widget";
import { autoGranularityForSpan } from "@/lib/time-range";

const min = Date.parse("2026-01-01T10:00:00Z");
const max = Date.parse("2026-01-01T11:00:00Z");

describe("chart data span for automatic buckets", () => {
  it("uses elapsed days for a one-hour aggregate span and selects minute buckets", () => {
    const days = chartSpanDays(min, max);
    expect(days).toBeCloseTo(1 / 24);
    expect(autoGranularityForSpan(days)).toBe("minute");
  });

  it("uses elapsed days for register rows, ignoring invalid dates and row order", () => {
    const days = dataSpanDays([
      { date: "2026-01-01T11:00:00Z" },
      { date: "invalid" },
      { date: "2026-01-01T10:00:00Z" },
    ], "date");
    expect(days).toBeCloseTo(1 / 24);
    expect(autoGranularityForSpan(days)).toBe("minute");
  });

  it("preserves the one-day fallback and hourly buckets for zero-length spans", () => {
    expect(chartSpanDays(min, min)).toBe(1);
    expect(dataSpanDays([{ date: "2026-01-01T10:00:00Z" }], "date")).toBe(1);
    expect(autoGranularityForSpan(chartSpanDays(min, min))).toBe("hour");
  });

  it("preserves the one-day fallback for empty or invalid rows", () => {
    expect(dataSpanDays([], "date")).toBe(1);
    expect(dataSpanDays([{ date: "invalid" }, {}], "date")).toBe(1);
  });

  it("does not add an inclusive day to longer spans", () => {
    const days = chartSpanDays(min, min + 86_400_000);
    expect(days).toBe(1);
    expect(autoGranularityForSpan(days)).toBe("hour");
  });
});
