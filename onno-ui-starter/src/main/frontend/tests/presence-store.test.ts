import { describe, expect, it, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";

// Hoisted so the vi.mock factory (which runs before module init) can close over it.
const { getPresenceSnapshot } = vi.hoisted(() => ({ getPresenceSnapshot: vi.fn() }));
vi.mock("@/lib/api", () => ({ api: { getPresenceSnapshot } }));

import {
  startPresence,
  useRecordViewers,
  useEntityViewers,
  useViewersById,
} from "@/lib/presence-store";

function presenceDelta(detail: Record<string, unknown>) {
  window.dispatchEvent(new CustomEvent("onno:dataevent", { detail: { type: "presence", ...detail } }));
}

describe("presence store", () => {
  it("seeds from the snapshot, applies live deltas, includes self, and aggregates by entity", async () => {
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
    startPresence();

    // Snapshot seeds r1; viewers include the caller (me) so your own other sessions show.
    const record = renderHook(() => useRecordViewers("r1"));
    await waitFor(() => expect(record.result.current.map((v) => v.userId)).toEqual(["me", "u2"]));

    // A second property record gains a viewer via a live SSE delta.
    act(() =>
      presenceDelta({ kind: "catalogs", entityName: "properties", id: "r2", viewers: [{ userId: "u3", displayName: "Bob" }] })
    );

    // Sidebar aggregation unions the distinct viewers (including you) across every property record.
    const entity = renderHook(() => useEntityViewers("catalogs", "properties"));
    await waitFor(() => expect(entity.result.current.map((v) => v.userId).sort()).toEqual(["me", "u2", "u3"]));

    // The by-id map (list rows) carries both records, self included.
    const byId = renderHook(() => useViewersById());
    expect(byId.result.current.get("r1")?.map((v) => v.userId)).toEqual(["me", "u2"]);
    expect(byId.result.current.get("r2")?.map((v) => v.userId)).toEqual(["u3"]);

    // An empty-viewers delta (everyone left) drops the record entirely.
    act(() => presenceDelta({ kind: "catalogs", entityName: "properties", id: "r2", viewers: [] }));
    const after = renderHook(() => useViewersById());
    expect(after.result.current.has("r2")).toBe(false);
  });
});
