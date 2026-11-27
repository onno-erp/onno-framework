import { useSyncExternalStore } from "react";

/**
 * The device class every responsive surface agrees on: the shell layout, page rows, the calendar,
 * facet overlays and application windows all switch at the same widths, so a 700px screen is never
 * a tablet to one part of the UI and a phone to another.
 *
 * - `mobile` — phones (below Tailwind's `md`).
 * - `tablet` — tablets and wide landscape phones (`md` up to `lg`).
 * - `desktop` — laptops and wider (`lg` and up).
 */
export type Viewport = "mobile" | "tablet" | "desktop";

/** The narrowest tablet width, in CSS pixels — Tailwind's `md`. Narrower is `mobile`. */
export const TABLET_MIN_WIDTH = 768;
/** The narrowest desktop width, in CSS pixels — Tailwind's `lg`. */
export const DESKTOP_MIN_WIDTH = 1024;

// Each class is the exact complement of a `min-width` query — the form of Tailwind's `max-md:`/
// `max-lg:` — so a fractional width under browser zoom (767.99px) can never fall between two
// classes, and the hook switches exactly where `md:`/`lg:` do.
/** Media query matching the `mobile` class: narrower than Tailwind's `md`. */
export const MOBILE_QUERY = `not all and (min-width: ${TABLET_MIN_WIDTH}px)`;
/** Media query matching `mobile` and `tablet`: narrower than Tailwind's `lg`. */
export const BELOW_DESKTOP_QUERY = `not all and (min-width: ${DESKTOP_MIN_WIDTH}px)`;

const QUERIES = [MOBILE_QUERY, BELOW_DESKTOP_QUERY];

function canMatchMedia() {
  return typeof window !== "undefined" && typeof window.matchMedia === "function";
}

function subscribe(onChange: () => void) {
  if (!canMatchMedia()) return () => {};
  const lists = QUERIES.map((query) => window.matchMedia(query));
  lists.forEach((list) => list.addEventListener("change", onChange));
  return () => lists.forEach((list) => list.removeEventListener("change", onChange));
}

/** The current device class; `desktop` where media queries are unavailable (SSR, bare test DOMs). */
export function currentViewport(): Viewport {
  if (!canMatchMedia()) return "desktop";
  if (window.matchMedia(MOBILE_QUERY).matches) return "mobile";
  if (window.matchMedia(BELOW_DESKTOP_QUERY).matches) return "tablet";
  return "desktop";
}

const serverViewport = (): Viewport => "desktop";

/** The current {@link Viewport}, re-rendering when the screen crosses a breakpoint. */
export function useViewport(): Viewport {
  return useSyncExternalStore(subscribe, currentViewport, serverViewport);
}
