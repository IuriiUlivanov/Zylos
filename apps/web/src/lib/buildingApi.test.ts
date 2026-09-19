import { afterEach, describe, expect, it, vi } from "vitest";
import { fetchBuilding, fetchBuildingAt } from "./buildingApi";
import { BuildingNotFoundError, OutsideCityError } from "../types/building";

describe("building API client", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("calls GET /v1/buildings/at with lon and lat", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ id: "bldg-1", label: "Zgrada" }),
    });
    vi.stubGlobal("fetch", fetchMock);

    await expect(fetchBuildingAt(19.845, 45.255)).resolves.toEqual({ id: "bldg-1", label: "Zgrada" });
    const [url] = fetchMock.mock.calls[0] as [URL];
    expect(url.pathname).toBe("/v1/buildings/at");
    expect(url.searchParams.get("lon")).toBe("19.845");
    expect(url.searchParams.get("lat")).toBe("45.255");
  });

  it("maps 404 to BuildingNotFoundError and 422 to OutsideCityError", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: false,
        status: 404,
        json: async () => ({ error: "building_not_found" }),
      }),
    );
    await expect(fetchBuildingAt(19.86, 45.26)).rejects.toBeInstanceOf(BuildingNotFoundError);

    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: false,
        status: 422,
        json: async () => ({ error: "outside_city" }),
      }),
    );
    await expect(fetchBuildingAt(20.46, 44.817)).rejects.toBeInstanceOf(OutsideCityError);
  });

  it("loads GET /v1/buildings/:id", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({
        id: "bldg-1",
        name: null,
        centroid: { lon: 19.84, lat: 45.25 },
        geometry: { type: "Polygon", coordinates: [] },
        addresses: [],
        organizations: [],
      }),
    });
    vi.stubGlobal("fetch", fetchMock);
    const body = await fetchBuilding("bldg-1");
    expect(body.organizations).toEqual([]);
    const [url] = fetchMock.mock.calls[0] as [URL];
    expect(url.pathname).toBe("/v1/buildings/bldg-1");
  });
});
