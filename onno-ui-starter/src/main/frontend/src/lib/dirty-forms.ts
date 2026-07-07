/**
 * Which open form routes have unsaved edits, keyed by their tab path ("/catalogs/suppliers/{id}/edit",
 * ".../new", ".../duplicate"). The form widget marks/clears; the workspace shell consults it before
 * closing a tab (and before an SSE refetch remounts a surface) so a stray X / Esc / server push
 * can't silently discard typed input.
 *
 * Module state (not React state) on purpose: the form island and the shell live in different React
 * trees, and the flag's lifetime is the form instance's — a remounted island starts blank, and its
 * unmount cleanup drops the flag, so the two can't drift.
 */

const dirty = new Set<string>();

export function markFormDirty(path: string): void {
  dirty.add(path);
}

export function clearFormDirty(path: string): void {
  dirty.delete(path);
}

export function isFormDirty(path: string): boolean {
  return dirty.has(path);
}
