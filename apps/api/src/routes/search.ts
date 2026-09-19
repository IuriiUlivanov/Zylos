import type { FastifyInstance, FastifyRequest } from "fastify";
import { searchIndex } from "../services/meilisearch.js";
import type { SearchHit, SearchKind, SearchResponse } from "../types/search.js";

interface SearchQuery {
  q?: string;
  limit?: number;
  lat?: number;
  lon?: number;
  kind?: SearchKind;
}

const searchQuerySchema = {
  type: "object",
  additionalProperties: false,
  properties: {
    q: { type: "string" },
    limit: { type: "integer", minimum: 1, maximum: 15 },
    lat: { type: "number" },
    lon: { type: "number" },
    kind: { type: "string", enum: ["address", "organization"] },
  },
} as const;

function mapHit(doc: Record<string, unknown>): SearchHit {
  const geo = doc._geo as { lat?: number; lng?: number } | undefined;
  const hit: SearchHit = {
    id: String(doc.id ?? ""),
    kind: doc.kind === "organization" ? "organization" : "address",
    label: String(doc.label ?? ""),
    lat: Number(geo?.lat),
    lon: Number(geo?.lng),
    building_id: doc.building_id == null ? null : String(doc.building_id),
  };

  if (hit.kind === "organization") {
    if (doc.name != null) {
      hit.name = String(doc.name);
    }
    if (doc.category_slug != null) {
      hit.category_slug = String(doc.category_slug);
    }
  }

  return hit;
}

export async function searchRoutes(app: FastifyInstance): Promise<void> {
  app.get(
    "/search",
    { schema: { querystring: searchQuerySchema } },
    async (request: FastifyRequest<{ Querystring: SearchQuery }>, reply) => {
      const started = performance.now();
      const q = request.query.q ?? "";

      if (q.length < 2) {
        const body: SearchResponse = {
          query: q,
          hits: [],
          processingTimeMs: Math.max(0, Math.round(performance.now() - started)),
        };
        return reply.code(200).send(body);
      }

      const limit = request.query.limit ?? 10;
      const lat = request.query.lat;
      const lon = request.query.lon;
      const hasGeo = Number.isFinite(lat) && Number.isFinite(lon);

      const result = await searchIndex({
        q,
        limit,
        kind: request.query.kind,
        lat: hasGeo ? lat : undefined,
        lon: hasGeo ? lon : undefined,
      });

      const body: SearchResponse = {
        query: q,
        hits: result.hits.map(mapHit),
        processingTimeMs: Math.max(
          result.processingTimeMs,
          Math.round(performance.now() - started),
        ),
      };
      return reply.code(200).send(body);
    },
  );
}
