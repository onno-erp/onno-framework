import { useCallback, useRef, useState, type Dispatch, type SetStateAction } from "react";

/**
 * State a component owns unless its parent passes `value` — then the parent owns it, like a React
 * `<input value onChange>`. Either way changes are reported through `onChange`.
 */
export function useControllable<T>(
  value: T | undefined,
  onChange: ((next: T) => void) | undefined,
  initial: T | (() => T),
): [T, Dispatch<SetStateAction<T>>] {
  const [own, setOwn] = useState(initial);
  const controlled = value !== undefined;
  const current = controlled ? value : own;
  const currentRef = useRef(current);
  currentRef.current = current;
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;
  const set = useCallback<Dispatch<SetStateAction<T>>>((next) => {
    const resolved = typeof next === "function" ? (next as (prev: T) => T)(currentRef.current) : next;
    if (Object.is(resolved, currentRef.current)) return;
    currentRef.current = resolved;
    if (!controlled) setOwn(resolved);
    onChangeRef.current?.(resolved);
  }, [controlled]);
  return [current, set];
}
