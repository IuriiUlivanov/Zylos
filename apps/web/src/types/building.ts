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

export interface BuildingAtResponse {
  id: string;
  label: string;
}

export interface BuildingDetail {
  id: string;
  name: string | null;
  centroid: { lon: number; lat: number };
  geometry: BuildingGeometry;
  addresses: BuildingAddress[];
  organizations: BuildingOrgListItem[];
}

export class BuildingNotFoundError extends Error {
  constructor() {
    super("building_not_found");
    this.name = "BuildingNotFoundError";
  }
}

export class OutsideCityError extends Error {
  constructor() {
    super("outside_city");
    this.name = "OutsideCityError";
  }
}
