import { categoryLabel } from "./categories";
import type { Selection } from "../types/app";
import type { BuildingDetail } from "../types/building";
import type { OrgDetail } from "../types/org";
import type { SearchHit } from "../types/search";

export function hitSelection(hit: SearchHit): Selection {
  const subtitle =
    hit.kind === "organization"
      ? (categoryLabel(hit.category_slug, hit.category_name) ?? "Organizacija")
      : "Adresa";
  return {
    type: "hit",
    id: hit.id,
    title: hit.label,
    subtitle,
    kind: hit.kind,
    lat: hit.lat,
    lon: hit.lon,
    buildingId: hit.building_id,
  };
}

export function buildingSelection(
  building: BuildingDetail,
  pin?: { lon: number; lat: number } | null,
): Selection {
  const title =
    building.addresses[0]?.label || building.name || "Zgrada";
  const subtitle =
    building.organizations.length > 0
      ? `${building.organizations.length} organizacija`
      : "Zgrada";
  return {
    type: "building",
    id: building.id,
    title,
    subtitle,
    lat: pin?.lat ?? building.centroid.lat,
    lon: pin?.lon ?? building.centroid.lon,
  };
}

export function clickSelection(lon: number, lat: number): Selection {
  return {
    type: "building",
    id: "click",
    title: "Zgrada",
    subtitle: "Zgrada",
    lat,
    lon,
  };
}

export function orgSelection(org: OrgDetail): Selection {
  return {
    type: "organization",
    id: org.id,
    title: org.name,
    subtitle: categoryLabel(org.category_slug ?? undefined, org.category_name ?? undefined) ?? "Organizacija",
    lat: org.location.lat,
    lon: org.location.lon,
    buildingId: org.building_id,
  };
}

export type MapClickTarget = "poi" | "marker" | "building";

export function mapClickTarget(input: {
  poiHits: number;
  markerHits: number;
  buildingTileHits?: number;
}): MapClickTarget {
  if ((input.buildingTileHits ?? 0) > 0) {
    return "building";
  }
  if (input.poiHits > 0) {
    return "poi";
  }
  if (input.markerHits > 0) {
    return "marker";
  }
  return "building";
}
