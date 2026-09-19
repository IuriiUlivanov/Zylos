export type SheetState = "closed" | "peek" | "building" | "organization";

export type Selection =
  | {
      type: "hit";
      id: string;
      title: string;
      subtitle: string;
      kind: "address" | "organization";
      lat: number;
      lon: number;
      buildingId: string | null;
    }
  | {
      type: "poi";
      id: string;
      title: string;
      subtitle: string;
      lat: number;
      lon: number;
    }
  | {
      type: "building";
      id: string;
      title: string;
      subtitle: string;
      lat: number;
      lon: number;
    }
  | {
      type: "organization";
      id: string;
      title: string;
      subtitle: string;
      lat: number;
      lon: number;
      buildingId: string | null;
    };

export interface ZylosAppState {
  query: string;
  hitsQuery: string;
  hitIds: string[];
  sheet: SheetState;
  selectionType: Selection["type"] | null;
  selectionTitle: string | null;
  buildingId: string | null;
  orgId: string | null;
  orgCount: number;
  toast: string | null;
  error: string | null;
}

export interface ZylosPerf {
  navigationStart: number;
  mapCreatedAt: number | null;
  roadsAt: number | null;
  buildingsAt: number | null;
  ttiAt: number | null;
  lastInputMs: number | null;
  sheetOpenedMs: number | null;
  glyphsWaitMs: number | null;
  clickStartedAt: number | null;
  buildingListAt: number | null;
}

declare global {
  interface Window {
    __zylosMap?: import("maplibre-gl").Map;
    __zylosMapError?: string | null;
    __zylosApp?: ZylosAppState;
    __zylosPerf?: ZylosPerf;
    __zylosPickAt?: (lon: number, lat: number) => Promise<"found" | "missing" | "outside" | "aborted">;
  }
}

export {};
