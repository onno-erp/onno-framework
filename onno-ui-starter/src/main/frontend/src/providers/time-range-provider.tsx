import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import {
  DEFAULT_PRESETS,
  FALLBACK_RANGE,
  autoGranularityForRange,
  presetById,
  type RangePreset,
  type TimeGranularity,
  type TimeGranularityMode,
  type TimeRange,
} from "@/lib/time-range";

/**
 * The dashboard's shared (common) time range — one window every chart reads from, so a single picker
 * drives the whole board (Grafana-style) instead of a per-chart control. The range model itself lives
 * in {@link import("@/lib/time-range") time-range} (a relative "last N", an absolute from/to, or all-
 * time); this provider just holds the selected window plus the dashboard's configurable preset list
 * and default, and persists the selection to localStorage so it survives a reload.
 */
interface TimeRangeContextValue {
  range: TimeRange;
  /** Shared override for auto-bucketed time charts; fixed authored buckets remain fixed. */
  granularity: TimeGranularityMode;
  /** The concrete bucket represented by `auto` for the current selected period. */
  resolvedGranularity: TimeGranularity;
  /** The quick-picks the picker renders — defaults until a dashboard {@link configure}s its own. */
  presets: RangePreset[];
  setRange: (range: TimeRange) => void;
  setGranularity: (granularity: TimeGranularityMode) => void;
  /** Apply a preset by id (no-op if the id isn't in the current list). */
  setPreset: (id: string) => void;
  /** Set an absolute window; clearing both bounds reverts to the configured default. */
  setAbsolute: (from?: string, to?: string) => void;
  /**
   * Let the placed `timeRange` widget supply this dashboard's presets and default. The default is
   * applied only when the user hasn't already chosen a range (nothing persisted), so a saved
   * selection always wins.
   */
  configure: (opts: { presets?: RangePreset[]; defaultRangeId?: string }) => void;
}

const STORAGE_KEY = "onno.dashboard.timeRange";
const GRANULARITY_STORAGE_KEY = "onno.dashboard.granularity";

const TimeRangeContext = createContext<TimeRangeContextValue>({
  range: FALLBACK_RANGE,
  granularity: "auto",
  resolvedGranularity: autoGranularityForRange(FALLBACK_RANGE),
  presets: DEFAULT_PRESETS,
  setRange: () => {},
  setGranularity: () => {},
  setPreset: () => {},
  setAbsolute: () => {},
  configure: () => {},
});

function load(): TimeRange | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) return JSON.parse(raw) as TimeRange;
  } catch {
    // ignore malformed / unavailable storage
  }
  return null;
}

function loadGranularity(): TimeGranularityMode {
  try {
    const value = localStorage.getItem(GRANULARITY_STORAGE_KEY);
    if (["auto", "minute", "hour", "day", "week", "month"].includes(value ?? "")) {
      return value as TimeGranularityMode;
    }
  } catch {
    // ignore malformed / unavailable storage
  }
  return "auto";
}

/** Two preset lists describe the same picker iff their id sequences match (a preset is its id). */
function samePresetIds(a: RangePreset[], b: RangePreset[]): boolean {
  return a.length === b.length && a.every((p, i) => p.id === b[i].id);
}

export function TimeRangeProvider({ children }: { children: ReactNode }) {
  // Whether the user has a persisted selection — gates whether a dashboard's default applies.
  const persisted = useRef<TimeRange | null>(load());
  const [range, setRange] = useState<TimeRange>(persisted.current ?? FALLBACK_RANGE);
  const [granularity, setGranularity] = useState<TimeGranularityMode>(loadGranularity);
  const [presets, setPresets] = useState<RangePreset[]>(DEFAULT_PRESETS);
  // Mirror of `presets` so the callbacks below can read the current list without depending on it —
  // their identities must stay stable across re-renders. The `timeRange` widget calls configure()
  // from an effect keyed on `configure`; a presets-dependent identity re-armed that effect after its
  // own setPresets, which re-called configure → an infinite render loop that pegged the dashboard
  // and starved React's transition lane (react-router v7 navigations never committed — the mobile
  // bottom bar highlighted taps but the page never changed).
  const presetsRef = useRef(presets);
  presetsRef.current = presets;
  // The "clear custom" / configured-default target, separate from the live selection.
  const defaultRange = useRef<TimeRange>(persisted.current ?? FALLBACK_RANGE);

  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(range));
    } catch {
      // ignore
    }
  }, [range]);

  useEffect(() => {
    try {
      localStorage.setItem(GRANULARITY_STORAGE_KEY, granularity);
    } catch {
      // ignore
    }
  }, [granularity]);

  const resolvedGranularity = useMemo(() => autoGranularityForRange(range), [range]);

  const setPreset = useCallback((id: string) => {
    const p = presetById(presetsRef.current, id);
    if (p) setRange(p.range);
  }, []);

  const setAbsolute = useCallback(
    (from?: string, to?: string) => setRange(from || to ? { kind: "absolute", from, to } : defaultRange.current),
    []
  );

  const configure = useCallback(
    (opts: { presets?: RangePreset[]; defaultRangeId?: string }) => {
      const list = opts.presets ?? presetsRef.current;
      if (opts.presets) {
        // Keep the previous array when the ids match so React bails out instead of re-rendering
        // every consumer with a fresh-but-identical list on each configure() call.
        setPresets((prev) => (samePresetIds(prev, opts.presets!) ? prev : opts.presets!));
      }
      if (opts.defaultRangeId) {
        const p = presetById(list, opts.defaultRangeId);
        if (p) {
          defaultRange.current = p.range;
          if (!persisted.current) setRange(p.range); // only when the user hasn't chosen
        }
      }
    },
    []
  );

  const value = useMemo(
    () => ({
      range,
      granularity,
      resolvedGranularity,
      presets,
      setRange,
      setGranularity,
      setPreset,
      setAbsolute,
      configure,
    }),
    [range, granularity, resolvedGranularity, presets, setPreset, setAbsolute, configure]
  );
  return <TimeRangeContext.Provider value={value}>{children}</TimeRangeContext.Provider>;
}

export const useTimeRange = (): TimeRangeContextValue => useContext(TimeRangeContext);
