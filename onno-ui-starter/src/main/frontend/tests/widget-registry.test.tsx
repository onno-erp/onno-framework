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

import {
  registerBuiltinWidget,
  registerListRenderer,
  registerListSelection,
  resolveListRenderer,
  resolveListSelection,
} from "@/lib/widget-registry";

/**
 * The widget registry's lookup side, which the entity list's custom body renderer
 * (ListSpec.custom) resolves against: registerWidget makes a type resolvable, bumps the version
 * store and notifies subscribers (so a list mounted before a plugin loads re-resolves), and an
 * unregistered type stays undefined — the list degrades to the default grid.
 */
describe("widget registry lookups", () => {
  it("resolves built-ins and leaves unknown types undefined", () => {
    expect(resolveWidget("chart")).toBeDefined();
    expect(resolveWidget("count")).toBeDefined();
    expect(resolveWidget("metric")).toBeDefined();
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

  it("keeps dashboard, list and selection components with the same name separate", () => {
    const dashboardMap = resolveWidget("map");
    expect(dashboardMap).toBeDefined();

    const ListMap = () => null;
    const SelectionActions = () => null;

    registerListRenderer("map", ListMap);
    registerListSelection("map", SelectionActions);

    expect(resolveWidget("map")).toBe(dashboardMap);
    expect(resolveListRenderer("map")?.component).toBe(ListMap);
    expect(resolveListSelection("map")).toBe(SelectionActions);
  });

  it("requires an explicit override to replace a built-in widget", () => {
    const Builtin = () => null;
    const Replacement = () => null;
    registerBuiltinWidget("test-builtin", Builtin);

    expect(() => registerWidget("test-builtin", Replacement)).toThrow(/override: true/);
    expect(resolveWidget("test-builtin")).toBe(Builtin);

    registerWidget("test-builtin", Replacement, { override: true });

    expect(resolveWidget("test-builtin")).toBe(Replacement);
  });

  it("stores loading options per registration without changing the component", () => {
    const Renderer = Object.freeze(() => null);

    registerListRenderer("own-loading", Renderer, { ownsLoadingMore: true });
    registerListRenderer("default-loading", Renderer);

    expect(resolveListRenderer("own-loading")?.ownsLoadingMore).toBe(true);
    expect(resolveListRenderer("default-loading")?.ownsLoadingMore).toBe(false);
    expect(Renderer).not.toHaveProperty("ownsLoadingMore");
  });

  it("still resolves list renderers and selection widgets registered by outdated bundles", () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    const Legacy = Object.assign(() => null, { ownsLoadingMore: true });
    const LegacySelection = () => null;
    registerWidget("legacy-renderer", Legacy as never);
    registerWidget("legacy-selection", LegacySelection as never);

    expect(resolveListRenderer("legacy-renderer")).toEqual({ component: Legacy, ownsLoadingMore: true });
    expect(resolveListSelection("legacy-selection")).toBe(LegacySelection);
    expect(resolveListRenderer("gauge")).toBeUndefined(); // built-ins never leak into list lookups
    expect(warn).toHaveBeenCalled();
    warn.mockRestore();
  });
});
