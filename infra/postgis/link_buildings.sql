-- =============================================================================
-- Zylos - Stage 2 - conflation: address -> building, organization -> address/building
-- Docs/STAGE-02-postgis.md section 6 ("Правила RGZ и conflation").
--
-- Run after every import:
--   psql -v ON_ERROR_STOP=1 -U zylos -d zylos -f infra/postgis/link_buildings.sql
--
-- Idempotent by construction: every run clears address.building_id,
-- organization.building_id and organization.address_id and recomputes them from
-- the current geometry. NO ROWS ARE EVER DELETED here - this script only writes
-- link columns and the *_norm matching helpers.
--
-- Everything is set-based (DISTINCT ON / LATERAL). There is no PL/pgSQL loop
-- over the ~80k address points; that would take minutes instead of seconds.
--
-- Distances are metres and therefore evaluated on `geography`. All input is
-- EPSG:4326. The geography GIST indexes created by schema.sql
-- (building_geog_gix, address_geog_gix, organization_geog_gix) are what makes
-- ST_DWithin(...::geography, ...) and the <-> nearest-neighbour operator fast;
-- the plain geometry indexes cannot serve metre-based predicates.
-- =============================================================================

BEGIN;

-- One bulk rewrite of link columns per run; durability of the intermediate
-- writes is irrelevant because the whole script is a single transaction.
SET LOCAL synchronous_commit = off;


-- -----------------------------------------------------------------------------
-- 0. Refresh the normalized matching keys
-- -----------------------------------------------------------------------------
-- zylos_normalize_street() folds case, Serbian Cyrillic and diacritics:
-- "Булевар ослобођења" = "Bulevar oslobođenja" = "bulevar oslobodjenja".
UPDATE public.address
SET street_norm = public.zylos_normalize_street(street)
WHERE street_norm IS DISTINCT FROM public.zylos_normalize_street(street);

UPDATE public.organization
SET addr_street_norm = public.zylos_normalize_street(addr_street)
WHERE addr_street_norm IS DISTINCT FROM public.zylos_normalize_street(addr_street);


-- -----------------------------------------------------------------------------
-- 0b. Reset all link columns
-- -----------------------------------------------------------------------------
-- Done up front and in one place, so that a building or address that was
-- deleted/moved by the last import cannot leave a stale link behind. Everything
-- below only ever fills these three columns back in.
UPDATE public.address
SET building_id = NULL
WHERE building_id IS NOT NULL;

UPDATE public.organization
SET building_id = NULL,
    address_id  = NULL
WHERE building_id IS NOT NULL
   OR address_id  IS NOT NULL;


-- =============================================================================
-- 1. address -> building
-- =============================================================================
-- Order fixed by the spec:
--   1.1 ST_Contains(building.geom, address.geom); on overlapping OSM outlines
--       take the one with the largest real-world area
--   1.2 otherwise the nearest building within 3 m (geography)
--   1.3 otherwise building_id stays NULL (legitimate: no outline in OSM)

-- 1.1 point in polygon
WITH contained AS (
  SELECT DISTINCT ON (a.id)
         a.id AS address_id,
         b.id AS building_id
  FROM public.address  a
  JOIN public.building b ON ST_Contains(b.geom, a.geom)
  ORDER BY a.id,
           ST_Area(b.geom::geography) DESC,  -- overlapping outlines: largest wins
           b.id                              -- deterministic tie-break
)
UPDATE public.address a
SET building_id = c.building_id
FROM contained c
WHERE a.id = c.address_id;

-- 1.2 nearest building within 3 m
-- The unlinked addresses are selected in a sub-query (not filtered after the
-- join) so the planner drives the nested loop from that small set and probes
-- building_geog_gix once per point instead of scanning every building.
WITH nearest AS (
  SELECT a.id AS address_id,
         b.id AS building_id
  FROM (
    SELECT id, geom FROM public.address WHERE building_id IS NULL
  ) a
  CROSS JOIN LATERAL (
    SELECT b.id
    FROM public.building b
    WHERE ST_DWithin(b.geom::geography, a.geom::geography, 3.0)
    ORDER BY b.geom::geography <-> a.geom::geography,
             b.id
    LIMIT 1
  ) b
)
UPDATE public.address a
SET building_id = n.building_id
FROM nearest n
WHERE a.id = n.address_id;


-- =============================================================================
-- 2. organization -> address
-- =============================================================================
-- Only for POI that actually carry addr:street + addr:housenumber.
-- Match rule (deliberately simple, no fuzzy rule set):
--   normalized street equality  AND  normalized house number equality
--   AND the candidate address is within 30 m of the POI point.
-- Among the survivors the nearest wins; an RGZ address beats an OSM one only
-- when the distance is identical (RGZ is the official register).

