import type { RouteItinerary, RouteLeg, RouteLegMode, RouteLineString } from "../types/route.js";

export const ROUTE_MAX_JSON_BYTES = 200_000;

const TRANSIT_MODES = new Set([
  "BUS",
  "TRAM",
  "RAIL",
  "SUBWAY",
  "FERRY",
  "GONDOLA",
  "CABLE_CAR",
  "FUNICULAR",
  "TROLLEYBUS",
  "TRANSIT",
  "MONORAIL",
]);

export function decodePolyline(encoded: string, precision = 5): [number, number][] {
  const coordinates: [number, number][] = [];
  let index = 0;
  let lat = 0;
  let lon = 0;
  const factor = 10 ** precision;

  while (index < encoded.length) {
    let result = 0;
    let shift = 0;
    let byte: number;
    do {
      byte = encoded.charCodeAt(index++) - 63;
      result |= (byte & 0x1f) << shift;
      shift += 5;
    } while (byte >= 0x20 && index < encoded.length);
    lat += result & 1 ? ~(result >> 1) : result >> 1;

    result = 0;
    shift = 0;
    do {
      byte = encoded.charCodeAt(index++) - 63;
      result |= (byte & 0x1f) << shift;
      shift += 5;
    } while (byte >= 0x20 && index < encoded.length);
    lon += result & 1 ? ~(result >> 1) : result >> 1;

    coordinates.push([lon / factor, lat / factor]);
  }
  return coordinates;
}

export function simplifyLine(coords: [number, number][], maxPoints: number): [number, number][] {
  if (coords.length <= maxPoints || maxPoints < 2) {
    return coords;
  }
  const step = (coords.length - 1) / (maxPoints - 1);
  const out: [number, number][] = [];
  for (let i = 0; i < maxPoints - 1; i += 1) {
    out.push(coords[Math.round(i * step)]!);
  }
  out.push(coords[coords.length - 1]!);
  return out;
}

