import pg from "pg";
import { config } from "../config.js";
import { addressLabel, addressPublicId, orgPublicId, parseOrgPublicId } from "../lib/ids.js";
import type {
  BuildingAddress,
  BuildingAtResponse,
  BuildingDetailResponse,
  BuildingGeometry,
  BuildingOrgListItem,
} from "../types/building.js";
import type { OrgDetailResponse, OrgPin } from "../types/org.js";

const { Pool } = pg;

export const BUILDING_DWITHIN_M = 3;
export const SIMPLIFY_TOLERANCE = 0.00001;
export const MAX_ORGS_PER_BUILDING = 100;
export const MAX_ORG_PINS = 200;
export const MAX_GEOMETRY_BYTES = 51_200;

export const pool = config.databaseUrl
  ? new Pool({
      connectionString: config.databaseUrl,
      max: 10,
      connectionTimeoutMillis: 2000,
      idleTimeoutMillis: 30_000,
    })
  : null;

export async function postgisHealth(): Promise<"ok" | "down"> {
  if (!pool) {
    return "down";
  }
  try {
    await pool.query("SELECT 1");
    return "ok";
  } catch {
    return "down";
  }
}

export function requirePool(): pg.Pool {
  if (!pool) {
    const err = new Error("database_unavailable") as Error & { statusCode: number };
    err.statusCode = 503;
    throw err;
  }
  return pool;
}

function asFiniteNumber(value: unknown): number | null {
  if (typeof value === "number" && Number.isFinite(value)) {
    return value;
  }
  if (typeof value === "string" && value.trim() !== "") {
    const parsed = Number(value);
    if (Number.isFinite(parsed)) {
      return parsed;
    }
  }
  return null;
}

function parseGeometry(raw: string | null): BuildingGeometry {
  if (!raw) {
    throw new Error("building_geometry_missing");
  }
  const parsed = JSON.parse(raw) as { type?: string; coordinates?: unknown };
  if (parsed.type !== "Polygon" && parsed.type !== "MultiPolygon") {
    throw new Error("building_geometry_unsupported");
  }
  return parsed as BuildingGeometry;
}

function geometryBytes(geometry: BuildingGeometry): number {
  return Buffer.byteLength(JSON.stringify(geometry), "utf8");
}

export async function isInsideCity(lon: number, lat: number): Promise<boolean> {
  const db = requirePool();
  const result = await db.query<{ inside: boolean }>(
    `SELECT ST_Contains(geom, ST_SetSRID(ST_Point($1, $2), 4326)) AS inside
     FROM public.city_boundary
     WHERE id = 1`,
    [lon, lat],
  );
  return result.rows[0]?.inside === true;
}

export async function findBuildingAt(lon: number, lat: number): Promise<BuildingAtResponse | null> {
  const db = requirePool();
  const result = await db.query<{ id: string; label: string }>(
    `WITH pt AS (
       SELECT ST_SetSRID(ST_Point($1, $2), 4326) AS geom
     ),
     hit AS (
       SELECT b.id, b.name
       FROM public.building b, pt
       WHERE ST_Contains(b.geom, pt.geom)
       ORDER BY ST_Area(b.geom) DESC
       LIMIT 1
     ),
     near AS (
       SELECT b.id, b.name
       FROM public.building b, pt
       WHERE NOT EXISTS (SELECT 1 FROM hit)
         AND ST_DWithin(b.geom::geography, pt.geom::geography, $3)
       ORDER BY b.geom::geography <-> pt.geom::geography
       LIMIT 1
     ),
     found AS (
       SELECT * FROM hit
       UNION ALL
       SELECT * FROM near
     )
     SELECT
       found.id::text AS id,
       COALESCE(
         (
           SELECT NULLIF(btrim(concat_ws(' ', NULLIF(btrim(a.street), ''), NULLIF(btrim(a.housenumber), ''))), '')
           FROM public.address a
           WHERE a.building_id = found.id
           ORDER BY CASE a.source WHEN 'rgz' THEN 0 ELSE 1 END, a.street NULLS LAST, a.housenumber
           LIMIT 1
         ),
         NULLIF(btrim(found.name), ''),
         'Zgrada'
       ) AS label
     FROM found`,
    [lon, lat, BUILDING_DWITHIN_M],
  );
  return result.rows[0] ?? null;
}

