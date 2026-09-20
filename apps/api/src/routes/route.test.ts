import Fastify from "fastify";
import { afterEach, describe, expect, it, vi } from "vitest";

vi.mock("../services/postgis.js", () => ({
  isInsideCity: vi.fn(),
}));

vi.mock("../services/otpClient.js", () => {
  class OtpTimeoutError extends Error {
    constructor() {
      super("routing_timeout");
      this.name = "OtpTimeoutError";
    }
  }
  class OtpUnavailableError extends Error {
    constructor() {
      super("otp_unavailable");
      this.name = "OtpUnavailableError";
    }
  }
  return {
    planTransit: vi.fn(),
    OtpTimeoutError,
    OtpUnavailableError,
    ROUTE_UPSTREAM_TIMEOUT_MS: 6_000,
  };
});

import { isInsideCity } from "../services/postgis.js";
import { OtpTimeoutError, planTransit } from "../services/otpClient.js";
import {
  decodePolyline,
  itinerariesFromOtpPlan,
  normalizeRouteColor,
  simplifyLine,
  sortItineraries,
} from "../services/otpPlan.js";
import { routeRoutes } from "./route.js";

const isInsideCityMock = vi.mocked(isInsideCity);
const planTransitMock = vi.mocked(planTransit);

const OTP_PLAN = {
  plan: {
    itineraries: [
      {
        duration: 2000,
        walkTime: 400,
        transfers: 1,
        legs: [
          {
            mode: "WALK",
            duration: 400,
            distance: 280,
            from: { name: "Origin", lat: 45.255, lon: 19.845 },
            to: { name: "Trg Slobode", lat: 45.254, lon: 19.844 },
            legGeometry: { points: "_p~iF~ps|U" },
          },
          {
            mode: "BUS",
            duration: 1600,
            distance: 3200,
            routeShortName: "7A",
            routeColor: "#e30613",
            headsign: "Liman",
            from: { name: "Trg Slobode", lat: 45.254, lon: 19.844 },
            to: { name: "Liman III", lat: 45.238, lon: 19.84 },
            legGeometry: { points: "_p~iF~ps|U" },
          },
        ],
      },
      {
        duration: 1680,
        walkTime: 360,
        transfers: 1,
        legs: [
          {
            mode: "WALK",
            duration: 360,
            distance: 280,
            from: { name: "Origin", lat: 45.255, lon: 19.845 },
            to: { name: "Trg", lat: 45.254, lon: 19.844 },
            legGeometry: { points: "_p~iF~ps|U" },
          },
          {
            mode: "BUS",
            duration: 900,
            distance: 3200,
            routeShortName: "7A",
            routeColor: "E30613",
            headsign: "Liman",
            from: { name: "Trg Slobode", lat: 45.254, lon: 19.844 },
            to: { name: "Liman III", lat: 45.238, lon: 19.84 },
            legGeometry: { points: "_p~iF~ps|U" },
          },
          {
            mode: "WALK",
            duration: 420,
            distance: 300,
            from: { name: "Liman III", lat: 45.238, lon: 19.84 },
            to: { name: "Dest", lat: 45.238, lon: 19.84 },
            legGeometry: { points: "_p~iF~ps|U" },
          },
        ],
      },
    ],
  },
};

async function app() {
  const instance = Fastify();
  await instance.register(routeRoutes);
  await instance.ready();
  return instance;
}

