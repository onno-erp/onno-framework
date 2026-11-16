import { createContext, createElement, useCallback, useContext, useEffect, useMemo, useRef, type ReactNode } from "react";

/**
 * Which open form routes have unsaved edits, keyed by their canonical tab path
 * ("/catalogs/suppliers/{id}", ".../new", ".../duplicate"). A form reports its own state with
 * {@link useFormDirty}; the shell asks {@link useDirtyForms} before closing a tab or navigating
 * away, so a stray X / Esc / Back can't silently discard typed input.
 */
export type DirtyForms = {
  isDirty: (path: string) => boolean;
  mark: (path: string) => void;
  clear: (path: string) => void;
};

/** Outside a provider (a form rendered on its own): nothing asks, so nothing is recorded. */
const DirtyFormsContext = createContext<DirtyForms>({ isDirty: () => false, mark: () => {}, clear: () => {} });

/** Tracks the dirty forms of everything below it (the shell). */
export function DirtyFormsProvider({ children }: { children: ReactNode }) {
  // A ref, not state: nothing renders from the flags, they are only asked at decision time.
  const dirty = useRef(new Set<string>());
  const value = useMemo<DirtyForms>(() => ({
    isDirty: (path) => dirty.current.has(path),
    mark: (path) => void dirty.current.add(path),
    clear: (path) => void dirty.current.delete(path),
  }), []);
  return createElement(DirtyFormsContext.Provider, { value }, children);
}

/** The shell's view of which forms hold unsaved edits. */
export function useDirtyForms(): DirtyForms {
  return useContext(DirtyFormsContext);
}

/**
 * One form's unsaved-edits flag at `path`, reported to the shell. Its lifetime is the form
 * instance's: save/cancel clear it explicitly, and unmount clears it too — a remounted form starts
 * from the stored record again, so nothing is at risk.
 */
export function useFormDirty(path: string): { isDirty: () => boolean; mark: () => void; clear: () => void } {
  const forms = useDirtyForms();
  const dirty = useRef(false);
  const mark = useCallback(() => {
    dirty.current = true;
    forms.mark(path);
  }, [forms, path]);
  const clear = useCallback(() => {
    dirty.current = false;
    forms.clear(path);
  }, [forms, path]);
  const isDirty = useCallback(() => dirty.current, []);
  useEffect(() => () => {
    dirty.current = false;
    forms.clear(path);
  }, [forms, path]);
  return useMemo(() => ({ isDirty, mark, clear }), [isDirty, mark, clear]);
}
