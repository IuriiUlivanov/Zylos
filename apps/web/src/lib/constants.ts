export const DEFAULT_CENTER = { lon: 19.845, lat: 45.255 } as const;
export const DEFAULT_ZOOM = 14;
export const SEARCH_DEBOUNCE_MS = 150;
export const SEARCH_MIN_LENGTH = 2;
export const SEARCH_LIMIT = 15;
export const FLY_DURATION_MS = 800;
export const SHEET_ANIMATION_MS = 250;
export const SHEET_PEEK_PX = 88;
export const SHEET_BUILDING_PX = 120;
export const ORG_PINS_DEBOUNCE_MS = 300;
export const ORG_PINS_MIN_ZOOM = 15;
export const ORG_PINS_LIMIT = 200;

export const API_URL = (import.meta.env.VITE_API_URL || "http://127.0.0.1:3000").replace(
  /\/$/,
  "",
);
export const TILES_URL = (import.meta.env.VITE_TILES_URL || "http://127.0.0.1:8080").replace(
  /\/$/,
  "",
);

export const POI_LAYERS = ["poi-dot", "poi-label"] as const;
export const BUILDING_TILE_LAYERS = [
  "buildings",
  "buildings-3d",
  "selected-building",
  "selected-building-3d",
] as const;
export const MARKER_LAYER_ID = "selected-marker";
export const MARKER_SOURCE_ID = "selected-marker";
export const BUILDING_SOURCE_ID = "selected-building";
export const BUILDING_LAYER_ID = "selected-building";
export const BUILDING_OUTLINE_LAYER_ID = "selected-building-outline";
export const BUILDING_3D_LAYER_ID = "selected-building-3d";
export const ORG_PINS_SOURCE_ID = "org-pins";
export const ORG_PINS_LAYER_ID = "org-pins";
