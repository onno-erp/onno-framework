import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import type { UiEvent } from "@/lib/types";

// Mock the SSE transport so no real fetch runs: capture each (onEvent, signal) and keep the
// returned promise pending until the signal aborts (mirrors a live stream that never completes).
const h = vi.hoisted(() => {
  const calls: { onEvent: (e: UiEvent) => void; signal: AbortSignal }[] = [];
  const streamUiEvents = vi.fn((onEvent: (e: UiEvent) => void, signal: AbortSignal) => {
    calls.push({ onEvent, signal });
    return new Promise<void>((resolve) => {
      if (signal.aborted) resolve();
      else signal.addEventListener("abort", () => resolve(), { once: true });
    });
  });
  return { calls, streamUiEvents };
});

vi.mock("@/lib/api", () => ({ streamUiEvents: h.streamUiEvents }));

const evt = (over: Partial<UiEvent> = {}): UiEvent => ({
  type: "changed",
  entityType: "catalog",
  entityName: "Properties",
  id: "1",
  naturalKey: null,
  ...over,
});

function lastStream() {
  return h.calls[h.calls.length - 1];
}

describe("ui-event-bus", () => {
  beforeEach(() => {
    vi.resetModules();
    h.calls.length = 0;
    h.streamUiEvents.mockClear();
    delete (navigator as unknown as { locks?: unknown }).locks; // default: no Web Locks -> fallback
  });

  afterEach(() => {
    delete (navigator as unknown as { locks?: unknown }).locks;
  });

  it("opens ONE shared connection for many subscribers and delivers to all", async () => {
    const { subscribeUiEvents } = await import("@/lib/ui-event-bus");
    const a: UiEvent[] = [];
    const b: UiEvent[] = [];
    const offA = subscribeUiEvents((e) => a.push(e));
    const offB = subscribeUiEvents((e) => b.push(e));

    // The whole point of the fix: subscribers (and, across tabs, the shared bus) reuse one stream.
    expect(h.streamUiEvents).toHaveBeenCalledTimes(1);

    const e = evt();
    lastStream().onEvent(e);
    expect(a).toEqual([e]);
    expect(b).toEqual([e]);

    offA();
    offB();
  });

  it("tears the stream down only when the last subscriber leaves", async () => {
    const { subscribeUiEvents } = await import("@/lib/ui-event-bus");
    const off1 = subscribeUiEvents(() => {});
    const off2 = subscribeUiEvents(() => {});
    const { signal } = lastStream();

    off1();
    expect(signal.aborted).toBe(false); // one subscriber remains
    off2();
    expect(signal.aborted).toBe(true); // last one gone -> connection closed
  });

  it("elects a leader via Web Locks and rebroadcasts events to other tabs", async () => {
    (navigator as unknown as { locks: unknown }).locks = {
      // Grant the lock immediately; the leader holds it (callback promise stays pending).
      request: vi.fn((_name: string, cb: () => Promise<void>) => Promise.resolve(cb())),
    };
    const postSpy = vi.spyOn(BroadcastChannel.prototype, "postMessage");

    const { subscribeUiEvents } = await import("@/lib/ui-event-bus");
    const received: UiEvent[] = [];
    const off = subscribeUiEvents((e) => received.push(e));

    expect(h.streamUiEvents).toHaveBeenCalledTimes(1); // leader holds the single stream

    const e = evt({ type: "posted", entityType: "document", entityName: "Invoices", id: "9" });
    lastStream().onEvent(e);

    expect(received).toEqual([e]); // delivered locally
    expect(postSpy).toHaveBeenCalledWith(e); // and fanned out to the other tabs

    off();
    postSpy.mockRestore();
  });
});
