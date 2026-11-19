import { act } from "@testing-library/react";
import { vi } from "vitest";

type MediaList = { query: string; matches: boolean; listeners: Set<() => void> };

/**
 * Stubs `matchMedia` (and `innerWidth`) with a screen `width` px wide: `(min-width: …)` /
 * `(max-width: …)` queries, joined by `and` and optionally negated with a leading `not all and`,
 * match as a browser would; any other query does not. `resize` changes the width and fires `change`
 * on every list whose match flipped, like a browser crossing a breakpoint. Undo with
 * `vi.unstubAllGlobals()`.
 */
export function stubScreenWidth(width: number) {
  let current = width;
  const lists = new Set<MediaList>();
  const conditions = (query: string) => query.split(/\s+and\s+/).every((part) => {
    const bound = /^\((min|max)-width:\s*([\d.]+)px\)$/.exec(part.trim());
    if (!bound) return false;
    return bound[1] === "min" ? current >= Number(bound[2]) : current <= Number(bound[2]);
  });
  const evaluate = (query: string) => {
    const negated = /^not all and\s+(.+)$/.exec(query.trim());
    return negated ? !conditions(negated[1]) : conditions(query);
  };
  vi.stubGlobal("innerWidth", current);
  vi.stubGlobal("matchMedia", (query: string) => {
    const list: MediaList = { query, matches: evaluate(query), listeners: new Set() };
    lists.add(list);
    return {
      media: query,
      get matches() { return evaluate(query); },
      addEventListener: (_type: string, listener: () => void) => list.listeners.add(listener),
      removeEventListener: (_type: string, listener: () => void) => list.listeners.delete(listener),
    };
  });
  return {
    resize(next: number) {
      current = next;
      vi.stubGlobal("innerWidth", next);
      act(() => {
        for (const list of lists) {
          const matches = evaluate(list.query);
          if (matches === list.matches) continue;
          list.matches = matches;
          list.listeners.forEach((listener) => listener());
        }
      });
    },
    /** `change` listeners currently subscribed across every list handed out. */
    listenerCount: () => [...lists].reduce((sum, list) => sum + list.listeners.size, 0),
  };
}
