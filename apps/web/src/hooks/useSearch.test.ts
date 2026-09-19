import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { searchPlaces, SearchUnavailableError } from "../lib/api";
import { orgHit } from "../test/fixtures";
import { useSearch } from "./useSearch";

vi.mock("../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../lib/api")>();
  return {
    ...actual,
    searchPlaces: vi.fn(),
  };
});

const mockedSearch = vi.mocked(searchPlaces);

describe("useSearch", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    mockedSearch.mockReset();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("does not call the API when q is shorter than 2 characters", async () => {
    const { result } = renderHook(() => useSearch({ query: "a", lat: 45.255, lon: 19.845 }));
    await act(async () => {
      vi.advanceTimersByTime(300);
    });
    expect(mockedSearch).not.toHaveBeenCalled();
    expect(result.current.hits).toEqual([]);
  });

  it("debounces 150 ms before fetching", async () => {
    mockedSearch.mockResolvedValue({ query: "ap", hits: [orgHit()], processingTimeMs: 3 });
    renderHook(() => useSearch({ query: "ap", lat: 45.255, lon: 19.845 }));

    await act(async () => {
      vi.advanceTimersByTime(149);
    });
    expect(mockedSearch).not.toHaveBeenCalled();

    await act(async () => {
      vi.advanceTimersByTime(1);
    });
    expect(mockedSearch).toHaveBeenCalledTimes(1);
    expect(mockedSearch.mock.calls[0]?.[0]).toMatchObject({ q: "ap", lat: 45.255, lon: 19.845 });
  });

  it("ignores a stale response after a faster follow-up query", async () => {
    let resolveApo: ((value: Awaited<ReturnType<typeof searchPlaces>>) => void) | undefined;
    const apoResponse = new Promise<Awaited<ReturnType<typeof searchPlaces>>>((resolve) => {
      resolveApo = resolve;
    });
    mockedSearch.mockReturnValueOnce(apoResponse).mockResolvedValueOnce({
      query: "apotek",
      hits: [orgHit({ id: "org:osm:fresh" })],
      processingTimeMs: 2,
    });

    const { result, rerender } = renderHook(
      ({ query }: { query: string }) => useSearch({ query, lat: 45.255, lon: 19.845 }),
      { initialProps: { query: "apo" } },
    );

    await act(async () => {
      vi.advanceTimersByTime(150);
    });
    rerender({ query: "apotek" });
    await act(async () => {
      vi.advanceTimersByTime(150);
    });

    await act(async () => {
      resolveApo?.({
        query: "apo",
        hits: [orgHit({ id: "org:osm:stale", label: "stale" })],
        processingTimeMs: 20,
      });
    });

    expect(result.current.hits).toEqual([orgHit({ id: "org:osm:fresh" })]);
    expect(result.current.hitsQuery).toBe("apotek");
  });

  it("shows the unavailable message on 503", async () => {
    mockedSearch.mockRejectedValue(new SearchUnavailableError());
    const { result } = renderHook(() => useSearch({ query: "apotek", lat: 45.255, lon: 19.845 }));
    await act(async () => {
      vi.advanceTimersByTime(150);
    });

    expect(result.current.error).toBe("unavailable");
    expect(result.current.errorMessage).toMatch(/недоступен/i);
    expect(result.current.hits).toEqual([]);
  });

  it("shows a network error when fetch fails", async () => {
    mockedSearch.mockRejectedValue(Object.assign(new Error("network"), { name: "NetworkError" }));
    const { result } = renderHook(() => useSearch({ query: "apotek", lat: 45.255, lon: 19.845 }));
    await act(async () => {
      vi.advanceTimersByTime(150);
    });

    expect(result.current.error).toBe("network");
    expect(result.current.errorMessage).toBe("Nema veze sa serverom");
  });

  it("treats an empty hit list as «ничего не найдено»", async () => {
    mockedSearch.mockResolvedValue({ query: "zzzz", hits: [], processingTimeMs: 1 });
    const { result } = renderHook(() => useSearch({ query: "zzzz", lat: 45.255, lon: 19.845 }));
    await act(async () => {
      vi.advanceTimersByTime(150);
    });

    expect(result.current.error).toBe("empty");
    expect(result.current.errorMessage).toBe("Ništa nije pronađeno");
  });
});
