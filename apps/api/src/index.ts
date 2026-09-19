import Fastify from "fastify";
import cors from "@fastify/cors";
import { config } from "./config.js";
import { buildingRoutes } from "./routes/buildings.js";
import { healthRoutes } from "./routes/health.js";
import { orgRoutes } from "./routes/orgs.js";
import { searchRoutes } from "./routes/search.js";
import { SearchUnavailableError } from "./services/meilisearch.js";

async function main(): Promise<void> {
  const app = Fastify({ logger: true });

  await app.register(cors, {
    origin: config.corsOrigins,
  });

  app.setErrorHandler((err: unknown, request, reply) => {
    const error = err as { name?: string; message?: string; statusCode?: number; code?: string };
    if (err instanceof SearchUnavailableError || error.name === "TimeoutError" || error.name === "AbortError") {
      return reply.code(503).send({ error: "search_unavailable" });
    }

    if (error.message === "database_unavailable") {
      return reply.code(503).send({ error: "database_unavailable" });
    }

    const status = error.statusCode;
    if (typeof status === "number" && status >= 400 && status < 500) {
      return reply.code(status).send({
        statusCode: status,
        error: error.code ?? "Bad Request",
        message: error.message,
      });
    }

    request.log.error(err);
    return reply.code(500).send({ error: "internal" });
  });

  await app.register(healthRoutes, { prefix: "/v1" });
  await app.register(searchRoutes, { prefix: "/v1" });
  await app.register(buildingRoutes, { prefix: "/v1" });
  await app.register(orgRoutes, { prefix: "/v1" });

  await app.listen({ port: config.port, host: "0.0.0.0" });
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
