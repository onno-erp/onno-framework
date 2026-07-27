import { describe, expect, it } from "vitest";
import { applyFormat, isImageWidget, looksLikeImageUrl } from "@/lib/cell-format";
import { formatAmount } from "@/lib/format";
import { geoSourceFrom, hasGeoSource, toFeatureCollection } from "@/lib/geo";

describe("2.0 canonical UI formats", () => {
  it("does not infer USD for amounts or bare currency field formats", () => {
    expect(formatAmount(12.5, { locale: "en-US" })).toBe("12.50");
    expect(applyFormat("12.5", "currency")).toBeNull();
    expect(applyFormat("12.5", "currency:USD")).toContain("12.50");
  });

  it("accepts stored media URLs but not data URLs or photo widget aliases", () => {
    expect(looksLikeImageUrl("/api/media/2026/07/image.png")).toBe(true);
    expect(looksLikeImageUrl("https://cdn.example/image.png")).toBe(true);
    expect(looksLikeImageUrl("data:image/png;base64,AQID")).toBe(false);
    expect(isImageWidget("image")).toBe(true);
    expect(isImageWidget("avatar")).toBe(true);
    expect(isImageWidget("photo")).toBe(false);
  });

  it("accepts GeoJSON or numeric pairs but not combined lat,lng strings", () => {
    expect(toFeatureCollection("55.7558,37.6173")).toBeNull();
    expect(toFeatureCollection(
      '{"type":"Point","coordinates":[37.6173,55.7558]}',
    )?.features).toHaveLength(1);

    const legacy = geoSourceFrom({ geoField: "location" });
    expect(hasGeoSource(legacy)).toBe(false);
    expect(geoSourceFrom({ latField: "lat", lngField: "lng" })).toEqual({
      latField: "lat",
      lngField: "lng",
      geoJsonField: undefined,
    });
  });
});
