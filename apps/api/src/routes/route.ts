import type { FastifyInstance, FastifyRequest } from "fastify";
import { isInsideCity } from "../services/postgis.js";
import {
  OtpTimeoutError,
  OtpUnavailableError,
  planTransit,
  ROUTE_UPSTREAM_TIMEOUT_MS,
} from "../services/otpClient.js";
import type { RouteLonLat, RouteRequest, RouteResponse } from "../types/route.js";

function finiteCoord(value: unknown): number | null {
  if (typeof value === "number" && Number.isFinite(value)) {
    return value;
  }
  return null;
}

function parsePoint(value: unknown): RouteLonLat | null {
  if (!value || typeof value !== "object") {
    return null;
  }
  const point = value as { lon?: unknown; lat?: unknown };
  const lon = finiteCoord(point.lon);
  const lat = finiteCoord(point.lat);
  if (lon == null || lat == null || lon < -180 || lon > 180 || lat < -90 || lat > 90) {
    return null;
  }
  return { lon, lat };
}

function parseRequest(body: unknown): RouteRequest | null {
  if (!body || typeof body !== "object") {
    return null;
  }
  const raw = body as { from?: unknown; to?: unknown; mode?: unknown };
  const from = parsePoint(raw.from);
  const to = parsePoint(raw.to);
  if (!from || !to || raw.mode !== "transit") {
    return null;
  }
  return { from, to, mode: "transit" };
}

export async function routeRoutes(app: FastifyInstance): Promise<void> {
  app.post("/route", async (request: FastifyRequest, reply) => {
    const parsed = parseRequest(request.body);
    if (!parsed) {
      return reply.code(400).send({ error: "invalid_request" });
    }

    const [fromInside, toInside] = await Promise.all([
      isInsideCity(parsed.from.lon, parsed.from.lat),
      isInsideCity(parsed.to.lon, parsed.to.lat),
    ]);
    if (!fromInside || !toInside) {
      return reply.code(422).send({ error: "outside_city" });
    }

    try {
      const itineraries = await planTransit(parsed.from, parsed.to);
      if (itineraries.length === 0) {
        return reply.code(404).send({ error: "no_route" });
      }
      const best = itineraries[0]!;
      const body: RouteResponse = {
        mode: "transit",
        duration_sec: best.duration_sec,
        distance_m: best.distance_m,
        transfers: best.transfers,
        legs: best.legs,
        itineraries,
      };
      return reply.code(200).send(body);
    } catch (err) {
      if (err instanceof OtpTimeoutError) {
        return reply.code(504).send({ error: "routing_timeout" });
      }
      if (err instanceof OtpUnavailableError) {
        return reply.code(504).send({ error: "routing_timeout" });
      }
      request.log.error({ err, timeoutMs: ROUTE_UPSTREAM_TIMEOUT_MS }, "otp plan failed");
      return reply.code(504).send({ error: "routing_timeout" });
    }
  });
}
