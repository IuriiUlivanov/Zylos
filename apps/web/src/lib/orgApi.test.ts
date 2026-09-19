import { afterEach, describe, expect, it, vi } from "vitest";
import { fetchOrg, fetchOrgPins } from "./orgApi";
import { OrgNotFoundError } from "../types/org";

describe("org API client", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("calls GET /v1/orgs/:id with the public org id", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({
        id: "org:osm:n1",
        name: "A1",
        source: "osm",
        phones: [],
        website: null,
        hours: null,
        floor: null,
        tags: [],
        address: null,
        building_id: "bldg-1",
        location: { lon: 19.84, lat: 45.24 },
      }),
    });
    vi.stubGlobal("fetch", fetchMock);
    await expect(fetchOrg("org:osm:n1")).resolves.toMatchObject({ id: "org:osm:n1", name: "A1" });
    const [url] = fetchMock.mock.calls[0] as [URL];
    expect(url.pathname).toBe("/v1/orgs/org%3Aosm%3An1");
  });

  it("maps 404 to OrgNotFoundError", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: false,
        status: 404,
        json: async () => ({ error: "org_not_found" }),
      }),
    );
    await expect(fetchOrg("org:osm:missing")).rejects.toBeInstanceOf(OrgNotFoundError);
  });

  it("requests bbox pins with a 200 cap", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => [{ id: "org:osm:n1", name: "A1", category_slug: "shop", lon: 19.84, lat: 45.24 }],
    });
    vi.stubGlobal("fetch", fetchMock);
    const pins = await fetchOrgPins({ minLon: 19.8, minLat: 45.2, maxLon: 19.9, maxLat: 45.3 });
    expect(pins).toHaveLength(1);
    const [url] = fetchMock.mock.calls[0] as [URL];
    expect(url.pathname).toBe("/v1/orgs");
    expect(url.searchParams.get("bbox")).toBe("19.8,45.2,19.9,45.3");
    expect(url.searchParams.get("limit")).toBe("200");
  });
});
