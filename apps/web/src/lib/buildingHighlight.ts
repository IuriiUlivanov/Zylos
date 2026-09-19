import type { GeoJSONSource, Map as MapLibreMap, MapGeoJSONFeature, PointLike } from "maplibre-gl";
import type { BuildingGeometry } from "../types/building";
import {
  BUILDING_3D_LAYER_ID,
  BUILDING_LAYER_ID,
  BUILDING_OUTLINE_LAYER_ID,
  BUILDING_SOURCE_ID,
} from "./constants";

type GeometryCollection = {
  type: "FeatureCollection";
  features: Array<{
    type: "Feature";
    id: number;
    properties: Record<string, never>;
    geometry: BuildingGeometry;
  }>;
};

type TileFeatureKey = {
  source: string;
  sourceLayer?: string;
  id: string | number;
};

const TILE_BUILDING_LAYERS = ["buildings", "buildings-3d"] as const;
const paintedMaps = new WeakSet<MapLibreMap>();
const selectedByMap = new WeakMap<MapLibreMap, TileFeatureKey>();

export function emptyGeometryCollection(): GeometryCollection {
  return { type: "FeatureCollection", features: [] };
}

export function geometryCollection(geometry: BuildingGeometry): GeometryCollection {
  return {
    type: "FeatureCollection",
    features: [
      {
        type: "Feature",
        id: 1,
        properties: {} as Record<string, never>,
        geometry,
      },
    ],
  };
}

export function queryPointFromGeometry(
  geometry: BuildingGeometry,
  fallback: { lon: number; lat: number },
): { lon: number; lat: number } {
  const coordinates = geometry.coordinates as unknown;
  const first = Array.isArray(coordinates) ? coordinates[0] : null;
  const ring = geometry.type === "Polygon" ? first : Array.isArray(first) ? first[0] : null;
  if (!Array.isArray(ring) || ring.length === 0) {
    return fallback;
  }
  let x = 0;
  let y = 0;
  let n = 0;
  for (const coord of ring) {
    if (Array.isArray(coord) && Number.isFinite(coord[0]) && Number.isFinite(coord[1])) {
      x += Number(coord[0]);
      y += Number(coord[1]);
      n += 1;
    }
  }
  return n > 0 ? { lon: x / n, lat: y / n } : fallback;
}

function asBuildingGeometry(feature: MapGeoJSONFeature): BuildingGeometry | null {
  const geometry = feature.geometry;
  if (!geometry) {
    return null;
  }
  if (geometry.type === "Polygon" || geometry.type === "MultiPolygon") {
    return geometry as BuildingGeometry;
  }
  return null;
}

function queryBuildingFeatures(map: MapLibreMap, point: { x: number; y: number }): MapGeoJSONFeature[] {
  const pad = 4;
  const box: [PointLike, PointLike] = [
    [point.x - pad, point.y - pad],
    [point.x + pad, point.y + pad],
  ];
  const namedLayers = TILE_BUILDING_LAYERS.filter((id) => Boolean(map.getLayer(id)));
  const named = namedLayers.length ? map.queryRenderedFeatures(box, { layers: namedLayers }) : [];
  if (named.length > 0) {
    return named;
  }
  return map.queryRenderedFeatures(box).filter((feature) => feature.sourceLayer === "building");
}

function featureKey(feature: MapGeoJSONFeature): TileFeatureKey | null {
  const props = feature.properties ?? {};
  const id = feature.id ?? props.id ?? props.osm_id ?? props.osm_way_id;
  if (id == null || id === "") {
    return null;
  }
  return {
    source: feature.source,
    sourceLayer: feature.sourceLayer,
    id: id as string | number,
  };
}

export function enableTileBuildingSelectionPaint(map: MapLibreMap): void {
  if (paintedMaps.has(map)) {
    return;
  }
  paintedMaps.add(map);
  if (map.getLayer("buildings")) {
    map.setPaintProperty("buildings", "fill-color", [
      "case",
      ["boolean", ["feature-state", "selected"], false],
      "#3ECF4A",
      "#DDD3C4",
    ]);
    map.setPaintProperty("buildings", "fill-outline-color", [
      "case",
      ["boolean", ["feature-state", "selected"], false],
      "#008A32",
      "#C4B7A6",
    ]);
  }
  if (map.getLayer("buildings-3d")) {
    map.setPaintProperty("buildings-3d", "fill-extrusion-color", [
      "case",
      ["boolean", ["feature-state", "selected"], false],
      "#2FBF4A",
      "#E2D6C6",
    ]);
  }
}

export function clearTileBuildingSelection(map: MapLibreMap): void {
  const previous = selectedByMap.get(map);
  if (!previous) {
    return;
  }
  try {
    map.removeFeatureState(previous, "selected");
  } catch {
    /* feature may already be gone after a style reload */
  }
  selectedByMap.delete(map);
}

