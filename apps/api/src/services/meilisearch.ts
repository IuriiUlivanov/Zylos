import { config } from "../config.js";

export const INDEX_UID = "zylos";
const SEARCH_TIMEOUT_MS = 500;
const HEALTH_TIMEOUT_MS = 2000;

export class SearchUnavailableError extends Error {
  constructor() {
    super("search_unavailable");
    this.name = "SearchUnavailableError";
  }
}

export async function meiliHealth(): Promise<"ok" | "down"> {
  try {
    const response = await fetch(`${config.meiliUrl}/health`, {
      signal: AbortSignal.timeout(HEALTH_TIMEOUT_MS),
    });
    if (!response.ok) {
      return "down";
    }
    const body = (await response.json()) as { status?: string };
    return body.status === "available" ? "ok" : "down";
  } catch {
    return "down";
  }
}

export interface MeiliSearchParams {
  q: string;
  limit: number;
  kind?: "address" | "organization";
  lat?: number;
  lon?: number;
}

export interface MeiliSearchResult {
  hits: Record<string, unknown>[];
  processingTimeMs: number;
}

export async function searchIndex(params: MeiliSearchParams): Promise<MeiliSearchResult> {
  const body: Record<string, unknown> = {
    q: params.q,
    limit: params.limit,
    attributesToRetrieve: [
      "id",
      "kind",
      "label",
      "name",
      "category_slug",
      "building_id",
      "street",
      "housenumber",
      "source",
      "_geo",
    ],
  };

  if (params.kind) {
    body.filter = `kind = ${params.kind}`;
  }

  if (Number.isFinite(params.lat) && Number.isFinite(params.lon)) {
    body.sort = [`_geoPoint(${params.lat}, ${params.lon}):asc`];
  }

  let response: Response;
  try {
    response = await fetch(`${config.meiliUrl}/indexes/${INDEX_UID}/search`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${config.meiliKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(SEARCH_TIMEOUT_MS),
    });
  } catch {
    throw new SearchUnavailableError();
  }

  if (!response.ok) {
    throw new SearchUnavailableError();
  }

  const data = (await response.json()) as {
    hits?: Record<string, unknown>[];
    processingTimeMs?: number;
  };

  return {
    hits: data.hits ?? [],
    processingTimeMs: data.processingTimeMs ?? 0,
  };
}
