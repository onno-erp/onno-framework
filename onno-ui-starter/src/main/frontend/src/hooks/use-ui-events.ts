import { useEffect } from "react";
import { subscribeUiEvents } from "@/lib/ui-event-bus";
import type { UiEvent } from "@/lib/types";

/**
 * Subscribe to live entity-change events. All tabs of the origin share one SSE connection (see
 * {@link subscribeUiEvents}), so opening many tabs no longer exhausts the browser's per-origin
 * connection pool. `handler` should be stable (e.g. `useCallback`) so the subscription isn't torn
 * down and re-created on every render.
 */
export function useUiEvents(handler: (event: UiEvent) => void) {
  useEffect(() => subscribeUiEvents(handler), [handler]);
}
