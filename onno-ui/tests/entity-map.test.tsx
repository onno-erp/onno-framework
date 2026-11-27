import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import type { Feature } from "geojson";

// EntityMap reads its rows through useEntityRows (which sends `source.where`) and labels each
// feature with `label(record)`. The rows hook records the source it was given; MapView records
// the features it would draw.
const seen = vi.hoisted(() => ({ source: null as unknown, features: [] as Feature[] }));

vi.mock("@/lib/aggregate-data", () => ({
  useEntityRows: (source: unknown) => {
    seen.source = source;
    return { rows: [
      { id: "s-1", name: "North", description: "Store 1", lat: 55.75, lng: 37.61 },
      { id: "s-2", name: "South", description: "Store 2", lat: 55.7, lng: 37.6 },
      { id: "s-3", name: "Nowhere", description: "Store 3" },
    ], hasMore: false, nextCursor: null };
  },
}));
vi.mock("@/lib/entity-source", () => ({
  useEntitySource: (entity: string | undefined, options: { where?: unknown } = {}) =>
    entity ? { entityType: "catalog", entityName: entity, dateField: "", where: options.where } : null,
}));
vi.mock("@/components/maps/map-view", () => ({
  MapView: ({ features }: { features: Feature[] }) => {
    seen.features = features;
    return <div data-testid="map" />;
  },
}));

import { EntityMap } from "@/components/maps/entity-map";

afterEach(() => {
  cleanup();
  seen.source = null;
  seen.features = [];
});

describe("EntityMap", () => {
  it("passes where to the rows source and labels markers with the render function", () => {
    render(<EntityMap title="Stores" entity="Stores" where={{ active: true }} lat="lat" lng="lng" label={(s) => `${s.name}!`} />);

    expect(seen.source).toMatchObject({ entityName: "Stores", where: { active: true } });
    expect(screen.getByTestId("map")).toBeTruthy();
    expect(seen.features.map((f) => f.properties?.label)).toEqual(["North!", "South!"]);
    expect(seen.features[0].properties?.href).toBe("onno://catalogs/stores/s-1");
  });

  it("defaults the label to the description and honours limit", () => {
    render(<EntityMap entity="Stores" lat="lat" lng="lng" limit={1} />);

    expect(seen.features.map((f) => f.properties?.label)).toEqual(["Store 1"]);
  });

  it("asks for a geo source when lat/lng and geoJson are missing", () => {
    render(<EntityMap entity="Stores" />);

    expect(screen.queryByTestId("map")).toBeNull();
    expect(screen.getByText("geoJson")).toBeTruthy();
  });
});
