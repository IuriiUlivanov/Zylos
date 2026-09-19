import type { StyleSpecification } from "maplibre-gl";
import previewStyle from "../../../../infra/preview/style.json";
import { TILES_URL } from "./constants";

export function patchMapStyle(style: StyleSpecification, tilesUrl: string): StyleSpecification {
  const base = tilesUrl.replace(/\/$/, "");
  const next: StyleSpecification = {
    ...style,
    sources: { ...style.sources },
  };
  const noviSad = next.sources.noviSad;
  if (noviSad && noviSad.type === "vector") {
    next.sources.noviSad = {
      ...noviSad,
      tiles: [`${base}/mvt/novi-sad/{z}/{x}/{y}.mvt`],
    };
  }
  const waterFill = next.sources.waterFill;
  if (waterFill && waterFill.type === "geojson") {
    next.sources.waterFill = {
      ...waterFill,
      data: `${base}/water-fill.geojson`,
    };
  }
  return next;
}

export function loadMapStyle(tilesUrl = TILES_URL): StyleSpecification {
  return patchMapStyle(structuredClone(previewStyle) as StyleSpecification, tilesUrl);
}
