export type SearchKind = "address" | "organization";
export type SearchSource = "osm" | "rgz" | "editorial";

export interface SearchHit {
  id: string;
  kind: SearchKind;
  label: string;
  lat: number;
  lon: number;
  building_id: string | null;
  name?: string;
  category_slug?: string;
  category_name?: string;
}

export interface SearchResponse {
  query: string;
  hits: SearchHit[];
  processingTimeMs: number;
}

export interface SearchErrorBody {
  error: "search_unavailable" | "internal";
}

export type SearchUiError = "unavailable" | "network" | "empty" | null;
