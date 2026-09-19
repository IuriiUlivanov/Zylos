import { afterEach, describe, expect, it, vi } from "vitest";
import { searchPlaces, SearchUnavailableError } from "./api";

describe("searchPlaces", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("calls GET /v1/search with q, limit 15 and geo-bias", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ query: "apotek", hits: [], processingTimeMs: 4 }),
    });
    vi.stubGlobal("fetch", fetchMock);

    await searchPlaces({ q: "apotek", lat: 45.255, lon: 19.845 });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url] = fetchMock.mock.calls[0] as [URL];
    expect(url.pathname).toBe("/v1/search");
    expect(url.searchParams.get("q")).toBe("apotek");
    expect(url.searchParams.get("limit")).toBe("15");
    expect(url.searchParams.get("lat")).toBe("45.255");
    expect(url.searchParams.get("lon")).toBe("19.845");
  });

  it("throws SearchUnavailableError on HTTP 503", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: false,
        status: 503,
        json: async () => ({ error: "search_unavailable" }),
      }),
    );

    await expect(searchPlaces({ q: "apotek" })).rejects.toBeInstanceOf(SearchUnavailableError);
  });

  it("maps a network failure to NetworkError", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("Failed to fetch")));

    await expect(searchPlaces({ q: "apotek" })).rejects.toMatchObject({ name: "NetworkError" });
  });

  it("rethrows AbortError without wrapping", async () => {
    const abort = new DOMException("Aborted", "AbortError");
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(abort));

    await expect(searchPlaces({ q: "apotek", signal: new AbortController().signal })).rejects.toBe(abort);
  });
});
