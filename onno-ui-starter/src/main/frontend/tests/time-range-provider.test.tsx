import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { useEffect } from "react";
import { TimeRangeProvider, useTimeRange } from "@/providers/time-range-provider";
import { presetsFromConfig, type TimeRange } from "@/lib/time-range";

const STORAGE_KEY = "onno.dashboard.timeRange";
/** The ladder a business dashboard offers — no sub-month windows (see the Operations overview). */
const LADDER = "30d,90d,6M,1y,all";

afterEach(cleanup);
beforeEach(() => localStorage.clear());

/** Stands in for the placed `timeRange` widget: it configures the board, then shows the selection. */
function Board({ presets = LADDER, defaultId = "90d" }: { presets?: string; defaultId?: string }) {
  const { range, configure } = useTimeRange();
  useEffect(() => {
    configure({ presets: presetsFromConfig(presets), defaultRangeId: defaultId });
  }, [configure, presets, defaultId]);
  return <output>{JSON.stringify(range)}</output>;
}

function mount(saved?: TimeRange) {
  if (saved) localStorage.setItem(STORAGE_KEY, JSON.stringify(saved));
  render(<TimeRangeProvider><Board /></TimeRangeProvider>);
  return () => JSON.parse(screen.getByRole("status").textContent!) as TimeRange;
}

describe("dashboard default vs a persisted range", () => {
  it("applies the dashboard default when nothing is saved", () => {
    const selected = mount();
    expect(selected()).toEqual({ kind: "relative", amount: 90, unit: "d" });
  });

  it("keeps a saved range the dashboard offers", () => {
    const selected = mount({ kind: "relative", amount: 30, unit: "d" });
    expect(selected()).toEqual({ kind: "relative", amount: 30, unit: "d" });
  });

  it("keeps a saved absolute window, which no ladder lists", () => {
    const window = { kind: "absolute", from: "2026-01-01", to: "2026-02-01" } as const;
    const selected = mount(window);
    expect(selected()).toEqual(window);
  });

  // The failure this guards: a "last hour" carried over from another board applied invisibly here —
  // every widget read an empty window while the picker chip rendered no value at all.
  it("falls back to the default when the saved range is not on this dashboard's ladder", () => {
    const selected = mount({ kind: "relative", amount: 24, unit: "h" });
    expect(selected()).toEqual({ kind: "relative", amount: 90, unit: "d" });
  });

  it("persists the fallback, so the next board sees a range it can label", () => {
    mount({ kind: "relative", amount: 24, unit: "h" });
    expect(JSON.parse(localStorage.getItem(STORAGE_KEY)!)).toEqual({ kind: "relative", amount: 90, unit: "d" });
  });
});
