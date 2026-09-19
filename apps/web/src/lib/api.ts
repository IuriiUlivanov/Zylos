import {
  API_URL,
  SEARCH_LIMIT,
} from "./constants";
import type { SearchResponse } from "../types/search";

export class SearchUnavailableError extends Error {
  constructor() {
    super("search_unavailable");
    this.name = "SearchUnavailableError";
  }
}

export interface SearchParams {
  q: string;
  limit?: number;
  lat?: number;
  lon?: number;
  signal?: AbortSignal;
}

export async function searchPlaces(params: SearchParams): Promise<SearchResponse> {
  const url = new URL("/v1/search", API_URL);
  url.searchParams.set("q", params.q);
  url.searchParams.set("limit", String(params.limit ?? SEARCH_LIMIT));
  if (Number.isFinite(params.lat) && Number.isFinite(params.lon)) {
    url.searchParams.set("lat", String(params.lat));
    url.searchParams.set("lon", String(params.lon));
  }

  let response: Response;
  try {
    response = await fetch(url, { signal: params.signal });
  } catch (err) {
    if (err instanceof DOMException && err.name === "AbortError") {
      throw err;
    }
    const error = new Error("network");
    error.name = "NetworkError";
    throw error;
  }

  if (response.status === 503) {
    throw new SearchUnavailableError();
  }
  if (!response.ok) {
    const error = new Error(`search_http_${response.status}`);
    error.name = "NetworkError";
    throw error;
  }

  return (await response.json()) as SearchResponse;
}