WITH matched AS (
  SELECT o.id AS org_id,
         m.id AS address_id
  FROM (
    SELECT id, geom, addr_street_norm, addr_housenumber
    FROM public.organization
    WHERE addr_street_norm IS NOT NULL
      AND public.zylos_normalize_housenumber(addr_housenumber) IS NOT NULL
  ) o
  CROSS JOIN LATERAL (
    SELECT a.id
    FROM public.address a
    WHERE a.street_norm = o.addr_street_norm
      AND public.zylos_normalize_housenumber(a.housenumber)
        = public.zylos_normalize_housenumber(o.addr_housenumber)
      AND ST_DWithin(a.geom::geography, o.geom::geography, 30.0)
    ORDER BY a.geom::geography <-> o.geom::geography,
             (a.source = 'rgz') DESC,
             a.id
    LIMIT 1
  ) m
)
UPDATE public.organization o
SET address_id = m.address_id
FROM matched m
WHERE o.id = m.org_id;


-- =============================================================================
-- 3. organization -> building
-- =============================================================================
--   3.1 ST_Contains(building.geom, organization.geom), largest area on overlap
--   3.2 otherwise the nearest building within 15 m (geography)
--   3.3 otherwise inherit the building of the address matched in step 2
--       (that address is at most 30 m away and shares street + house number)
-- organization.geom is never modified - it stays the POI node / entrance point.

-- 3.1 point in polygon
WITH contained AS (
  SELECT DISTINCT ON (o.id)
         o.id AS org_id,
         b.id AS building_id
  FROM public.organization o
  JOIN public.building    b ON ST_Contains(b.geom, o.geom)
  ORDER BY o.id,
           ST_Area(b.geom::geography) DESC,
           b.id
)
UPDATE public.organization o
SET building_id = c.building_id
FROM contained c
WHERE o.id = c.org_id;

-- 3.2 nearest building within 15 m
WITH nearest AS (
  SELECT o.id AS org_id,
         b.id AS building_id
  FROM (
    SELECT id, geom FROM public.organization WHERE building_id IS NULL
  ) o
  CROSS JOIN LATERAL (
    SELECT b.id
    FROM public.building b
    WHERE ST_DWithin(b.geom::geography, o.geom::geography, 15.0)
    ORDER BY b.geom::geography <-> o.geom::geography,
             b.id
    LIMIT 1
  ) b
)
UPDATE public.organization o
SET building_id = n.building_id
FROM nearest n
WHERE o.id = n.org_id;

-- 3.3 fall back to the building of the matched address
UPDATE public.organization o
SET building_id = a.building_id
FROM public.address a
WHERE o.building_id IS NULL
  AND o.address_id  = a.id
  AND a.building_id IS NOT NULL;


-- -----------------------------------------------------------------------------
-- 4. Statistics + report
-- -----------------------------------------------------------------------------
ANALYZE public.address;
ANALYZE public.organization;
ANALYZE public.building;

DO $report$
DECLARE
  addr_total    bigint;
  addr_linked   bigint;
  rgz_total     bigint;
  rgz_linked    bigint;
  org_total     bigint;
  org_bld       bigint;
  org_addr      bigint;
  bld_with_org  bigint;
BEGIN
  SELECT count(*),
         count(*) FILTER (WHERE building_id IS NOT NULL),
         count(*) FILTER (WHERE source = 'rgz'),
         count(*) FILTER (WHERE source = 'rgz' AND building_id IS NOT NULL)
    INTO addr_total, addr_linked, rgz_total, rgz_linked
  FROM public.address;

  SELECT count(*),
         count(*) FILTER (WHERE building_id IS NOT NULL),
         count(*) FILTER (WHERE address_id IS NOT NULL),
         count(DISTINCT building_id)
    INTO org_total, org_bld, org_addr, bld_with_org
  FROM public.organization;

  -- '%%' is a literal percent sign for RAISE; '%' is the value placeholder.
  RAISE NOTICE 'link_buildings: address       % of % linked to a building (% %%)',
    addr_linked, addr_total, coalesce(round(100.0 * addr_linked / nullif(addr_total, 0), 1), 0);
  RAISE NOTICE 'link_buildings: address(rgz)  % of % linked to a building (% %%)  [C5 >= 50]',
    rgz_linked, rgz_total, coalesce(round(100.0 * rgz_linked / nullif(rgz_total, 0), 1), 0);
  RAISE NOTICE 'link_buildings: organization  % of % linked to a building (% %%)  [C6 >= 40]',
    org_bld, org_total, coalesce(round(100.0 * org_bld / nullif(org_total, 0), 1), 0);
  RAISE NOTICE 'link_buildings: organization  % of % linked to an address',
    org_addr, org_total;
  RAISE NOTICE 'link_buildings: buildings with >= 1 organization: %  [C7 >= 300]',
    bld_with_org;
END
$report$;

COMMIT;