export function normalizeRouteColor(raw: unknown): string | undefined {
  if (typeof raw !== "string") {
    return undefined;
  }
  const hex = raw.trim().replace(/^#/, "").toUpperCase();
  if (/^[0-9A-F]{6}$/.test(hex)) {
    return hex;
  }
  return undefined;
}

function asFinite(value: unknown): number | null {
  if (typeof value === "number" && Number.isFinite(value)) {
    return value;
  }
  if (typeof value === "string" && value.trim() !== "") {
    const parsed = Number(value);
    if (Number.isFinite(parsed)) {
      return parsed;
    }
  }
  return null;
}

function asString(value: unknown): string | undefined {
  if (typeof value === "string" && value.trim() !== "") {
    return value.trim();
  }
  return undefined;
}

function mapLegMode(raw: unknown): RouteLegMode {
  const mode = typeof raw === "string" ? raw.toUpperCase() : "";
  if (mode === "WALK" || mode === "BICYCLE" || mode === "CAR" || mode === "FOOT") {
    return "walk";
  }
  if (TRANSIT_MODES.has(mode) || mode.includes("BUS") || mode.includes("RAIL")) {
    return "transit";
  }
  return "transit";
}

function geometryFromLeg(leg: Record<string, unknown>): RouteLineString {
  const encoded = asString((leg.legGeometry as { points?: unknown } | undefined)?.points);
  if (encoded) {
    const coordinates = decodePolyline(encoded);
    if (coordinates.length >= 2) {
      return { type: "LineString", coordinates };
    }
  }
  const from = leg.from as { lon?: unknown; lat?: unknown } | undefined;
  const to = leg.to as { lon?: unknown; lat?: unknown } | undefined;
  const fromLon = asFinite(from?.lon);
  const fromLat = asFinite(from?.lat);
  const toLon = asFinite(to?.lon);
  const toLat = asFinite(to?.lat);
  const coordinates: [number, number][] = [];
  if (fromLon != null && fromLat != null) {
    coordinates.push([fromLon, fromLat]);
  }
  if (toLon != null && toLat != null) {
    coordinates.push([toLon, toLat]);
  }
  if (coordinates.length < 2) {
    coordinates.push([0, 0], [0, 0]);
  }
  return { type: "LineString", coordinates };
}

function mapLeg(raw: unknown): RouteLeg | null {
  if (!raw || typeof raw !== "object") {
    return null;
  }
  const leg = raw as Record<string, unknown>;
  const duration = asFinite(leg.duration) ?? 0;
  const distance = asFinite(leg.distance) ?? 0;
  const route = (leg.route as Record<string, unknown> | undefined) ?? {};
  const mapped: RouteLeg = {
    mode: mapLegMode(leg.mode),
    duration_sec: Math.max(0, Math.round(duration)),
    distance_m: Math.max(0, Math.round(distance)),
    geometry: geometryFromLeg(leg),
  };
  const shortName = asString(leg.routeShortName) ?? asString(route.shortName);
  if (shortName) {
    mapped.route_short_name = shortName;
  }
  const color = normalizeRouteColor(leg.routeColor ?? route.color);
  if (color) {
    mapped.route_color = color;
  }
  const fromName = asString((leg.from as { name?: unknown } | undefined)?.name);
  const toName = asString((leg.to as { name?: unknown } | undefined)?.name);
  if (fromName) {
    mapped.from_stop_name = fromName;
  }
  if (toName) {
    mapped.to_stop_name = toName;
  }
  const headsign = asString(leg.headsign);
  if (headsign) {
    mapped.headsign = headsign;
  }
  return mapped;
}

function mapItinerary(raw: unknown): RouteItinerary | null {
  if (!raw || typeof raw !== "object") {
    return null;
  }
  const it = raw as Record<string, unknown>;
  const legs = Array.isArray(it.legs)
    ? it.legs.map(mapLeg).filter((leg): leg is RouteLeg => leg != null)
    : [];
  if (legs.length === 0) {
    return null;
  }
  const walkSum = legs.filter((leg) => leg.mode === "walk").reduce((sum, leg) => sum + leg.duration_sec, 0);
  const transitCount = legs.filter((leg) => leg.mode === "transit").length;
  const duration = asFinite(it.duration) ?? legs.reduce((sum, leg) => sum + leg.duration_sec, 0);
  const distance = legs.reduce((sum, leg) => sum + leg.distance_m, 0);
  const transfers = asFinite(it.transfers) ?? asFinite(it.nTransfers) ?? Math.max(0, transitCount - 1);
  return {
    duration_sec: Math.max(0, Math.round(duration)),
    distance_m: Math.max(0, Math.round(distance)),
    transfers: Math.max(0, Math.round(transfers)),
    walk_duration_sec: Math.max(0, Math.round(asFinite(it.walkTime) ?? walkSum)),
    legs,
  };
}

export function sortItineraries(items: RouteItinerary[]): RouteItinerary[] {
  return items
    .map((item, index) => ({ item, index }))
    .sort((a, b) => {
      if (a.item.duration_sec !== b.item.duration_sec) {
        return a.item.duration_sec - b.item.duration_sec;
      }
      if (a.item.transfers !== b.item.transfers) {
        return a.item.transfers - b.item.transfers;
      }
      if (a.item.walk_duration_sec !== b.item.walk_duration_sec) {
        return a.item.walk_duration_sec - b.item.walk_duration_sec;
      }
      return a.index - b.index;
    })
    .map((row) => row.item);
}

export function itinerariesFromOtpPlan(payload: unknown): RouteItinerary[] {
  const root = payload as {
    plan?: { itineraries?: unknown[] };
    data?: { plan?: { itineraries?: unknown[] } };
  };
  const list = root?.plan?.itineraries ?? root?.data?.plan?.itineraries ?? [];
  if (!Array.isArray(list)) {
    return [];
  }
  const mapped = list
    .map((item) => mapItinerary(item))
    .filter((item): item is RouteItinerary => item != null)
    .filter((item) => item.legs.some((leg) => leg.mode === "transit"));
  return sortItineraries(mapped);
}

export function fitItinerariesToBudget(items: RouteItinerary[], maxBytes = ROUTE_MAX_JSON_BYTES): RouteItinerary[] {
  let current = items;
  let maxPoints = 80;
  while (maxPoints >= 8) {
    const sized = current.map((it) => ({
      ...it,
      legs: it.legs.map((leg) => ({
        ...leg,
        geometry: {
          type: "LineString" as const,
          coordinates: simplifyLine(leg.geometry.coordinates, maxPoints),
        },
      })),
    }));
    if (Buffer.byteLength(JSON.stringify(sized), "utf8") <= maxBytes) {
      return sized;
    }
    maxPoints = Math.floor(maxPoints / 2);
    current = sized;
  }
  return current;
}
