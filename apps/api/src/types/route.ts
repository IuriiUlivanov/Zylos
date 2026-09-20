export type RouteMode = "transit";
export type RouteLegMode = "walk" | "transit";

export interface RouteLonLat {
  lon: number;
  lat: number;
}

export interface RouteRequest {
  from: RouteLonLat;
  to: RouteLonLat;
  mode: RouteMode;
}

export interface RouteLineString {
  type: "LineString";
  coordinates: [number, number][];
}

export interface RouteLeg {
  mode: RouteLegMode;
  duration_sec: number;
  distance_m: number;
  geometry: RouteLineString;
  route_short_name?: string;
  route_color?: string;
  from_stop_name?: string;
  to_stop_name?: string;
  headsign?: string;
}

export interface RouteItinerary {
  duration_sec: number;
  distance_m: number;
  transfers: number;
  walk_duration_sec: number;
  legs: RouteLeg[];
}

/** Canonical 200 body: top-level fields duplicate itineraries[0] (best after sort). */
export interface RouteResponse {
  mode: RouteMode;
  duration_sec: number;
  distance_m: number;
  transfers: number;
  legs: RouteLeg[];
  itineraries: RouteItinerary[];
}

export type RouteErrorCode =
  | "invalid_request"
  | "outside_city"
  | "no_route"
  | "routing_timeout";

export interface RouteErrorBody {
  error: RouteErrorCode;
}
