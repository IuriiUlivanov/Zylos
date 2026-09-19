import type { StyleSpecification } from "maplibre-gl";
import { describe, expect, it } from "vitest";
import { loadMapStyle, patchMapStyle } from "./mapStyle";

function baseStyle(): StyleSpecification {
  return {
    version: 8,
    name: "Zylos 2GIS-like",
    sources: {
      noviSad: {
        type: "vector",
        tiles: ["/mvt/novi-sad/{z}/{x}/{y}.mvt"],
        minzoom: 0,
        maxzoom: 14,
      },
      waterFill: {
        type: "geojson",
        data: "/water-fill.geojson",
      },
    },
    layers: [],
  };
}

describe("patchMapStyle", () => {
  it("rewrites noviSad MVT and water-fill URLs onto the tiles host", () => {
    const patched = patchMapStyle(baseStyle(), "http://127.0.0.1:8080/");
    const noviSad = patched.sources.noviSad;
    const waterFill = patched.sources.waterFill;

    expect(noviSad?.type).toBe("vector");
    if (noviSad?.type === "vector") {
      expect(noviSad.tiles).toEqual(["http://127.0.0.1:8080/mvt/novi-sad/{z}/{x}/{y}.mvt"]);
      expect(noviSad.maxzoom).toBe(14);
    }
    expect(waterFill?.type).toBe("geojson");
    if (waterFill?.type === "geojson") {
      expect(waterFill.data).toBe("http://127.0.0.1:8080/water-fill.geojson");
    }
  });

  it("does not mutate the input style object", () => {
    const original = baseStyle();
    patchMapStyle(original, "http://127.0.0.1:8080");
    const noviSad = original.sources.noviSad;
    if (noviSad?.type === "vector") {
      expect(noviSad.tiles).toEqual(["/mvt/novi-sad/{z}/{x}/{y}.mvt"]);
    }
  });
});

describe("loadMapStyle", () => {
  it("loads the preview style with source noviSad at maxzoom 14, not Protomaps", () => {
    const style = loadMapStyle("http://127.0.0.1:8080");
    const noviSad = style.sources.noviSad;

    expect(style.name).toBe("Zylos 2GIS-like");
    expect(style.name).not.toMatch(/protomaps/i);
    expect(noviSad).toBeDefined();
    if (noviSad?.type === "vector") {
      expect(noviSad.maxzoom).toBe(14);
      expect(noviSad.tiles).toEqual(["http://127.0.0.1:8080/mvt/novi-sad/{z}/{x}/{y}.mvt"]);
      expect(noviSad.attribution).toMatch(/OpenStreetMap/i);
    }
  });
});
