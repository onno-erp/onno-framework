/**
 * Which open form routes have unsaved edits, keyed by their tab path ("/catalogs/suppliers/{id}",
 * ".../new", ".../duplicate"). The form widget marks/clears; the workspace shell consults it before
 * closing a tab (and before an SSE refetch remounts a surface) so a stray X / Esc / server push
 * can't silently discard typed input.
 *
 * Module state (not React state) on purpose: the form island and the shell live in different React
 * trees, and the flag's lifetime is the form instance's — a remounted island starts blank, and its
 * unmount cleanup drops the flag, so the two can't drift.
 */

const dirty = new Set<string>();

// The record surface is the editable form; the legacy "/edit" route serves the same surface, so
// both paths must share one dirty flag or the tab-close guard and the SSE-refetch skip would miss
// a form opened via an old deep link.
function normalize(path: string): string {
  return path.endsWith("/edit") ? path.slice(0, -"/edit".length) : path;
}

export function markFormDirty(path: string): void {
  dirty.add(normalize(path));
}

export function clearFormDirty(path: string): void {
  dirty.delete(normalize(path));
}

export function isFormDirty(path: string): boolean {
  return dirty.has(normalize(path));
}
