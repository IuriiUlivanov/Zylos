import type { FastifyInstance } from "fastify";
import { meiliHealth } from "../services/meilisearch.js";
import { postgisHealth } from "../services/postgis.js";

export async function healthRoutes(app: FastifyInstance): Promise<void> {
  app.get("/health", async (_request, reply) => {
    const [meilisearch, postgis] = await Promise.all([meiliHealth(), postgisHealth()]);
    if (meilisearch === "ok" && postgis === "ok") {
      return reply.code(200).send({ status: "ok", meilisearch, postgis });
    }
    return reply.code(503).send({ status: "error", meilisearch, postgis });
  });
}
