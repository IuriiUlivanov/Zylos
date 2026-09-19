export interface OrgPin {
  id: string;
  name: string;
  category_slug: string | null;
  lon: number;
  lat: number;
}

export interface OrgAddress {
  label: string;
  street: string | null;
  housenumber: string | null;
}

export interface OrgDetailResponse {
  id: string;
  name: string;
  source: "osm" | "editorial";
  category_slug: string | null;
  category_name: string | null;
  phones: string[];
  website: string | null;
  hours: string | null;
  floor: string | null;
  tags: string[];
  address: OrgAddress | null;
  building_id: string | null;
  location: { lon: number; lat: number };
}
