import type { SearchHit } from "../types/search";

export function orgHit(overrides: Partial<SearchHit> = {}): SearchHit {
  return {
    id: "org:osm:n1",
    kind: "organization",
    label: "Zelena apoteka",
    lat: 45.2493968,
    lon: 19.8409398,
    building_id: "bldg:1",
    name: "Zelena apoteka",
    category_slug: "pharmacy",
    category_name: "Apoteka",
    ...overrides,
  };
}

export function addressHit(overrides: Partial<SearchHit> = {}): SearchHit {
  return {
    id: "addr:rgz:1",
    kind: "address",
    label: "Bulevar oslobođenja 12",
    lat: 45.255,
    lon: 19.845,
    building_id: null,
    ...overrides,
  };
}