async function loadGeometry(id: string): Promise<{
  id: string;
  name: string | null;
  lon: number;
  lat: number;
  geometry: BuildingGeometry;
} | null> {
  const db = requirePool();
  const tolerances = [
    SIMPLIFY_TOLERANCE,
    SIMPLIFY_TOLERANCE * 5,
    SIMPLIFY_TOLERANCE * 20,
    SIMPLIFY_TOLERANCE * 50,
  ];

  for (const tol of tolerances) {
    const result = await db.query<{
      id: string;
      name: string | null;
      lon: number | string;
      lat: number | string;
      geometry: string | null;
    }>(
      `SELECT
         b.id::text AS id,
         NULLIF(btrim(b.name), '') AS name,
         ST_X(ST_Centroid(b.geom)) AS lon,
         ST_Y(ST_Centroid(b.geom)) AS lat,
         COALESCE(
           NULLIF(ST_AsGeoJSON(ST_MakeValid(ST_SimplifyPreserveTopology(b.geom, $2))), ''),
           ST_AsGeoJSON(b.geom)
         ) AS geometry
       FROM public.building b
       WHERE b.id = $1::uuid`,
      [id, tol],
    );
    const row = result.rows[0];
    if (!row) {
      return null;
    }
    try {
      const geometry = parseGeometry(row.geometry);
      if (geometryBytes(geometry) <= MAX_GEOMETRY_BYTES) {
        return {
          id: row.id,
          name: row.name,
          lon: asFiniteNumber(row.lon) ?? 0,
          lat: asFiniteNumber(row.lat) ?? 0,
          geometry,
        };
      }
    } catch {
      continue;
    }
  }

  const fallback = await db.query<{
    id: string;
    name: string | null;
    lon: number | string;
    lat: number | string;
    geometry: string | null;
  }>(
    `SELECT
       b.id::text AS id,
       NULLIF(btrim(b.name), '') AS name,
       ST_X(ST_Centroid(b.geom)) AS lon,
       ST_Y(ST_Centroid(b.geom)) AS lat,
       ST_AsGeoJSON(ST_Envelope(b.geom)) AS geometry
     FROM public.building b
     WHERE b.id = $1::uuid`,
    [id],
  );
  const row = fallback.rows[0];
  if (!row) {
    return null;
  }
  return {
    id: row.id,
    name: row.name,
    lon: asFiniteNumber(row.lon) ?? 0,
    lat: asFiniteNumber(row.lat) ?? 0,
    geometry: parseGeometry(row.geometry),
  };
}

export async function getBuildingById(id: string): Promise<BuildingDetailResponse | null> {
  const core = await loadGeometry(id);
  if (!core) {
    return null;
  }
  const db = requirePool();
  const [addresses, organizations] = await Promise.all([
    db.query<{
      source: "osm" | "rgz";
      source_id: string;
      street: string | null;
      housenumber: string;
    }>(
      `SELECT source, source_id, street, housenumber
       FROM public.address
       WHERE building_id = $1::uuid
       ORDER BY source, street NULLS LAST, housenumber`,
      [id],
    ),
    db.query<{
      source: "osm" | "editorial";
      source_id: string;
      name: string;
      floor: string | null;
      category_slug: string | null;
      category_name: string | null;
    }>(
      `SELECT
         o.source,
         o.source_id,
         o.name,
         o.floor,
         c.slug AS category_slug,
         c.name_sr AS category_name
       FROM public.organization o
       LEFT JOIN public.category c ON c.id = o.category_id
       WHERE o.building_id = $1::uuid
       ORDER BY o.name
       LIMIT $2`,
      [id, MAX_ORGS_PER_BUILDING],
    ),
  ]);

  const addressRows: BuildingAddress[] = addresses.rows.map((row) => ({
    id: addressPublicId(row.source, row.source_id),
    label: addressLabel(row.street, row.housenumber) || row.housenumber,
    street: row.street,
    housenumber: row.housenumber,
    source: row.source,
  }));

  const orgRows: BuildingOrgListItem[] = organizations.rows.map((row) => ({
    id: orgPublicId(row.source, row.source_id),
    name: row.name,
    category_slug: row.category_slug,
    category_name: row.category_name,
    floor: row.floor,
  }));

  return {
    id: core.id,
    name: core.name,
    centroid: { lon: core.lon, lat: core.lat },
    geometry: core.geometry,
    addresses: addressRows,
    organizations: orgRows,
  };
}

