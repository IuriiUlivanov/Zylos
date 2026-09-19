import type { FastifyInstance, FastifyRequest } from "fastify";
import { MAX_ORG_PINS, getOrgByPublicId, listOrgsInBbox } from "../services/postgis.js";

interface OrgParams {
  id: string;
}

interface OrgBboxQuery {
  bbox?: string;
  limit?: number;
}

const orgParamsSchema = {
  type: "object",
  required: ["id"],
  properties: {
    id: { type: "string", minLength: 1 },
  },
} as const;

const bboxQuerySchema = {
  type: "object",
  additionalProperties: false,
  required: ["bbox"],
  properties: {
    bbox: { type: "string", minLength: 1 },
    limit: { type: "integer", minimum: 1, maximum: MAX_ORG_PINS },
  },
} as const;

function parseBbox(raw: string): [number, number, number, number] | null {
  const parts = raw.split(",").map((part) => Number(part.trim()));
  if (parts.length !== 4 || parts.some((value) => !Number.isFinite(value))) {
    return null;
  }
  const [minLon, minLat, maxLon, maxLat] = parts as [number, number, number, number];
  if (minLon >= maxLon || minLat >= maxLat) {
    return null;
  }
  if (minLon < -180 || maxLon > 180 || minLat < -90 || maxLat > 90) {
    return null;
  }
  return [minLon, minLat, maxLon, maxLat];
}

export async function orgRoutes(app: FastifyInstance): Promise<void> {
  app.get(
    "/orgs",
    { schema: { querystring: bboxQuerySchema } },
    async (request: FastifyRequest<{ Querystring: OrgBboxQuery }>, reply) => {
      const bbox = parseBbox(request.query.bbox ?? "");
      if (!bbox) {
        return reply.code(400).send({ error: "invalid_bbox" });
      }
      const pins = await listOrgsInBbox(...bbox, request.query.limit ?? MAX_ORG_PINS);
      return reply.code(200).send(pins);
    },
  );

  app.get(
    "/orgs/:id",
    { schema: { params: orgParamsSchema } },
    async (request: FastifyRequest<{ Params: OrgParams }>, reply) => {
      const org = await getOrgByPublicId(request.params.id);
      if (!org) {
        return reply.code(404).send({ error: "org_not_found" });
      }
      return reply.code(200).send(org);
    },
  );
}
