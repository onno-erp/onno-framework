import { describe, expect, it, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { createElement, type ReactNode } from "react";
import type { UiEvent } from "@/lib/types";

// Hoisted so the vi.mock factory (which runs before module init) can close over it.
const { getPresenceSnapshot } = vi.hoisted(() => ({ getPresenceSnapshot: vi.fn() }));
vi.mock("@/lib/api", () => ({ api: { getPresenceSnapshot } }));

// The server's live stream, driven by the test.
const listeners = new Set<(event: UiEvent) => void>();
vi.mock("@/lib/ui-event-bus", () => ({
  subscribeUiEvents: (listener: (event: UiEvent) => void) => {
    listeners.add(listener);
    return () => listeners.delete(listener);
  },
}));

import { PresenceProvider, useEntityViewers, useRecordViewers, useViewersById } from "@/lib/presence-store";

function presenceEvent(detail: Record<string, unknown>) {
  for (const listener of listeners) listener({ type: "presence", ...detail } as UiEvent);
}

describe("presence", () => {
  it("seeds from the snapshot, applies live events, and aggregates by entity (self excluded)", async () => {
    getPresenceSnapshot.mockResolvedValue({
      you: "me",
      records: [
        {
          kind: "catalogs",
          name: "properties",
          id: "r1",
          viewers: [
            { userId: "me", displayName: "Me" },
            { userId: "u2", displayName: "Ada" },
          ],
        },
      ],
    });
    const wrapper = ({ children }: { children: ReactNode }) => createElement(PresenceProvider, null, children);
    const view = renderHook(() => ({
      record: useRecordViewers("r1"),
      entity: useEntityViewers("catalogs", "properties"),
      byId: useViewersById(),
    }), { wrapper });

    // Snapshot seeds r1; viewers include the caller (me) so your own other sessions show.
    await waitFor(() => expect(view.result.current.record.map((v) => v.userId)).toEqual(["me", "u2"]));

    // A second property record gains a viewer via a live event.
    act(() =>
      presenceEvent({ kind: "catalogs", entityName: "properties", id: "r2", viewers: [{ userId: "u3", displayName: "Bob" }] })
    );

    // Sidebar aggregation unions the distinct OTHER viewers across every property record — self is
    // excluded (your own presence isn't news; showing it just made the sidebar dot blink).
    await waitFor(() => expect(view.result.current.entity.map((v) => v.userId).sort()).toEqual(["u2", "u3"]));

    // The by-id map (list rows) carries both records, self included.
    expect(view.result.current.byId.get("r1")?.map((v) => v.userId)).toEqual(["me", "u2"]);
    expect(view.result.current.byId.get("r2")?.map((v) => v.userId)).toEqual(["u3"]);

    // An empty-viewers event (everyone left) drops the record entirely.
    act(() => presenceEvent({ kind: "catalogs", entityName: "properties", id: "r2", viewers: [] }));
    expect(view.result.current.byId.has("r2")).toBe(false);
  });

  it("shows nobody outside a provider", () => {
    const view = renderHook(() => useRecordViewers("r1"));
    expect(view.result.current).toEqual([]);
  });
});
