import { useEffect, useLayoutEffect, useRef } from "react";
import maplibregl, { type GeoJSONSource, type Map as MapLibreMap, type StyleSpecification } from "maplibre-gl";
import {
  DEFAULT_CENTER,
  DEFAULT_ZOOM,
  FLY_DURATION_MS,
  MARKER_LAYER_ID,
  MARKER_SOURCE_ID,
  ORG_PINS_LAYER_ID,
  ORG_PINS_SOURCE_ID,
  POI_LAYERS,
  SHEET_ANIMATION_MS,
} from "../lib/constants";
import { applyBuildingHighlight, enableTileBuildingSelectionPaint, ensureBuildingLayers, queryPointFromGeometry, selectTileBuildingAt } from "../lib/buildingHighlight";
import { poiName } from "../lib/poi";
import type { BuildingGeometry } from "../types/building";
import type { OrgPin } from "../types/org";
import type { Selection } from "../types/app";

type PointCollection = {
  type: "FeatureCollection";
  features: Array<{
    type: "Feature";
    properties: Record<string, never>;
    geometry: { type: "Point"; coordinates: [number, number] };
  }>;
};

export interface PoiPick {
  name: string;
  lon: number;
  lat: number;
}

export interface MapViewChange {
  lon: number;
  lat: number;
  zoom: number;
  bbox: { minLon: number; minLat: number; maxLon: number; maxLat: number };
}

interface MapViewProps {
  style: StyleSpecification | null;
  styleError: string | null;
  selection: Selection | null;
  buildingGeometry: BuildingGeometry | null;
  orgPins: OrgPin[];
  flyNonce: number;
  onMapClick: (
    lon: number,
    lat: number,
    poi: PoiPick | null,
    pin?: { lon: number; lat: number },
  ) => void;
  onViewChange: (view: MapViewChange) => void;
}

function emptyPointCollection(): PointCollection {
  return { type: "FeatureCollection", features: [] };
}

function pointCollection(lon: number, lat: number): PointCollection {
  return {
    type: "FeatureCollection",
    features: [
      {
        type: "Feature",
        properties: {} as Record<string, never>,
        geometry: { type: "Point", coordinates: [lon, lat] },
      },
    ],
  };
}

function visibleLayerIds(map: MapLibreMap, ids: readonly string[]): string[] {
  return ids.filter((id) => {
    if (!map.getLayer(id)) {
      return false;
    }
    try {
      return map.getLayoutProperty(id, "visibility") !== "none";
    } catch {
      return true;
    }
  });
}

function pinsCollection(pins: OrgPin[]): PointCollection {
  return {
    type: "FeatureCollection",
    features: pins.map((pin) => ({
      type: "Feature" as const,
      properties: {} as Record<string, never>,
      geometry: { type: "Point" as const, coordinates: [pin.lon, pin.lat] as [number, number] },
    })),
  };
}

function ensureSources(map: MapLibreMap): void {
  if (!map.getSource(ORG_PINS_SOURCE_ID)) {
    map.addSource(ORG_PINS_SOURCE_ID, {
      type: "geojson",
      data: emptyPointCollection(),
    });
  }
  if (!map.getLayer(ORG_PINS_LAYER_ID)) {
    map.addLayer({
      id: ORG_PINS_LAYER_ID,
      type: "circle",
      source: ORG_PINS_SOURCE_ID,
      paint: {
        "circle-radius": 4.5,
        "circle-color": "#00B341",
        "circle-stroke-width": 1.4,
        "circle-stroke-color": "#FFFFFF",
        "circle-opacity": 0.92,
      },
    });
  }
  ensureBuildingLayers(map);
  if (!map.getSource(MARKER_SOURCE_ID)) {
    map.addSource(MARKER_SOURCE_ID, {
      type: "geojson",
      data: emptyPointCollection(),
    });
  }
  if (!map.getLayer(MARKER_LAYER_ID)) {
    map.addLayer({
      id: MARKER_LAYER_ID,
      type: "circle",
      source: MARKER_SOURCE_ID,
      paint: {
        "circle-radius": 8,
        "circle-color": "#00B341",
        "circle-stroke-width": 2.2,
        "circle-stroke-color": "#FFFFFF",
      },
    });
  }
}

