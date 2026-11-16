import { useEffect, useMemo, useRef } from "react";

/** Invalidates responses on scope changes, replacement reads and unmount, including before effects run. */
export function useRequestScope(key: string) {
  const state = useRef({ key, epoch: 0, mounted: true });
  if (state.current.key !== key) state.current = { key, epoch: state.current.epoch + 1, mounted: true };
  useEffect(() => {
    state.current.mounted = true;
    return () => { state.current.mounted = false; state.current.epoch++; };
  }, []);
  return useMemo(() => ({
    capture() {
      const epoch = state.current.epoch;
      return () => state.current.mounted && state.current.key === key && state.current.epoch === epoch;
    },
    begin() {
      if (state.current.key === key) state.current.epoch++;
      const epoch = state.current.epoch;
      return () => state.current.mounted && state.current.key === key && state.current.epoch === epoch;
    },
  }), [key]);
}
