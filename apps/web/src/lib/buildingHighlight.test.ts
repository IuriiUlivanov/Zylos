import { describe, expect, it } from "vitest";
import { emptyGeometryCollection, geometryCollection, queryPointFromGeometry } from "./buildingHighlight";

describe("buildingHighlight", () => {
  it("wraps a polygon in a FeatureCollection with a feature id", () => {
    const geometry = {
      type: "Polygon" as const,
      coordinates: [
        [
          [19.84, 45.25],
          [19.85, 45.25],
          [19.85, 45.26],
          [19.84, 45.26],
          [19.84, 45.25],
        ],
      ],
    };
    expect(geometryCollection(geometry)).toEqual({
      type: "FeatureCollection",
      features: [
        {
          type: "Feature",
          id: 1,
          properties: {},
          geometry,
        },
      ],
    });
  });

  it("clears highlight with an empty FeatureCollection", () => {
    expect(emptyGeometryCollection()).toEqual({ type: "FeatureCollection", features: [] });
  });

  it("uses the ring centroid so a 3D facade click still hits the building", () => {
    const geometry = {
      type: "Polygon" as const,
      coordinates: [
        [
          [19.84, 45.25],
          [19.86, 45.25],
          [19.86, 45.27],
          [19.84, 45.27],
          [19.84, 45.25],
        ],
      ],
    };
    const point = queryPointFromGeometry(geometry, { lon: 0, lat: 0 });
    expect(point.lon).toBeCloseTo(19.848, 5);
    expect(point.lat).toBeCloseTo(45.258, 5);
  });
});