describe("OTP plan parser", () => {
  it("decodes polyline to lon/lat pairs", () => {
    const coords = decodePolyline("_p~iF~ps|U");
    expect(coords.length).toBeGreaterThanOrEqual(1);
    expect(coords[0]?.length).toBe(2);
  });

  it("normalizes route_color to 6 hex without hash", () => {
    expect(normalizeRouteColor("#e30613")).toBe("E30613");
    expect(normalizeRouteColor("00B341")).toBe("00B341");
    expect(normalizeRouteColor("red")).toBeUndefined();
  });

  it("keeps first and last points when simplifying", () => {
    const line: [number, number][] = [
      [19.84, 45.25],
      [19.841, 45.251],
      [19.842, 45.252],
      [19.843, 45.253],
      [19.844, 45.254],
    ];
    const simple = simplifyLine(line, 3);
    expect(simple[0]).toEqual(line[0]);
    expect(simple[simple.length - 1]).toEqual(line[line.length - 1]);
    expect(simple.length).toBe(3);
  });

  it("maps OTP legs and sorts duration → transfers → walk", () => {
    const itineraries = itinerariesFromOtpPlan(OTP_PLAN);
    expect(itineraries.length).toBe(2);
    expect(itineraries[0]?.duration_sec).toBe(1680);
    expect(itineraries[0]?.legs.some((leg) => leg.mode === "transit")).toBe(true);
    expect(itineraries[0]?.legs.find((leg) => leg.mode === "transit")?.route_short_name).toBe("7A");
    expect(itineraries[0]?.legs.find((leg) => leg.mode === "transit")?.route_color).toBe("E30613");
    const sorted = sortItineraries(itineraries);
    expect(sorted[0]?.duration_sec).toBeLessThanOrEqual(sorted[1]?.duration_sec ?? Infinity);
  });

  it("drops walk-only itineraries", () => {
    const itineraries = itinerariesFromOtpPlan({
      plan: {
        itineraries: [
          {
            duration: 600,
            walkTime: 600,
            transfers: 0,
            legs: [
              {
                mode: "WALK",
                duration: 600,
                distance: 800,
                from: { name: "A", lat: 45.25, lon: 19.84 },
                to: { name: "B", lat: 45.26, lon: 19.85 },
              },
            ],
          },
        ],
      },
    });
    expect(itineraries).toEqual([]);
  });
});

describe("POST /route", () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it("returns 400 invalid_request when mode is not transit", async () => {
    const instance = await app();
    const response = await instance.inject({
      method: "POST",
      url: "/route",
      payload: { from: { lon: 19.845, lat: 45.255 }, to: { lon: 19.84, lat: 45.238 }, mode: "walk" },
    });
    expect(response.statusCode).toBe(400);
    expect(response.json()).toEqual({ error: "invalid_request" });
    await instance.close();
  });

  it("returns 422 outside_city without calling OTP", async () => {
    isInsideCityMock.mockResolvedValueOnce(false).mockResolvedValueOnce(true);
    const instance = await app();
    const response = await instance.inject({
      method: "POST",
      url: "/route",
      payload: { from: { lon: 20.5, lat: 44.8 }, to: { lon: 19.845, lat: 45.255 }, mode: "transit" },
    });
    expect(response.statusCode).toBe(422);
    expect(response.json()).toEqual({ error: "outside_city" });
    expect(planTransitMock).not.toHaveBeenCalled();
    await instance.close();
  });

  it("returns 200 with legs from OTP", async () => {
    isInsideCityMock.mockResolvedValue(true);
    planTransitMock.mockResolvedValue(itinerariesFromOtpPlan(OTP_PLAN));
    const instance = await app();
    const response = await instance.inject({
      method: "POST",
      url: "/route",
      payload: { from: { lon: 19.845, lat: 45.255 }, to: { lon: 19.84, lat: 45.238 }, mode: "transit" },
    });
    expect(response.statusCode).toBe(200);
    const body = response.json();
    expect(body.mode).toBe("transit");
    expect(body.duration_sec).toBeGreaterThan(0);
    expect(body.legs.length).toBeGreaterThanOrEqual(1);
    expect(body.legs.some((leg: { mode: string }) => leg.mode === "transit")).toBe(true);
    expect(body.itineraries.length).toBeGreaterThanOrEqual(1);
    await instance.close();
  });

  it("returns 404 no_route when OTP has no transit path", async () => {
    isInsideCityMock.mockResolvedValue(true);
    planTransitMock.mockResolvedValue([]);
    const instance = await app();
    const response = await instance.inject({
      method: "POST",
      url: "/route",
      payload: { from: { lon: 19.845, lat: 45.255 }, to: { lon: 19.84, lat: 45.238 }, mode: "transit" },
    });
    expect(response.statusCode).toBe(404);
    expect(response.json()).toEqual({ error: "no_route" });
    await instance.close();
  });

  it("returns 504 routing_timeout when OTP times out", async () => {
    isInsideCityMock.mockResolvedValue(true);
    planTransitMock.mockRejectedValue(new OtpTimeoutError());
    const instance = await app();
    const response = await instance.inject({
      method: "POST",
      url: "/route",
      payload: { from: { lon: 19.845, lat: 45.255 }, to: { lon: 19.84, lat: 45.238 }, mode: "transit" },
    });
    expect(response.statusCode).toBe(504);
    expect(response.json()).toEqual({ error: "routing_timeout" });
    await instance.close();
  });
});