export async function getOrgByPublicId(publicId: string): Promise<OrgDetailResponse | null> {
  const parsed = parseOrgPublicId(publicId);
  if (!parsed) {
    return null;
  }
  const db = requirePool();
  const result = await db.query<{
    source: "osm" | "editorial";
    source_id: string;
    name: string;
    phones: string[] | null;
    website: string | null;
    hours: string | null;
    floor: string | null;
    tags: string[] | null;
    building_id: string | null;
    lon: number | string;
    lat: number | string;
    category_slug: string | null;
    category_name: string | null;
    addr_street: string | null;
    addr_housenumber: string | null;
    address_street: string | null;
    address_housenumber: string | null;
  }>(
    `SELECT
       o.source,
       o.source_id,
       o.name,
       o.phones,
       NULLIF(btrim(o.website), '') AS website,
       NULLIF(btrim(o.hours), '') AS hours,
       o.floor,
       o.tags,
       o.building_id::text AS building_id,
       ST_X(o.geom) AS lon,
       ST_Y(o.geom) AS lat,
       c.slug AS category_slug,
       c.name_sr AS category_name,
       o.addr_street,
       o.addr_housenumber,
       a.street AS address_street,
       a.housenumber AS address_housenumber
     FROM public.organization o
     LEFT JOIN public.category c ON c.id = o.category_id
     LEFT JOIN public.address a ON a.id = o.address_id
     WHERE o.source = $1 AND o.source_id = $2
     LIMIT 1`,
    [parsed.source, parsed.sourceId],
  );
  const row = result.rows[0];
  if (!row) {
    return null;
  }

  const street = row.address_street ?? row.addr_street;
  const housenumber = row.address_housenumber ?? row.addr_housenumber;
  const label = addressLabel(street, housenumber);

  return {
    id: orgPublicId(row.source, row.source_id),
    name: row.name,
    source: row.source,
    category_slug: row.category_slug,
    category_name: row.category_name,
    phones: Array.isArray(row.phones) ? row.phones.filter((item) => Boolean(item && item.trim())) : [],
    website: row.website,
    hours: row.hours,
    floor: row.floor,
    tags: Array.isArray(row.tags) ? row.tags : [],
    address: label
      ? { label, street: street ?? null, housenumber: housenumber ?? null }
      : null,
    building_id: row.building_id,
    location: {
      lon: asFiniteNumber(row.lon) ?? 0,
      lat: asFiniteNumber(row.lat) ?? 0,
    },
  };
}

export async function listOrgsInBbox(
  minLon: number,
  minLat: number,
  maxLon: number,
  maxLat: number,
  limit = MAX_ORG_PINS,
): Promise<OrgPin[]> {
  const db = requirePool();
  const capped = Math.min(Math.max(1, limit), MAX_ORG_PINS);
  const result = await db.query<{
    source: "osm" | "editorial";
    source_id: string;
    name: string;
    category_slug: string | null;
    lon: number | string;
    lat: number | string;
  }>(
    `SELECT
       o.source,
       o.source_id,
       o.name,
       c.slug AS category_slug,
       ST_X(o.geom) AS lon,
       ST_Y(o.geom) AS lat
     FROM public.organization o
     LEFT JOIN public.category c ON c.id = o.category_id
     WHERE o.geom && ST_MakeEnvelope($1, $2, $3, $4, 4326)
     ORDER BY o.name
     LIMIT $5`,
    [minLon, minLat, maxLon, maxLat, capped],
  );
  return result.rows.map((row) => ({
    id: orgPublicId(row.source, row.source_id),
    name: row.name,
    category_slug: row.category_slug,
    lon: asFiniteNumber(row.lon) ?? 0,
    lat: asFiniteNumber(row.lat) ?? 0,
  }));
}
