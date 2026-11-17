import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { act, cleanup, render, screen } from "@testing-library/react";
import { useEffect } from "react";
import { TimeRangeProvider, useTimeRange } from "@/providers/time-range-provider";
import { presetsFromConfig, type TimeRange } from "@/lib/time-range";

const key = (scope: string) => `onno.dashboard.timeRange:${scope}`;
/** The ladder a business dashboard offers — no sub-month windows (see the Operations overview). */
const LADDER = "30d,90d,6M,1y,all";
const FINE = "15m,1h,24h,7d,30d";

afterEach(cleanup);
beforeEach(() => localStorage.clear());

interface BoardProps {
  scope: string;
  presets?: string;
  defaultId?: string;
}

/** Stands in for the placed `timeRange` widget: it configures the board, then shows the selection. */
function Board({ scope, presets = LADDER, defaultId = "90d" }: BoardProps) {
  const { range, configure, setPreset } = useTimeRange();
  useEffect(() => {
    configure({ presets: presetsFromConfig(presets), defaultRangeId: defaultId, scope });
  }, [configure, presets, defaultId, scope]);
  return (
    <>
      <output>{JSON.stringify(range)}</output>
      <button onClick={() => setPreset("1h")}>1h</button>
    </>
  );
}

function save(scope: string, range: TimeRange) {
  localStorage.setItem(key(scope), JSON.stringify(range));
}

function mount(board: BoardProps) {
  const view = render(<TimeRangeProvider><Board {...board} /></TimeRangeProvider>);
  return {
    selected: () => JSON.parse(screen.getByRole("status").textContent!) as TimeRange,
    navigate: (next: BoardProps) => view.rerender(<TimeRangeProvider><Board {...next} /></TimeRangeProvider>),
  };
}

describe("per-picker time range persistence", () => {
  it("applies the dashboard default when nothing is saved for its scope", () => {
    const { selected } = mount({ scope: "/ops" });
    expect(selected()).toEqual({ kind: "relative", amount: 90, unit: "d" });
  });

  it("restores the range saved for its scope", () => {
    save("/ops", { kind: "relative", amount: 30, unit: "d" });
    const { selected } = mount({ scope: "/ops" });
    expect(selected()).toEqual({ kind: "relative", amount: 30, unit: "d" });
  });

  it("keeps a saved absolute window, which no ladder lists", () => {
    const window = { kind: "absolute", from: "2026-01-01", to: "2026-02-01" } as const;
    save("/ops", window);
    const { selected } = mount({ scope: "/ops" });
    expect(selected()).toEqual(window);
  });

  it("falls back to the default when the saved range left this ladder", () => {
    save("/ops", { kind: "relative", amount: 24, unit: "h" });
    const { selected } = mount({ scope: "/ops" });
    expect(selected()).toEqual({ kind: "relative", amount: 90, unit: "d" });
  });

  // The failure this guards: a "last hour" picked on a fine-grained board carried over to a
  // 30d–1y business board, where it applied invisibly — every tile read 0 and the chip was blank.
  it("does not carry a selection from one board to another", () => {
    const { selected, navigate } = mount({ scope: "/logs", presets: FINE, defaultId: "24h" });
    act(() => screen.getByRole("button", { name: "1h" }).click());
    expect(selected()).toEqual({ kind: "relative", amount: 1, unit: "h" });

    navigate({ scope: "/ops" });
    expect(selected()).toEqual({ kind: "relative", amount: 90, unit: "d" });

    navigate({ scope: "/logs", presets: FINE, defaultId: "24h" });
    expect(selected()).toEqual({ kind: "relative", amount: 1, unit: "h" });
    expect(JSON.parse(localStorage.getItem(key("/logs"))!)).toEqual({ kind: "relative", amount: 1, unit: "h" });
    expect(JSON.parse(localStorage.getItem(key("/ops"))!)).toEqual({ kind: "relative", amount: 90, unit: "d" });
  });

  it("shares a selection between pickers that name the same scope", () => {
    save("sales", { kind: "relative", amount: 1, unit: "y" });
    const { selected, navigate } = mount({ scope: "sales" });
    navigate({ scope: "sales", defaultId: "30d" });
    expect(selected()).toEqual({ kind: "relative", amount: 1, unit: "y" });
  });
});
