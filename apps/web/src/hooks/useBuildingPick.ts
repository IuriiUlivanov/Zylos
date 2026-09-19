import { useCallback, useEffect, useRef, useState } from "react";
import { fetchBuilding, fetchBuildingAt } from "../lib/buildingApi";
import { paintBuildingHighlight } from "../lib/buildingHighlight";
import { fetchOrg } from "../lib/orgApi";
import { buildingSelection, clickSelection, orgSelection } from "../lib/selection";
import type { Selection } from "../types/app";
import {
  BuildingNotFoundError,
  OutsideCityError,
  type BuildingDetail,
} from "../types/building";
import { OrgNotFoundError, type OrgDetail } from "../types/org";

export type BuildingSheetMode = "closed" | "building" | "organization";

export function useBuildingPick(): {
  building: BuildingDetail | null;
  org: OrgDetail | null;
  mode: BuildingSheetMode;
  selection: Selection | null;
  loading: boolean;
  toast: string | null;
  pickAt: (
    lon: number,
    lat: number,
    opts?: { skipMissingToast?: boolean; pin?: { lon: number; lat: number } },
  ) => Promise<"found" | "missing" | "outside" | "aborted">;
  loadBuilding: (id: string) => Promise<BuildingDetail | null>;
  loadOrg: (id: string) => Promise<OrgDetail | null>;
  clear: () => void;
  clearToast: () => void;
  backToBuilding: () => void;
} {
  const [building, setBuilding] = useState<BuildingDetail | null>(null);
  const [org, setOrg] = useState<OrgDetail | null>(null);
  const [clickPin, setClickPin] = useState<{ lon: number; lat: number } | null>(null);
  const [mode, setMode] = useState<BuildingSheetMode>("closed");
  const [loading, setLoading] = useState(false);
  const [toast, setToast] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const requestIdRef = useRef(0);

  const abortInFlight = useCallback(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    requestIdRef.current += 1;
  }, []);

  const clear = useCallback(() => {
    abortInFlight();
    setBuilding(null);
    setOrg(null);
    setClickPin(null);
    setMode("closed");
    setLoading(false);
    paintBuildingHighlight(null);
  }, [abortInFlight]);

  const clearToast = useCallback(() => setToast(null), []);

  const backToBuilding = useCallback(() => {
    setOrg(null);
    setMode(building ? "building" : "closed");
  }, [building]);

  const loadBuilding = useCallback(async (id: string): Promise<BuildingDetail | null> => {
    abortInFlight();
    const requestId = requestIdRef.current;
    const controller = new AbortController();
    abortRef.current = controller;
    setClickPin(null);
    setLoading(true);
    try {
      const detail = await fetchBuilding(id, controller.signal);
      if (requestId !== requestIdRef.current) {
        return null;
      }
      setBuilding(detail);
      setOrg(null);
      setMode("building");
      setLoading(false);
      paintBuildingHighlight(detail.geometry);
      return detail;
    } catch (err) {
      if (err instanceof DOMException && err.name === "AbortError") {
        return null;
      }
      if (requestId !== requestIdRef.current) {
        return null;
      }
      setLoading(false);
      if (err instanceof BuildingNotFoundError) {
        setToast("Nema zgrade");
        setBuilding(null);
        setOrg(null);
        setClickPin(null);
        setMode("closed");
        paintBuildingHighlight(null);
      }
      return null;
    }
  }, [abortInFlight]);

  const loadOrg = useCallback(async (id: string): Promise<OrgDetail | null> => {
    const controller = new AbortController();
    abortRef.current?.abort();
    abortRef.current = controller;
    const requestId = (requestIdRef.current += 1);
    setLoading(true);
    try {
      const detail = await fetchOrg(id, controller.signal);
      if (requestId !== requestIdRef.current) {
        return null;
      }
      setOrg(detail);
      setMode("organization");
      if (detail.building_id) {
        try {
          const bldg = await fetchBuilding(detail.building_id, controller.signal);
          if (requestId !== requestIdRef.current) {
            return detail;
          }
          setBuilding(bldg);
          paintBuildingHighlight(bldg.geometry);
        } catch {
          /* org card still works without contour */
        }
      }
      setLoading(false);
      return detail;
    } catch (err) {
      if (err instanceof DOMException && err.name === "AbortError") {
        return null;
      }
      if (requestId !== requestIdRef.current) {
        return null;
      }
      setLoading(false);
      if (err instanceof OrgNotFoundError) {
        setToast("Organizacija nije pronađena");
      }
      return null;
    }
  }, []);

  const pickAt = useCallback(
    async (
      lon: number,
      lat: number,
      opts?: { skipMissingToast?: boolean; pin?: { lon: number; lat: number } },
    ): Promise<"found" | "missing" | "outside" | "aborted"> => {
      abortInFlight();
      const requestId = requestIdRef.current;
      const controller = new AbortController();
      abortRef.current = controller;
      setClickPin(opts?.pin ?? { lon, lat });
      setLoading(true);
      setOrg(null);
      try {
        const at = await fetchBuildingAt(lon, lat, controller.signal);
        if (requestId !== requestIdRef.current) {
          return "aborted";
        }
        const detail =
          at.geometry && Array.isArray(at.organizations)
            ? (at as BuildingDetail)
            : await fetchBuilding(at.id, controller.signal);
        if (requestId !== requestIdRef.current) {
          return "aborted";
        }
        setBuilding(detail);
        setOrg(null);
        setMode("building");
        setLoading(false);
        paintBuildingHighlight(detail.geometry ?? null);
        return "found";
      } catch (err) {
        if (err instanceof DOMException && err.name === "AbortError") {
          return "aborted";
        }
        if (requestId !== requestIdRef.current) {
          return "aborted";
        }
        setLoading(false);
        if (err instanceof OutsideCityError) {
          setToast("Van granice grada");
          setBuilding(null);
          setOrg(null);
          setClickPin(null);
          setMode("closed");
          paintBuildingHighlight(null);
          return "outside";
        }
        if (err instanceof BuildingNotFoundError) {
          if (!opts?.skipMissingToast) {
            setToast("Nema zgrade");
          }
          setBuilding(null);
          setOrg(null);
          setClickPin(null);
          setMode("closed");
          paintBuildingHighlight(null);
          return "missing";
        }
        setBuilding(null);
        setOrg(null);
        setClickPin(null);
        setMode("closed");
        paintBuildingHighlight(null);
        return "missing";
      }
    },
    [abortInFlight],
  );

  useEffect(() => {
    return () => {
      abortRef.current?.abort();
    };
  }, []);

  const selection: Selection | null = org
    ? orgSelection(org)
    : building
      ? buildingSelection(building, clickPin)
      : clickPin
        ? clickSelection(clickPin.lon, clickPin.lat)
        : null;

  return {
    building,
    org,
    mode,
    selection,
    loading,
    toast,
    pickAt,
    loadBuilding,
    loadOrg,
    clear,
    clearToast,
    backToBuilding,
  };
}
