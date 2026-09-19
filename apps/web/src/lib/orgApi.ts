import { API_URL, ORG_PINS_LIMIT } from "./constants";
import { OrgNotFoundError, type OrgDetail, type OrgPin } from "../types/org";

export interface OrgBbox {
  minLon: number;
  minLat: number;
  maxLon: number;
  maxLat: number;
}

async function parseJson(response: Response): Promise<unknown> {
  return response.json().catch(() => null);
}

export async function fetchOrg(id: string, signal?: AbortSignal): Promise<OrgDetail> {
  const url = new URL(`/v1/orgs/${encodeURIComponent(id)}`, API_URL);

  let response: Response;
  try {
    response = await fetch(url, { signal });
  } catch (err) {
    if (err instanceof DOMException && err.name === "AbortError") {
      throw err;
    }
    const error = new Error("network");
    error.name = "NetworkError";
    throw error;
  }

  if (response.status === 404) {
    throw new OrgNotFoundError();
  }
  if (!response.ok) {
    const error = new Error(`orgs_id_http_${response.status}`);
    error.name = "NetworkError";
    throw error;
  }

  return (await parseJson(response)) as OrgDetail;
}

export async function fetchOrgPins(bbox: OrgBbox, signal?: AbortSignal): Promise<OrgPin[]> {
  const url = new URL("/v1/orgs", API_URL);
  url.searchParams.set("bbox", `${bbox.minLon},${bbox.minLat},${bbox.maxLon},${bbox.maxLat}`);
  url.searchParams.set("limit", String(ORG_PINS_LIMIT));

  let response: Response;
  try {
    response = await fetch(url, { signal });
  } catch (err) {
    if (err instanceof DOMException && err.name === "AbortError") {
      throw err;
    }
    const error = new Error("network");
    error.name = "NetworkError";
    throw error;
  }

  if (!response.ok) {
    const error = new Error(`orgs_bbox_http_${response.status}`);
    error.name = "NetworkError";
    throw error;
  }

  const body = (await parseJson(response)) as OrgPin[];
  return Array.isArray(body) ? body.slice(0, ORG_PINS_LIMIT) : [];
}
