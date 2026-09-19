import { useMemo } from "react";
import type { StyleSpecification } from "maplibre-gl";
import { TILES_URL } from "../lib/constants";
import { loadMapStyle } from "../lib/mapStyle";

export function useMapStyle(): {
  style: StyleSpecification | null;
  error: string | null;
} {
  const style = useMemo(() => loadMapStyle(TILES_URL), []);
  return { style, error: null };
}