export function MapView({
  style,
  styleError,
  selection,
  buildingGeometry,
  orgPins,
  flyNonce,
  onMapClick,
  onViewChange,
}: MapViewProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<MapLibreMap | null>(null);
  const onMapClickRef = useRef(onMapClick);
  const onViewChangeRef = useRef(onViewChange);
  const flyNonceRef = useRef(0);
  const selectionRef = useRef(selection);
  const flyRequestRef = useRef(flyNonce);
  const buildingGeometryRef = useRef(buildingGeometry);
  const orgPinsRef = useRef(orgPins);
  const lastMarkerKeyRef = useRef<string | null>(null);

  onMapClickRef.current = onMapClick;
  onViewChangeRef.current = onViewChange;
  selectionRef.current = selection;
  flyRequestRef.current = flyNonce;
  buildingGeometryRef.current = buildingGeometry;
  orgPinsRef.current = orgPins;

  useEffect(() => {
    if (!style || !containerRef.current || mapRef.current) {
      return;
    }

    window.__zylosPerf = window.__zylosPerf ?? {
      navigationStart: performance.now(),
      mapCreatedAt: null,
      roadsAt: null,
      buildingsAt: null,
      ttiAt: null,
      lastInputMs: null,
      sheetOpenedMs: null,
      glyphsWaitMs: null,
      clickStartedAt: null,
      buildingListAt: null,
    };
    window.__zylosMapError = null;
    window.__zylosPerf.mapCreatedAt = performance.now();

    const map = new maplibregl.Map({
      container: containerRef.current,
      style,
      center: [DEFAULT_CENTER.lon, DEFAULT_CENTER.lat],
      zoom: DEFAULT_ZOOM,
      minZoom: 10,
      maxZoom: 18,
      maxPitch: 60,
      fadeDuration: 0,
      doubleClickZoom: false,
      maplibreLogo: false,
      attributionControl: {
        compact: true,
        customAttribution: "",
      },
    });
    mapRef.current = map;
    window.__zylosMap = map;

    map.addControl(new maplibregl.NavigationControl({ visualizePitch: true }), "bottom-right");
    map.addControl(new maplibregl.ScaleControl({ unit: "metric" }), "bottom-left");

    const recordLayers = (): void => {
      const perf = window.__zylosPerf;
      if (!perf) {
        return;
      }
      if (perf.roadsAt == null && map.getLayer("roads")) {
        if (map.queryRenderedFeatures({ layers: ["roads"] }).length > 0) {
          perf.roadsAt = performance.now();
        }
      }
      if (perf.buildingsAt == null && map.getLayer("buildings")) {
        if (map.queryRenderedFeatures({ layers: ["buildings"] }).length > 0) {
          perf.buildingsAt = performance.now();
        }
      }
    };

    map.on("error", (event) => {
      const message = event.error?.message || String(event.error || "unknown");
      window.__zylosMapError = message;
    });

    const flyToSelection = (): void => {
      const current = selectionRef.current;
      const nonce = flyRequestRef.current;
      if (!current || nonce === 0 || nonce === flyNonceRef.current) {
        return;
      }
      flyNonceRef.current = nonce;
      map.flyTo({
        center: [current.lon, current.lat],
        zoom: Math.max(map.getZoom(), 16),
        duration: FLY_DURATION_MS,
      });
    };

    const emitView = (): void => {
      const center = map.getCenter();
      const bounds = map.getBounds();
      onViewChangeRef.current({
        lon: center.lng,
        lat: center.lat,
        zoom: map.getZoom(),
        bbox: {
          minLon: bounds.getWest(),
          minLat: bounds.getSouth(),
          maxLon: bounds.getEast(),
          maxLat: bounds.getNorth(),
        },
      });
    };

    map.on("load", () => {
      ensureSources(map);
      enableTileBuildingSelectionPaint(map);
      applyBuildingHighlight(map, buildingGeometryRef.current);
      const pinSource = map.getSource(ORG_PINS_SOURCE_ID) as GeoJSONSource | undefined;
      if (pinSource) {
        pinSource.setData(pinsCollection(orgPinsRef.current));
      }
      flyToSelection();
      emitView();
      if (window.__zylosPerf) {
        window.__zylosPerf.ttiAt = performance.now();
      }
    });

    const onRender = (): void => {
      recordLayers();
      if (window.__zylosPerf?.roadsAt != null && window.__zylosPerf?.buildingsAt != null) {
        map.off("render", onRender);
      }
    };
    map.on("render", onRender);

    const poiLayerList = [...POI_LAYERS];
    for (const layerId of poiLayerList) {
      map.on("mouseenter", layerId, () => {
        map.getCanvas().style.cursor = "pointer";
      });
      map.on("mouseleave", layerId, () => {
        map.getCanvas().style.cursor = "";
      });
    }

    map.on("click", (event) => {
      const poiLayers = visibleLayerIds(map, poiLayerList);
      const pois = poiLayers.length
        ? map.queryRenderedFeatures(event.point, { layers: poiLayers })
        : [];
      const poiFeature = pois[0];
      const poi: PoiPick | null = poiFeature
        ? {
            name: poiName(poiFeature.properties as Record<string, unknown> | null),
            lon:
              poiFeature.geometry.type === "Point"
                ? (poiFeature.geometry.coordinates as [number, number])[0]
                : event.lngLat.lng,
            lat:
              poiFeature.geometry.type === "Point"
                ? (poiFeature.geometry.coordinates as [number, number])[1]
                : event.lngLat.lat,
          }
        : null;

      const pin = { lon: event.lngLat.lng, lat: event.lngLat.lat };
      ensureSources(map);
      const markerSource = map.getSource(MARKER_SOURCE_ID) as GeoJSONSource | undefined;
      if (markerSource) {
        markerSource.setData(pointCollection(pin.lon, pin.lat));
        lastMarkerKeyRef.current = `${pin.lon},${pin.lat}`;
      }

      const tileGeom = selectTileBuildingAt(map, event.point);
      if (tileGeom) {
        applyBuildingHighlight(map, tileGeom);
      }
      map.triggerRepaint();

      if (window.__zylosPerf) {
        window.__zylosPerf.clickStartedAt = performance.now();
        window.__zylosPerf.buildingListAt = null;
      }
      const query = tileGeom ? queryPointFromGeometry(tileGeom, pin) : pin;
      onMapClickRef.current(query.lon, query.lat, poi, pin);
    });

    map.on("moveend", emitView);

    return () => {
      map.remove();
      mapRef.current = null;
      if (window.__zylosMap === map) {
        delete window.__zylosMap;
      }
    };
  }, [style]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map) {
      return;
    }

    const applyMarker = (): void => {
      ensureSources(map);
      const source = map.getSource(MARKER_SOURCE_ID) as GeoJSONSource | undefined;
      if (!source) {
        return;
      }
      const key = selection ? `${selection.lon},${selection.lat}` : "";
      if (key === lastMarkerKeyRef.current) {
        return;
      }
      lastMarkerKeyRef.current = key;
      if (selection) {
        source.setData(pointCollection(selection.lon, selection.lat));
      } else {
        source.setData(emptyPointCollection());
      }
      map.triggerRepaint();
    };

    const run = (): void => {
      applyMarker();
    };

    if (selection) {
      run();
      return;
    }
    const timer = window.setTimeout(run, SHEET_ANIMATION_MS);
    return () => window.clearTimeout(timer);
  }, [selection]);

  useLayoutEffect(() => {
    const map = mapRef.current;
    if (!map) {
      return;
    }
    if (buildingGeometry) {
      applyBuildingHighlight(map, buildingGeometry);
      return;
    }
  }, [buildingGeometry]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map) {
      return;
    }
    const applyPins = (): void => {
      ensureSources(map);
      const source = map.getSource(ORG_PINS_SOURCE_ID) as GeoJSONSource | undefined;
      if (!source) {
        return;
      }
      source.setData(pinsCollection(orgPins));
    };
    applyPins();
  }, [orgPins]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map) {
      return;
    }
    const current = selection;
    const nonce = flyNonce;
    if (!current || nonce === 0 || nonce === flyNonceRef.current) {
      return;
    }
    const fly = (): void => {
      if (nonce === flyNonceRef.current) {
        return;
      }
      flyNonceRef.current = nonce;
      map.flyTo({
        center: [current.lon, current.lat],
        zoom: Math.max(map.getZoom(), 16),
        duration: FLY_DURATION_MS,
      });
    };
    fly();
  }, [selection, flyNonce]);

  return (
    <div className="map-root">
      <div ref={containerRef} className="map-canvas" data-testid="map" />
      {styleError ? <div className="map-error">Карта: {styleError}</div> : null}
    </div>
  );
}
