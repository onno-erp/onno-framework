import * as React from "react";
import { act, cleanup, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

type Event = { type: string; entityType?: string; entityName?: string; id?: string };

const listeners = new Set<(event: Event) => void>();
const unsubscribe = vi.fn();

function installHost(withEvents = true) {
  const events = withEvents
    ? {
        subscribe: (listener: (event: Event) => void) => {
          listeners.add(listener);
          return () => {
            listeners.delete(listener);
            unsubscribe();
          };
        },
      }
    : undefined;
  (globalThis as unknown as { onno: unknown }).onno = {
    React,
    jsxRuntime: {},
    registerWidget: () => {},
    html: () => null,
    api: {},
    ui: {},
    events,
    version: withEvents ? 3 : 2,
  };
}

const widget = {
  title: "Recent activity",
  widgetType: "eventLog",
  order: 0,
  width: "full",
  entityType: "document",
  entityName: "Payments",
  maxItems: 10,
  dateField: "date",
  titleField: "number",
  extraConfig: {},
};

describe("widget SDK live updates", () => {
  beforeEach(() => {
    vi.resetModules();
    vi.useFakeTimers();
    listeners.clear();
    unsubscribe.mockClear();
    installHost();
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    delete (globalThis as unknown as { onno?: unknown }).onno;
  });

  it("filters to the bound entity, coalesces bursts, and cleans up", async () => {
    const sdk = await import("../../../../../onno-widget-sdk/src/index");
    const refresh = vi.fn();
    const hook = renderHook(() => sdk.useWidgetUpdates(widget, refresh, { debounceMs: 50 }));

    expect(listeners).toHaveLength(1);
    act(() => {
      for (const listener of listeners) {
        listener({ type: "updated", entityType: "document", entityName: "Orders" });
        listener({ type: "posted", entityType: "document", entityName: "Payments" });
        listener({ type: "changed", entityType: "document", entityName: "Payments" });
      }
      vi.advanceTimersByTime(49);
    });
    expect(refresh).not.toHaveBeenCalled();
    act(() => vi.advanceTimersByTime(1));
    expect(refresh).toHaveBeenCalledTimes(1);

    hook.unmount();
    expect(unsubscribe).toHaveBeenCalledTimes(1);
    expect(listeners).toHaveLength(0);
  });

  it("supports register wildcard invalidations", async () => {
    const sdk = await import("../../../../../onno-widget-sdk/src/index");
    const refresh = vi.fn();
    renderHook(() =>
      sdk.useWidgetUpdates({ ...widget, entityType: "register", entityName: "Stock" }, refresh, {
        debounceMs: 0,
      })
    );

    act(() => {
      for (const listener of listeners) {
        listener({ type: "changed", entityType: "register", entityName: "*" });
      }
      vi.runAllTimers();
    });
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it("isolates synchronous and asynchronous refresh failures", async () => {
    const error = vi.spyOn(console, "error").mockImplementation(() => {});
    const sdk = await import("../../../../../onno-widget-sdk/src/index");
    const syncFailure = new Error("sync refresh failed");
    const asyncFailure = new Error("async refresh failed");
    const throwSync = () => { throw syncFailure; };
    const hook = renderHook(({ refresh }) =>
      sdk.useWidgetUpdates(widget, refresh, { debounceMs: 0 }), {
        initialProps: { refresh: throwSync as () => void | Promise<unknown> },
      }
    );

    act(() => {
      for (const listener of listeners) {
        listener({ type: "updated", entityType: "document", entityName: "Payments" });
      }
      vi.runAllTimers();
    });
    expect(error).toHaveBeenCalledWith("[onno] widget live refresh failed", syncFailure);

    hook.rerender({ refresh: () => Promise.reject(asyncFailure) });
    act(() => {
      for (const listener of listeners) {
        listener({ type: "updated", entityType: "document", entityName: "Payments" });
      }
      vi.runAllTimers();
    });
    await act(async () => { await Promise.resolve(); });
    expect(error).toHaveBeenCalledWith("[onno] widget live refresh failed", asyncFailure);

    error.mockRestore();
  });

  it("loads on a v2 host until a live API is actually used", async () => {
    installHost(false);
    vi.resetModules();
    const sdk = await import("../../../../../onno-widget-sdk/src/index");

    expect(() => sdk.events.subscribe(() => {})).toThrow(/host contract v3/);
  });
});
