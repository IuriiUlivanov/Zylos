import { useEffect, useRef, useState } from "react";
import { ORG_PINS_DEBOUNCE_MS, ORG_PINS_MIN_ZOOM } from "../lib/constants";
import { fetchOrgPins, type OrgBbox } from "../lib/orgApi";
import type { OrgPin } from "../types/org";

export interface MapViewState {
  lon: number;
  lat: number;
  zoom: number;
  bbox: OrgBbox;
}

export function useOrgPins(view: MapViewState | null): OrgPin[] {
  const [pins, setPins] = useState<OrgPin[]>([]);
  const abortRef = useRef<AbortController | null>(null);
  const requestIdRef = useRef(0);

  useEffect(() => {
    if (!view || view.zoom < ORG_PINS_MIN_ZOOM) {
      requestIdRef.current += 1;
      abortRef.current?.abort();
      abortRef.current = null;
      setPins([]);
      return;
    }

    const timer = window.setTimeout(() => {
      const requestId = requestIdRef.current + 1;
      requestIdRef.current = requestId;
      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;
      fetchOrgPins(view.bbox, controller.signal)
        .then((next) => {
          if (requestId !== requestIdRef.current) {
            return;
          }
          setPins(next);
        })
        .catch((err: unknown) => {
          if (err instanceof DOMException && err.name === "AbortError") {
            return;
          }
          if (requestId !== requestIdRef.current) {
            return;
          }
          setPins([]);
        });
    }, ORG_PINS_DEBOUNCE_MS);

    return () => {
      window.clearTimeout(timer);
    };
  }, [view?.zoom, view?.bbox.minLon, view?.bbox.minLat, view?.bbox.maxLon, view?.bbox.maxLat]);

  useEffect(() => {
    return () => {
      abortRef.current?.abort();
    };
  }, []);

  return pins;
}
