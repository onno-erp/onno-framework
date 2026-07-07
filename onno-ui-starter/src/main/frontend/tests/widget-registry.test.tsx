import { describe, expect, it, vi } from "vitest";

// widget-bridge's built-in registry pulls in the map widget, whose maplibre-gl dependency can't
// evaluate under jsdom — stub it; the registry mechanics under test don't render it.
vi.mock("@/components/map-widget", () => ({ MapWidget: () => null }));

import {
  getRegistryVersion,
  registeredWidgetTypes,
  registerWidget,
  resolveWidget,
  subscribeRegistry,
} from "@/lib/widget-bridge";

/**
 * The widget registry's lookup side, which the entity list's custom body renderer
 * (ListSpec.custom) resolves against: registerWidget makes a type resolvable, bumps the version
 * store and notifies subscribers (so a list mounted before a plugin loads re-resolves), and an
 * unregistered type stays undefined — the list degrades to the default grid.
 */
describe("widget registry lookups", () => {
  it("resolves built-ins and leaves unknown types undefined", () => {
    expect(resolveWidget("chart")).toBeDefined();
    expect(resolveWidget("no-such-renderer")).toBeUndefined();
  });

  it("registerWidget makes the type resolvable and notifies registry subscribers", () => {
    const Tiles = () => null;
    let notified = 0;
    const unsubscribe = subscribeRegistry(() => notified++);
    const before = getRegistryVersion();

    registerWidget("testTiles", Tiles);

    expect(resolveWidget("testTiles")).toBe(Tiles);
    expect(registeredWidgetTypes()).toContain("testTiles");
    expect(getRegistryVersion()).toBeGreaterThan(before);
    expect(notified).toBe(1);

    unsubscribe();
    registerWidget("testTiles2", Tiles);
    expect(notified).toBe(1); // unsubscribed — no further notifications
  });
});
