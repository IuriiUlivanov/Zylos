export interface BuildingAtResponse {
  id: string;
  label: string;
}

export interface BuildingAddress {
  id: string;
  label: string;
  street: string | null;
  housenumber: string;
  source: "osm" | "rgz";
}

export interface BuildingOrgListItem {
  id: string;
  name: string;
  category_slug: string | null;
  category_name: string | null;
  floor: string | null;
}

export type BuildingGeometry = {
  type: "Polygon" | "MultiPolygon";
  coordinates: unknown;
};

export interface BuildingDetailResponse {
  id: string;
  name: string | null;
  centroid: { lon: number; lat: number };
  geometry: BuildingGeometry;
  addresses: BuildingAddress[];
  organizations: BuildingOrgListItem[];
}
