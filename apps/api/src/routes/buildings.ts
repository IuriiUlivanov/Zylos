import type { FastifyInstance, FastifyRequest } from "fastify";
import { isUuid } from "../lib/ids.js";
import { findBuildingAt, getBuildingById, isInsideCity } from "../services/postgis.js";

interface AtQuery {
  lon?: number;
  lat?: number;
}

interface BuildingParams {
  id: string;
}

const atQuerySchema = {
  type: "object",
  additionalProperties: false,
  required: ["lon", "lat"],
  properties: {
    lon: { type: "number" },
    lat: { type: "number" },
  },
} as const;

const idParamsSchema = {
  type: "object",
  required: ["id"],
  properties: {
    id: { type: "string", minLength: 1 },
  },
} as const;

function finiteCoord(value: unknown): number | null {
  if (typeof value === "number" && Number.isFinite(value)) {
    return value;
  }
  return null;
}

export async function buildingRoutes(app: FastifyInstance): Promise<void> {
  app.get(
    "/buildings/at",
    { schema: { querystring: atQuerySchema } },
    async (request: FastifyRequest<{ Querystring: AtQuery }>, reply) => {
      const lon = finiteCoord(request.query.lon);
      const lat = finiteCoord(request.query.lat);
      if (lon == null || lat == null || lon < -180 || lon > 180 || lat < -90 || lat > 90) {
        return reply.code(400).send({ error: "invalid_coordinates" });
      }

      const found = await findBuildingAt(lon, lat);
      if (found) {
        const detail = await getBuildingById(found.id);
        if (!detail) {
          return reply.code(404).send({ error: "building_not_found" });
        }
        return reply.code(200).send({ ...detail, label: found.label });
      }

      const inside = await isInsideCity(lon, lat);
      if (!inside) {
        return reply.code(422).send({ error: "outside_city" });
      }
      return reply.code(404).send({ error: "building_not_found" });
    },
  );

  app.get(
    "/buildings/:id",
    { schema: { params: idParamsSchema } },
    async (request: FastifyRequest<{ Params: BuildingParams }>, reply) => {
      const id = request.params.id;
      if (!isUuid(id)) {
        return reply.code(404).send({ error: "building_not_found" });
      }
      const building = await getBuildingById(id);
      if (!building) {
        return reply.code(404).send({ error: "building_not_found" });
      }
      return reply.code(200).send(building);
    },
  );
}
