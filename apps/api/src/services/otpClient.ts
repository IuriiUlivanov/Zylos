import { config } from "../config.js";
import type { RouteItinerary, RouteLonLat } from "../types/route.js";
import { fitItinerariesToBudget, itinerariesFromOtpPlan } from "./otpPlan.js";

export const ROUTE_UPSTREAM_TIMEOUT_MS = 6_000;
export const ROUTE_NUM_ITINERARIES = 3;

export { itinerariesFromOtpPlan, fitItinerariesToBudget } from "./otpPlan.js";

export class OtpTimeoutError extends Error {
  constructor() {
    super("routing_timeout");
    this.name = "OtpTimeoutError";
  }
}

export class OtpUnavailableError extends Error {
  constructor(message = "otp_unavailable") {
    super(message);
    this.name = "OtpUnavailableError";
  }
}

function otpDateTime(now = new Date()): { date: string; time: string } {
  const month = String(now.getMonth() + 1).padStart(2, "0");
  const day = String(now.getDate()).padStart(2, "0");
  const year = now.getFullYear();
  const hours = now.getHours();
  const minutes = String(now.getMinutes()).padStart(2, "0");
  const ampm = hours >= 12 ? "pm" : "am";
  const hour12 = hours % 12 === 0 ? 12 : hours % 12;
  return {
    date: `${month}-${day}-${year}`,
    time: `${hour12}:${minutes}${ampm}`,
  };
}

async function fetchWithTimeout(url: string, init: RequestInit, timeoutMs: number): Promise<Response> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, { ...init, signal: controller.signal });
  } catch (err) {
    const name = (err as { name?: string }).name;
    if (name === "AbortError" || name === "TimeoutError") {
      throw new OtpTimeoutError();
    }
    throw new OtpUnavailableError();
  } finally {
    clearTimeout(timer);
  }
}

async function planViaRest(from: RouteLonLat, to: RouteLonLat): Promise<unknown> {
  const { date, time } = otpDateTime();
  const params = new URLSearchParams({
    fromPlace: `${from.lat},${from.lon}`,
    toPlace: `${to.lat},${to.lon}`,
    mode: "TRANSIT,WALK",
    arriveBy: "false",
    numItineraries: String(ROUTE_NUM_ITINERARIES),
    maxWalkDistance: "1500",
    date,
    time,
  });
  const url = `${config.otpUrl}/otp/routers/default/plan?${params.toString()}`;
  const response = await fetchWithTimeout(url, { headers: { Accept: "application/json" } }, ROUTE_UPSTREAM_TIMEOUT_MS);
  if (response.status === 404 || response.status === 405 || response.status === 501) {
    throw new OtpUnavailableError();
  }
  if (!response.ok && response.status >= 500) {
    throw new OtpUnavailableError();
  }
  return response.json();
}

const PLAN_QUERY = `query Plan($fromLat: Float!, $fromLon: Float!, $toLat: Float!, $toLon: Float!) {
  plan(
    from: { lat: $fromLat, lon: $fromLon }
    to: { lat: $toLat, lon: $toLon }
    numItineraries: 3
    transportModes: [{ mode: TRANSIT }, { mode: WALK }]
  ) {
    itineraries {
      duration
      walkTime
      nTransfers
      legs {
        mode
        duration
        distance
        headsign
        route { shortName color }
        from { name lat lon }
        to { name lat lon }
        legGeometry { points }
      }
    }
  }
}`;

async function planViaGraphQL(from: RouteLonLat, to: RouteLonLat): Promise<unknown> {
  const url = `${config.otpUrl}/otp/routers/default/index/graphql`;
  const response = await fetchWithTimeout(
    url,
    {
      method: "POST",
      headers: { Accept: "application/json", "Content-Type": "application/json" },
      body: JSON.stringify({
        query: PLAN_QUERY,
        variables: { fromLat: from.lat, fromLon: from.lon, toLat: to.lat, toLon: to.lon },
      }),
    },
    ROUTE_UPSTREAM_TIMEOUT_MS,
  );
  if (!response.ok && response.status >= 500) {
    throw new OtpUnavailableError();
  }
  return response.json();
}

export async function planTransit(from: RouteLonLat, to: RouteLonLat): Promise<RouteItinerary[]> {
  let payload: unknown;
  try {
    payload = await planViaRest(from, to);
  } catch (err) {
    if (err instanceof OtpTimeoutError) {
      throw err;
    }
    payload = await planViaGraphQL(from, to);
  }
  return fitItinerariesToBudget(itinerariesFromOtpPlan(payload));
}