export function selectTileBuildingAt(
  map: MapLibreMap,
  point: { x: number; y: number },
): BuildingGeometry | null {
  enableTileBuildingSelectionPaint(map);
  clearTileBuildingSelection(map);
  const features = queryBuildingFeatures(map, point);
  const keyed = features.find((feature) => featureKey(feature) != null) ?? features[0];
  if (!keyed) {
    return null;
  }
  const key = featureKey(keyed);
  if (key) {
    map.setFeatureState(key, { selected: true });
    selectedByMap.set(map, key);
  }
  return asBuildingGeometry(keyed);
}

export function tileBuildingGeometry(
  map: MapLibreMap,
  point: { x: number; y: number },
): BuildingGeometry | null {
  const features = queryBuildingFeatures(map, point);
  for (const feature of features) {
    const geometry = asBuildingGeometry(feature);
    if (geometry) {
      return geometry;
    }
  }
  return null;
}

function addLayerIfMissing(
  map: MapLibreMap,
  layer: Parameters<MapLibreMap["addLayer"]>[0],
  beforeId?: string,
): void {
  if (map.getLayer(layer.id)) {
    return;
  }
  if (beforeId && map.getLayer(beforeId)) {
    map.addLayer(layer, beforeId);
    return;
  }
  map.addLayer(layer);
}

function removeHighlightLayers(map: MapLibreMap): void {
  for (const id of [BUILDING_3D_LAYER_ID, BUILDING_OUTLINE_LAYER_ID, BUILDING_LAYER_ID]) {
    if (map.getLayer(id)) {
      map.removeLayer(id);
    }
  }
  if (map.getSource(BUILDING_SOURCE_ID)) {
    map.removeSource(BUILDING_SOURCE_ID);
  }
}

export function ensureBuildingLayers(map: MapLibreMap): void {
  if (!map.getSource(BUILDING_SOURCE_ID)) {
    map.addSource(BUILDING_SOURCE_ID, {
      type: "geojson",
      data: emptyGeometryCollection() as GeoJSON.GeoJSON,
    });
  }
  addLayerIfMissing(map, {
    id: BUILDING_LAYER_ID,
    type: "fill",
    source: BUILDING_SOURCE_ID,
    paint: {
      "fill-color": "#00B341",
      "fill-opacity": 0.38,
      "fill-opacity-transition": { duration: 0 },
      "fill-color-transition": { duration: 0 },
    },
  });
  addLayerIfMissing(map, {
    id: BUILDING_OUTLINE_LAYER_ID,
    type: "line",
    source: BUILDING_SOURCE_ID,
    paint: {
      "line-color": "#008A32",
      "line-width": 3,
      "line-opacity-transition": { duration: 0 },
    },
  });
  try {
    addLayerIfMissing(
      map,
      {
        id: BUILDING_3D_LAYER_ID,
        type: "fill-extrusion",
        source: BUILDING_SOURCE_ID,
        minzoom: 15,
        paint: {
          "fill-extrusion-color": "#00B341",
          "fill-extrusion-opacity": 0.55,
          "fill-extrusion-height": 64,
          "fill-extrusion-base": 0,
          "fill-extrusion-opacity-transition": { duration: 0 },
        },
      },
      map.getLayer("housenumber") ? "housenumber" : undefined,
    );
  } catch {
    /* 2D fill/outline still work if extrusion cannot be inserted */
  }
}

function forceGeoJsonRepaint(map: MapLibreMap): void {
  map.triggerRepaint();
  try {
    map.panBy([1, 0], { duration: 0, animate: false });
    map.panBy([-1, 0], { duration: 0, animate: false });
  } catch {
    map.triggerRepaint();
  }
}

export function applyBuildingHighlight(map: MapLibreMap, geometry: BuildingGeometry | null): void {
  try {
    removeHighlightLayers(map);
    map.addSource(BUILDING_SOURCE_ID, {
      type: "geojson",
      data: JSON.parse(
        JSON.stringify(geometry ? geometryCollection(geometry) : emptyGeometryCollection()),
      ) as GeoJSON.GeoJSON,
    });
    ensureBuildingLayers(map);
  } catch {
    try {
      ensureBuildingLayers(map);
      const source = map.getSource(BUILDING_SOURCE_ID) as GeoJSONSource | undefined;
      source?.setData(
        JSON.parse(
          JSON.stringify(geometry ? geometryCollection(geometry) : emptyGeometryCollection()),
        ) as GeoJSON.GeoJSON,
      );
    } catch {
      return;
    }
  }
  forceGeoJsonRepaint(map);
}

export function paintBuildingHighlight(geometry: BuildingGeometry | null): void {
  const map = window.__zylosMap;
  if (!map) {
    return;
  }
  if (!geometry) {
    clearTileBuildingSelection(map);
  }
  applyBuildingHighlight(map, geometry);
}
