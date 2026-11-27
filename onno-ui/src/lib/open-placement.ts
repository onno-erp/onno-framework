/**
 * Where an open lands in the desktop workspace, and how a click asks for it. The modifiers follow
 * the browser's: a plain click navigates the active tab, a middle- or Ctrl/⌘-click opens a new tab,
 * a Shift-click opens beside the current island (the browser's "new window").
 */

/** `tab` replaces what the active tab shows; `new-tab` and `new-island` open next to it. */
export type OpenPlacement = "tab" | "new-tab" | "new-island";

type ClickModifiers = { button?: number; shiftKey?: boolean; ctrlKey?: boolean; metaKey?: boolean };

/** The placement a mouse click (or an Enter press carrying the same modifiers) asks for. */
export function placementForClick(event: ClickModifiers): OpenPlacement {
  if (event.button === 1 || event.ctrlKey || event.metaKey) return "new-tab";
  if (event.shiftKey) return "new-island";
  return "tab";
}
