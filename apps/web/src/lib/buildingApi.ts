import { API_URL } from "./constants";
import {
  BuildingNotFoundError,
  OutsideCityError,
  type BuildingAtResponse,
  type BuildingDetail,
} from "../types/building";

async function parseJson(response: Response): Promise<unknown> {
  return response.json().catch(() => null);
}

export async function fetchBuildingAt(
  lon: number,
  lat: number,
  signal?: AbortSignal,
): Promise<BuildingAtResponse & Partial<BuildingDetail>> {
  const url = new URL("/v1/buildings/at", API_URL);
  url.searchParams.set("lon", String(lon));
  url.searchParams.set("lat", String(lat));

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
    throw new BuildingNotFoundError();
  }
  if (response.status === 422) {
    throw new OutsideCityError();
  }
  if (!response.ok) {
    const error = new Error(`buildings_at_http_${response.status}`);
    error.name = "NetworkError";
    throw error;
  }

  return (await parseJson(response)) as BuildingAtResponse & Partial<BuildingDetail>;
}

export async function fetchBuilding(id: string, signal?: AbortSignal): Promise<BuildingDetail> {
  const url = new URL(`/v1/buildings/${encodeURIComponent(id)}`, API_URL);

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
    throw new BuildingNotFoundError();
  }
  if (!response.ok) {
    const error = new Error(`buildings_id_http_${response.status}`);
    error.name = "NetworkError";
    throw error;
  }

  return (await parseJson(response)) as BuildingDetail;
}
