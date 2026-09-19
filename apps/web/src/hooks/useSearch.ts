import { useEffect, useRef, useState } from "react";
import { searchPlaces, SearchUnavailableError } from "../lib/api";
import { SEARCH_DEBOUNCE_MS, SEARCH_MIN_LENGTH } from "../lib/constants";
import type { SearchHit, SearchUiError } from "../types/search";

export { SEARCH_DEBOUNCE_MS, SEARCH_MIN_LENGTH };

interface UseSearchOptions {
  query: string;
  lat: number;
  lon: number;
}

export function useSearch({ query, lat, lon }: UseSearchOptions): {
  hits: SearchHit[];
  hitsQuery: string;
  loading: boolean;
  error: SearchUiError;
  errorMessage: string | null;
} {
  const [hits, setHits] = useState<SearchHit[]>([]);
  const [hitsQuery, setHitsQuery] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<SearchUiError>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const requestIdRef = useRef(0);
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    if (query.length < SEARCH_MIN_LENGTH) {
      requestIdRef.current += 1;
      abortRef.current?.abort();
      abortRef.current = null;
      setHits([]);
      setHitsQuery(query);
      setLoading(false);
      setError(null);
      setErrorMessage(null);
      return;
    }

    const timer = window.setTimeout(() => {
      const requestId = requestIdRef.current + 1;
      requestIdRef.current = requestId;
      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;
      setLoading(true);
      setError(null);
      setErrorMessage(null);

      searchPlaces({ q: query, lat, lon, signal: controller.signal })
        .then((response) => {
          if (requestId !== requestIdRef.current) {
            return;
          }
          setHits(response.hits);
          setHitsQuery(response.query);
          setLoading(false);
          if (response.hits.length === 0) {
            setError("empty");
            setErrorMessage("Ništa nije pronađeno");
          } else {
            setError(null);
            setErrorMessage(null);
          }
        })
        .catch((err: unknown) => {
          if (requestId !== requestIdRef.current) {
            return;
          }
          if (err instanceof DOMException && err.name === "AbortError") {
            return;
          }
          setHits([]);
          setHitsQuery(query);
          setLoading(false);
          if (err instanceof SearchUnavailableError) {
            setError("unavailable");
            setErrorMessage("Поиск временно недоступен");
            return;
          }
          setError("network");
          setErrorMessage("Nema veze sa serverom");
        });
    }, SEARCH_DEBOUNCE_MS);

    return () => {
      window.clearTimeout(timer);
    };
  }, [query, lat, lon]);

  useEffect(() => {
    return () => {
      abortRef.current?.abort();
    };
  }, []);

  return { hits, hitsQuery, loading, error, errorMessage };
}
