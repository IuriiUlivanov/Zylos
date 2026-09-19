import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { BottomSheet } from "./components/BottomSheet";
import { MapView, type PoiPick } from "./components/MapView";
import { OrgPinLayer } from "./components/OrgPinLayer";
import { SearchBar } from "./components/SearchBar";
import { useBuildingPick } from "./hooks/useBuildingPick";
import { useMapStyle } from "./hooks/useMapStyle";
import { useOrgPins, type MapViewState } from "./hooks/useOrgPins";
import { useSearch } from "./hooks/useSearch";
import { DEFAULT_CENTER, FLY_DURATION_MS } from "./lib/constants";
import { hitSelection } from "./lib/selection";
import { readUrlState, writeUrlState } from "./lib/urlState";
import type { Selection, SheetState } from "./types/app";
import type { SearchHit } from "./types/search";

export function App() {
  const initial = useMemo(() => readUrlState(), []);
  const { style, error: styleError } = useMapStyle();
  const [query, setQuery] = useState(initial.q);
  const [mapCenter, setMapCenter] = useState<{ lon: number; lat: number }>(DEFAULT_CENTER);
  const [mapView, setMapView] = useState<MapViewState | null>(null);
  const [peekSelection, setPeekSelection] = useState<Selection | null>(null);
  const [selId, setSelId] = useState<string | null>(initial.sel);
  const [sheet, setSheet] = useState<SheetState>("closed");
  const [searchFocused, setSearchFocused] = useState(false);
  const [flyNonce, setFlyNonce] = useState(0);
  const pendingSelRef = useRef<string | null>(initial.sel);
  const hydratedSelRef = useRef(Boolean(initial.bldg || initial.org));
  const hydratedBldgRef = useRef(false);

  const {
    building,
    org,
    mode: pickMode,
    selection: pickSelection,
    loading: loadingBuilding,
    toast,
    pickAt,
    loadBuilding,
    loadOrg,
    clear: clearBuilding,
    clearToast,
    backToBuilding,
  } = useBuildingPick();

  const orgPins = useOrgPins(mapView);

  const { hits, hitsQuery, loading, error, errorMessage } = useSearch({
    query,
    lat: mapCenter.lat,
    lon: mapCenter.lon,
  });

  const flyTo = useCallback((lon: number, lat: number) => {
    setFlyNonce((n) => n + 1);
    const map = window.__zylosMap;
    if (map) {
      map.flyTo({
        center: [lon, lat],
        zoom: Math.max(map.getZoom(), 16),
        duration: FLY_DURATION_MS,
      });
    }
  }, []);

  const closeSheet = useCallback(() => {
    setSheet("closed");
    setPeekSelection(null);
    setSelId(null);
    clearBuilding();
    pendingSelRef.current = null;
    hydratedSelRef.current = true;
  }, [clearBuilding]);

  const selectHit = useCallback(
    (hit: SearchHit, fly: boolean) => {
      pendingSelRef.current = null;
      hydratedSelRef.current = true;
      setSearchFocused(false);
      setSelId(hit.id);
      if (fly) {
        flyTo(hit.lon, hit.lat);
      }
      if (hit.building_id) {
        setPeekSelection(null);
        setSheet("building");
        void loadBuilding(hit.building_id).then((detail) => {
          if (!detail) {
            setPeekSelection(hitSelection(hit));
            setSheet("peek");
          }
        });
        return;
      }
      clearBuilding();
      setPeekSelection(hitSelection(hit));
      setSheet("peek");
    },
    [clearBuilding, flyTo, loadBuilding],
  );

  useEffect(() => {
    const pending = pendingSelRef.current;
    if (!pending || hydratedSelRef.current) {
      return;
    }
    const hit = hits.find((item) => item.id === pending);
    if (hit) {
      selectHit(hit, true);
    }
  }, [hits, selectHit]);

  useEffect(() => {
    if (hydratedBldgRef.current) {
      return;
    }
    hydratedBldgRef.current = true;
    if (initial.org) {
      void loadOrg(initial.org).then((item) => {
        if (item) {
          setSheet("organization");
          flyTo(item.location.lon, item.location.lat);
        } else if (initial.bldg) {
          void loadBuilding(initial.bldg).then((detail) => {
            if (detail) {
              setSheet("building");
              flyTo(detail.centroid.lon, detail.centroid.lat);
            }
          });
        }
      });
      return;
    }
    if (initial.bldg) {
      void loadBuilding(initial.bldg).then((detail) => {
        if (detail) {
          setSheet("building");
          flyTo(detail.centroid.lon, detail.centroid.lat);
        }
      });
    }
  }, [flyTo, initial.bldg, initial.org, loadBuilding, loadOrg]);

  const sheetMode: SheetState =
    pickMode === "organization" ? "organization" : pickMode === "building" ? "building" : sheet;

  const selection: Selection | null =
    sheetMode === "organization" || sheetMode === "building" ? pickSelection : peekSelection;

  useEffect(() => {
    writeUrlState({
      q: query,
      sel: selId,
      bldg: building?.id ?? null,
      org: sheetMode === "organization" && org ? org.id : null,
    });
  }, [query, selId, building, org, sheetMode]);

  useEffect(() => {
    if (!toast) {
      return;
    }
    const timer = window.setTimeout(() => clearToast(), 2500);
    return () => window.clearTimeout(timer);
  }, [toast, clearToast]);

  useEffect(() => {
    window.__zylosApp = {
      query,
      hitsQuery,
      hitIds: hits.map((hit) => hit.id),
      sheet: sheetMode,
      selectionType: selection?.type ?? null,
      selectionTitle: selection?.title ?? building?.name ?? building?.addresses[0]?.label ?? null,
      buildingId: building?.id ?? null,
      orgId: org?.id ?? null,
      orgCount: building?.organizations.length ?? 0,
      toast,
      error: errorMessage,
    };
    window.__zylosPickAt = pickAt;
  }, [query, hitsQuery, hits, sheetMode, selection, building, org, toast, errorMessage, pickAt]);

  const dropdownOpen =
    query.length >= 2 &&
    (searchFocused || selection === null) &&
    (loading || hits.length > 0 || error !== null);

  return (
    <div className="app">
      <header className="app__search">
        <SearchBar
          query={query}
          hits={hits}
          hitsQuery={hitsQuery}
          loading={loading}
          error={error}
          errorMessage={errorMessage}
          dropdownOpen={dropdownOpen}
          onQueryChange={(value) => {
            setQuery(value);
            pendingSelRef.current = null;
            if (peekSelection?.type === "hit" && sheetMode === "peek") {
              setPeekSelection(null);
              setSelId(null);
              setSheet("closed");
            }
          }}
          onSelect={(hit) => selectHit(hit, true)}
          onFocus={() => setSearchFocused(true)}
          onBlur={() => {
            window.setTimeout(() => setSearchFocused(false), 120);
          }}
        />
      </header>
      <MapView
        style={style}
        styleError={styleError}
        selection={selection}
        buildingGeometry={sheetMode === "peek" ? null : building?.geometry ?? null}
        orgPins={orgPins}
        flyNonce={flyNonce}
        onMapClick={(lon, lat, poi: PoiPick | null, pin) => {
          setSearchFocused(false);
          setPeekSelection(null);
          setSelId(null);
          setSheet("building");
          void pickAt(lon, lat, { skipMissingToast: Boolean(poi), pin }).then((result) => {
            if (result === "found") {
              setSheet("building");
              return;
            }
            if (result === "aborted") {
              return;
            }
            if (poi) {
              setPeekSelection({
                type: "poi",
                id: `poi:${poi.lon.toFixed(5)},${poi.lat.toFixed(5)}`,
                title: poi.name,
                subtitle: "Iz mape OpenStreetMap",
                lat: poi.lat,
                lon: poi.lon,
              });
              setSheet("peek");
              return;
            }
            setSheet("closed");
          });
        }}
        onViewChange={(view) => {
          setMapCenter({ lon: view.lon, lat: view.lat });
          setMapView(view);
        }}
      />
      <BottomSheet
        sheet={sheetMode}
        selection={selection}
        building={building}
        org={org}
        loadingBuilding={loadingBuilding && (sheetMode === "building" || sheet === "building")}
        onClose={closeSheet}
        onBack={() => {
          if (building) {
            backToBuilding();
            setSheet("building");
            return;
          }
          closeSheet();
        }}
        onSelectOrg={(id) => {
          void loadOrg(id).then((item) => {
            if (item) {
              setSheet("organization");
            }
          });
        }}
      />
      <OrgPinLayer pins={orgPins} />
      {toast ? (
        <div className="toast" data-testid="toast" role="status">
          {toast}
        </div>
      ) : null}
    </div>
  );
}
