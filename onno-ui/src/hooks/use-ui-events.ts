import { useEffect, useRef } from "react";
import { subscribeUiEvents as subscribeAll } from "../lib/ui-event-bus";
import type { UiEvent, UiEventFilter } from "../lib/types";

function matches(event: UiEvent, filter?: UiEventFilter): boolean {
  if (!filter) return true;
  if (filter.types && !filter.types.includes(event.type)) return false;
  if (filter.entityType !== undefined && event.entityType !== filter.entityType) return false;
  if (filter.entityName !== undefined && event.entityName !== filter.entityName) return false;
  if (filter.id !== undefined && event.id !== filter.id) return false;
  return true;
}

/**
 * Subscribe to the server's live events — those `filter` selects — over the one shared stream (all
 * tabs of the origin share one SSE connection; never open your own EventSource). Entity data needs
 * no subscription: its queries refresh by themselves (see `useEntityChanges`). This is for what
 * isn't a query: comments, presence, process tasks, notifications.
 */
export function subscribeUiEvents(listener: (event: UiEvent) => void, filter?: UiEventFilter): () => void {
  return subscribeAll((event) => {
    if (matches(event, filter)) listener(event);
  });
}

/**
 * {@link subscribeUiEvents} for a component, cleaned up on unmount; `listener` needn't be stable.
 * With `enabled` false it doesn't subscribe (a component that only sometimes needs the events).
 */
export function useUiEvents(listener: (event: UiEvent) => void, filter?: UiEventFilter, enabled = true): void {
  const ref = useRef(listener);
  ref.current = listener;
  const typesKey = filter?.types?.join("\u0000") ?? "";
  useEffect(
    () => (enabled ? subscribeUiEvents((event) => ref.current(event), filter) : undefined),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [enabled, filter?.entityType, filter?.entityName, filter?.id, typesKey],
  );
}
