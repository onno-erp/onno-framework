import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import {
  DEFAULT_PRESETS,
  FALLBACK_RANGE,
  autoGranularityForRange,
  presetById,
  sameRange,
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
 * and default, and persists the selection to localStorage so it survives a reload. The selection is
 * saved per picker scope — the placed widget's `id` config, else its page route — so each board
 * remembers its own window and a short range picked on one never lands on another.
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
   * Let the placed `timeRange` widget supply this dashboard's presets, default, and persistence
   * `scope`. Entering a new scope restores that scope's saved range, or the default when nothing is
   * saved or the saved one is a relative range this ladder no longer offers (an absolute window
   * always survives).
   */
  configure: (opts: { presets?: RangePreset[]; defaultRangeId?: string; scope?: string }) => void;
}

const STORAGE_PREFIX = "onno.dashboard.timeRange:";
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

function load(scope: string): TimeRange | null {
  try {
    const raw = localStorage.getItem(STORAGE_PREFIX + scope);
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

/** Whether `list` can express `range` — any absolute window, or a relative one it lists as a preset. */
function offers(list: RangePreset[], range: TimeRange): boolean {
  return range.kind === "absolute" || list.some((p) => sameRange(p.range, range));
}

/** Two preset lists describe the same picker iff their id sequences match (a preset is its id). */
function samePresetIds(a: RangePreset[], b: RangePreset[]): boolean {
  return a.length === b.length && a.every((p, i) => p.id === b[i].id);
}

export function TimeRangeProvider({ children }: { children: ReactNode }) {
  // The storage scope of the picker currently configuring this provider. Unset until a `timeRange`
  // widget mounts, so a page without a picker never overwrites any board's saved range.
  const scope = useRef<string | null>(null);
  const [range, setRange] = useState<TimeRange>(FALLBACK_RANGE);
  const rangeRef = useRef(range);
  rangeRef.current = range;
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
  const defaultRange = useRef<TimeRange>(FALLBACK_RANGE);

  useEffect(() => {
    if (scope.current === null) return;
    try {
      localStorage.setItem(STORAGE_PREFIX + scope.current, JSON.stringify(range));
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
    (opts: { presets?: RangePreset[]; defaultRangeId?: string; scope?: string }) => {
      const list = opts.presets ?? presetsRef.current;
      if (opts.presets) {
        // Keep the previous array when the ids match so React bails out instead of re-rendering
        // every consumer with a fresh-but-identical list on each configure() call.
        setPresets((prev) => (samePresetIds(prev, opts.presets!) ? prev : opts.presets!));
      }
      const preset = opts.defaultRangeId ? presetById(list, opts.defaultRangeId) : undefined;
      defaultRange.current = preset?.range ?? FALLBACK_RANGE;
      // Entering a picker's scope restores what the user last chose there. A saved relative range
      // this ladder doesn't offer (the author narrowed `presets` since) would apply invisibly —
      // every widget reads its window while the chip renders no value — so it yields to the default.
      // Re-configuring the same scope keeps the live selection unless the new ladder drops it.
      const next = opts.scope ?? "";
      const current = scope.current === next ? rangeRef.current : load(next);
      scope.current = next;
      setRange(current && offers(list, current) ? current : defaultRange.current);
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
