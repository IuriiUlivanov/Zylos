import { describe, expect, it } from "vitest";
import { addressHit, orgHit } from "../test/fixtures";
import { buildingSelection, hitSelection, mapClickTarget } from "./selection";
import type { BuildingDetail } from "../types/building";

describe("hitSelection", () => {
  it("maps an organization hit to a sheet selection with category subtitle", () => {
    expect(hitSelection(orgHit())).toMatchObject({
      type: "hit",
      id: "org:osm:n1",
      title: "Zelena apoteka",
      subtitle: "Apoteka",
      kind: "organization",
      lat: 45.2493968,
      lon: 19.8409398,
      buildingId: "bldg:1",
    });
  });

  it("uses Organizacija when an org has no category", () => {
    expect(hitSelection(orgHit({ category_slug: undefined, category_name: undefined })).subtitle).toBe(
      "Organizacija",
    );
  });

  it("labels address hits as Adresa", () => {
    expect(hitSelection(addressHit())).toMatchObject({
      type: "hit",
      title: "Bulevar oslobođenja 12",
      subtitle: "Adresa",
      kind: "address",
    });
  });

  it("prefers a building tile over POI so a house tap highlights on the first click", () => {
    expect(mapClickTarget({ poiHits: 1, markerHits: 0, buildingTileHits: 1 })).toBe("building");
    expect(mapClickTarget({ poiHits: 1, markerHits: 1 })).toBe("poi");
    expect(mapClickTarget({ poiHits: 0, markerHits: 1 })).toBe("marker");
    expect(mapClickTarget({ poiHits: 0, markerHits: 0 })).toBe("building");
  });

  it("pins the building marker at the click, not the centroid", () => {
    const building: BuildingDetail = {
      id: "bldg-1",
      name: "Big Fashion",
      centroid: { lon: 19.8435, lat: 45.2458 },
      geometry: { type: "Polygon", coordinates: [] },
      addresses: [],
      organizations: [],
    };
    expect(buildingSelection(building, { lon: 19.841, lat: 45.247 })).toMatchObject({
      lon: 19.841,
      lat: 45.247,
    });
    expect(buildingSelection(building)).toMatchObject({
      lon: 19.8435,
      lat: 45.2458,
    });
  });
});
