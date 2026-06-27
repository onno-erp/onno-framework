import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import type { RangeKey } from "@/lib/widget-data";

/**
 * The dashboard's shared (common) time range — one window every chart reads from, so a single
 * picker drives the whole board (Grafana-style) instead of a per-chart control. Either a relative
 * preset ({@code 7d…all}) or an absolute {@code from}/{@code to} window. Persisted to localStorage so
 * it survives a reload.
 */
export interface TimeRange {
  preset: RangeKey;
  /** Absolute window (ISO {@code yyyy-MM-dd}); when set it overrides the preset. */
  from?: string;
  to?: string;
}

interface TimeRangeContextValue {
  range: TimeRange;
  setPreset: (preset: RangeKey) => void;
  setAbsolute: (from?: string, to?: string) => void;
}

const STORAGE_KEY = "onno.dashboard.timeRange";
const DEFAULT_RANGE: TimeRange = { preset: "90d" };

const TimeRangeContext = createContext<TimeRangeContextValue>({
  range: DEFAULT_RANGE,
  setPreset: () => {},
  setAbsolute: () => {},
});

function load(): TimeRange {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) return JSON.parse(raw) as TimeRange;
  } catch {
    // ignore malformed / unavailable storage
  }
  return DEFAULT_RANGE;
}

export function TimeRangeProvider({ children }: { children: ReactNode }) {
  const [range, setRange] = useState<TimeRange>(load);

  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(range));
    } catch {
      // ignore
    }
  }, [range]);

  const setPreset = useCallback((preset: RangeKey) => setRange({ preset }), []);
  const setAbsolute = useCallback(
    (from?: string, to?: string) =>
      setRange((r) => (from || to ? { preset: r.preset, from, to } : { preset: r.preset })),
    []
  );

  return (
    <TimeRangeContext.Provider value={{ range, setPreset, setAbsolute }}>
      {children}
    </TimeRangeContext.Provider>
  );
}

export const useTimeRange = (): TimeRangeContextValue => useContext(TimeRangeContext);
