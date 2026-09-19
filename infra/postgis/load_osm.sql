-- =============================================================================
-- Zylos - Stage 2 - osm_staging -> application tables
-- Docs/STAGE-02-postgis.md section 5 ("Правила импорта OSM") and section 8, step 4.
--
--   psql -v ON_ERROR_STOP=1 -U zylos -d zylos -f infra/postgis/load_osm.sql
--
-- Runs after schema.sql + seed_categories.sql and after the importer has filled
-- osm_staging (osm2pgsql flex for building_src / address_src / poi_src, ogr2ogr
-- for city_boundary_src). Single transaction: either the whole reload lands or
-- nothing does.
--
-- STAGING CONTRACT (columns are read by name, extra columns are ignored):
--   osm_staging.building_src(osm_type, osm_id, name, name_sr_latn, name_sr,
--                            building_levels, height, geom)
--   osm_staging.address_src (osm_type, osm_id, street, housenumber, postcode,
--                            street_sr_cyrl, geom)
--   osm_staging.poi_src     (osm_type, osm_id, name, amenity, shop, office,
--                            tourism, healthcare, craft, leisure, cuisine,
--                            brand, phone, website, opening_hours, level,
--                            addr_street, addr_housenumber, geom)
--   osm_staging.city_boundary_src(geom)   -- only `geom` is used
-- building_levels and height are TEXT in staging on purpose ("4", "4;5",
-- "12.5 m", "ground") and are parsed defensively below.
--
-- IMPORTANT: osm2pgsql must target --schema=osm_staging. Never run it with
-- --slim --drop against the public schema - it would drop the application
-- tables. This file is the only writer of source='osm' rows in public.
--
-- Rows with source='editorial' (organization) and source='rgz' (address) are
-- never read, updated or deleted here.
--
-- building_id / address_id links are NOT set here: infra/postgis/link_buildings.sql
-- owns them and must run after this file.
-- =============================================================================

BEGIN;

SET LOCAL synchronous_commit = off;


-- -----------------------------------------------------------------------------
-- 0. Staging tolerance
-- -----------------------------------------------------------------------------
-- schema.sql already creates these, but the importer may have dropped one of
-- them (osm2pgsql recreates its own tables). Re-create what is missing so that
-- this file always parses and runs, then let the row-count guards below decide
-- whether the import actually produced data.
CREATE SCHEMA IF NOT EXISTS osm_staging;

CREATE TABLE IF NOT EXISTS osm_staging.building_src (
  osm_type        char(1),
  osm_id          bigint,
  name            text,
  name_sr_latn    text,
  name_sr         text,
  building_levels text,
  height          text,
  geom            geometry(Geometry, 4326)
);

CREATE TABLE IF NOT EXISTS osm_staging.address_src (
  osm_type       char(1),
  osm_id         bigint,
  street         text,
  housenumber    text,
  postcode       text,
  street_sr_cyrl text,
  geom           geometry(Point, 4326)
);

CREATE TABLE IF NOT EXISTS osm_staging.poi_src (
  osm_type         char(1),
  osm_id           bigint,
  name             text,
  amenity          text,
  shop             text,
  office           text,
  tourism          text,
  healthcare       text,
  craft            text,
  leisure          text,
  cuisine          text,
  brand            text,
  phone            text,
  website          text,
  opening_hours    text,
  level            text,
  addr_street      text,
  addr_housenumber text,
  geom             geometry(Point, 4326)
);

CREATE TABLE IF NOT EXISTS osm_staging.city_boundary_src (
  geom geometry(Geometry, 4326)
);


-- -----------------------------------------------------------------------------
-- 1. Guards
-- -----------------------------------------------------------------------------
-- A failed or half-finished import leaves staging empty. Loading from it would
-- silently delete every OSM row in public (see the prune steps below), so stop
-- here instead. building_src is the canary: no city has zero buildings.
DO $guard_empty$
DECLARE
  n_bld bigint;
BEGIN
  SELECT count(*) INTO n_bld FROM osm_staging.building_src;

  IF n_bld = 0 THEN
    RAISE EXCEPTION
      'load_osm.sql: osm_staging.building_src is empty - the OSM import did not '
      'run or failed. Refusing to load (that would wipe the existing OSM rows). '
      'Run the osm2pgsql flex import into schema osm_staging first.';
  END IF;

  RAISE NOTICE 'load_osm.sql: staging rows - building_src %, address_src %, poi_src %, city_boundary_src %',
    n_bld,
    (SELECT count(*) FROM osm_staging.address_src),
    (SELECT count(*) FROM osm_staging.poi_src),
    (SELECT count(*) FROM osm_staging.city_boundary_src);
END
$guard_empty$;

-- SRID 0 means "unknown" - it cannot be transformed and it would poison every
-- distance computation in link_buildings.sql. Fail loudly instead.
DO $guard_srid$
DECLARE
  bad bigint;
BEGIN
  SELECT count(*) INTO bad
  FROM (
    (SELECT 1 FROM osm_staging.building_src      WHERE geom IS NOT NULL AND ST_SRID(geom) = 0 LIMIT 1)
    UNION ALL
    (SELECT 1 FROM osm_staging.address_src       WHERE geom IS NOT NULL AND ST_SRID(geom) = 0 LIMIT 1)
    UNION ALL
    (SELECT 1 FROM osm_staging.poi_src           WHERE geom IS NOT NULL AND ST_SRID(geom) = 0 LIMIT 1)
    UNION ALL
    (SELECT 1 FROM osm_staging.city_boundary_src WHERE geom IS NOT NULL AND ST_SRID(geom) = 0 LIMIT 1)
  ) x;

  IF bad > 0 THEN
    RAISE EXCEPTION
      'load_osm.sql: osm_staging contains geometries with SRID 0. Re-run the '
      'import with an explicit SRID (osm2pgsql: projection 4326; '
      'ogr2ogr: -t_srs EPSG:4326 -a_srs EPSG:4326).';
  END IF;
END
$guard_srid$;


-- -----------------------------------------------------------------------------
-- 2. city_boundary  (relation 1649672, exactly one row)
-- -----------------------------------------------------------------------------
-- ST_Union dissolves a multi-feature GeoJSON, ST_MakeValid repairs self-touching
-- rings, ST_CollectionExtract(..., 3) throws away the stray lines/points that
-- MakeValid can emit, so the result always casts to MultiPolygon.
-- Nothing happens when city_boundary_src is empty: the aggregate yields NULL and
-- the WHERE clause filters the row out, leaving the previously loaded boundary
-- untouched.
INSERT INTO public.city_boundary AS cb (id, osm_relation_id, geom, loaded_at)
SELECT 1, 1649672, u.geom, now()
FROM (
  SELECT ST_Multi(ST_CollectionExtract(
           ST_MakeValid(ST_Union(ST_Force2D(
             CASE WHEN ST_SRID(s.geom) = 4326 THEN s.geom
                  ELSE ST_Transform(s.geom, 4326) END))), 3)) AS geom
  FROM osm_staging.city_boundary_src s
  WHERE s.geom IS NOT NULL
    AND NOT ST_IsEmpty(s.geom)
) u
WHERE u.geom IS NOT NULL
  AND NOT ST_IsEmpty(u.geom)
ON CONFLICT (osm_relation_id) DO UPDATE
SET geom      = EXCLUDED.geom,
    loaded_at = now()
WHERE cb.geom IS DISTINCT FROM EXCLUDED.geom;


-- -----------------------------------------------------------------------------
-- 3. building
-- -----------------------------------------------------------------------------
-- Accepted set, materialized once: it is both the upsert source and the
-- reference for the prune step, so a building that stops qualifying (too small,
-- geometry no longer polygonal) also disappears from public.building.
--
--   * ST_MakeValid            - DoD C8 (zero invalid geometries)
--   * ST_CollectionExtract 3  - keep polygonal parts only, drop stray lines
--   * area >= 8 m2 (geography) - STAGE-02 section 5: throw away the noise
--   * name = name / name:sr-Latn / name:sr, first non-empty
DROP TABLE IF EXISTS _osm_building;
CREATE TEMP TABLE _osm_building ON COMMIT DROP AS
WITH raw AS (
  -- osm2pgsql flex styles write the type letter either way ('w' or 'W'
  -- depending on how the Lua builds it); public.building stores lowercase
  -- (CHECK osm_type IN ('n','w','r')), so normalize here instead of demanding
  -- a specific spelling from the importer.
  SELECT lower(btrim(s.osm_type::text)) AS osm_type,
         s.osm_id,
         coalesce(nullif(btrim(s.name),         ''),
                  nullif(btrim(s.name_sr_latn), ''),
                  nullif(btrim(s.name_sr),      '')) AS name,
         -- "4" -> 4, "4;5" -> 4, "ground" -> NULL. At most 3 digits, so the
         -- ::int cast below can never overflow on vandalised values.
         nullif(substring(btrim(coalesce(s.building_levels, '')) from '^([0-9]{1,3})'), '') AS levels_txt,
         -- "12.5 m" / "12,5" -> 12.5, "12'6\"" -> 12, "high" -> NULL.
         nullif(substring(replace(btrim(coalesce(s.height, '')), ',', '.')
                          from '^([0-9]+(?:\.[0-9]+)?)'), '') AS height_txt,
         ST_Multi(ST_CollectionExtract(
           ST_MakeValid(ST_Force2D(
             CASE WHEN ST_SRID(s.geom) = 4326 THEN s.geom
                  ELSE ST_Transform(s.geom, 4326) END)), 3)) AS geom
  FROM osm_staging.building_src s
  WHERE s.osm_id   IS NOT NULL
    AND lower(btrim(s.osm_type::text)) IN ('n', 'w', 'r')
    AND s.geom     IS NOT NULL
    AND NOT ST_IsEmpty(s.geom)
),
typed AS (
  SELECT osm_type,
         osm_id,
         name,
         geom,
         levels_txt::int     AS levels,
         height_txt::numeric AS height_m
  FROM raw
  WHERE geom IS NOT NULL
    AND NOT ST_IsEmpty(geom)
    AND ST_Area(geom::geography) >= 8.0
)
-- Same (osm_type, osm_id) twice in staging would break ON CONFLICT DO UPDATE
-- ("cannot affect row a second time"); keep the largest footprint.
SELECT DISTINCT ON (osm_type, osm_id)
       osm_type,
       osm_id,
       name,
       geom,
       CASE WHEN levels   BETWEEN 1 AND 200 THEN levels   END AS building_levels,
       CASE WHEN height_m > 0 AND height_m <= 500 THEN height_m END AS height_m
FROM typed
ORDER BY osm_type, osm_id, ST_Area(geom::geography) DESC;

CREATE UNIQUE INDEX _osm_building_key ON _osm_building (osm_type, osm_id);
ANALYZE _osm_building;

INSERT INTO public.building AS b (osm_type, osm_id, geom, name, building_levels, height_m)
SELECT osm_type, osm_id, geom, name, building_levels, height_m
FROM _osm_building
ON CONFLICT (osm_type, osm_id) DO UPDATE
SET geom            = EXCLUDED.geom,
    name            = EXCLUDED.name,
    building_levels = EXCLUDED.building_levels,
    height_m        = EXCLUDED.height_m
-- Skip no-op updates: keeps the table from bloating on every re-import.
WHERE b.geom            IS DISTINCT FROM EXCLUDED.geom
   OR b.name            IS DISTINCT FROM EXCLUDED.name
   OR b.building_levels IS DISTINCT FROM EXCLUDED.building_levels
   OR b.height_m        IS DISTINCT FROM EXCLUDED.height_m;

-- Prune: a building that vanished from OSM must vanish here too. Safe because
-- the guard above proved staging is non-empty. address.building_id and
-- organization.building_id fall back to NULL through ON DELETE SET NULL and are
-- recomputed by link_buildings.sql.
DO $prune_building$
DECLARE
  d bigint;
BEGIN
  DELETE FROM public.building b
  WHERE NOT EXISTS (
    SELECT 1 FROM _osm_building t
    WHERE t.osm_type = b.osm_type AND t.osm_id = b.osm_id
  );
  GET DIAGNOSTICS d = ROW_COUNT;
  RAISE NOTICE 'load_osm.sql: building - % accepted from staging, % stale row(s) deleted',
    (SELECT count(*) FROM _osm_building), d;
END
$prune_building$;


-- -----------------------------------------------------------------------------
-- 4. address, source = 'osm'
-- -----------------------------------------------------------------------------
-- Only rows that carry a house number (STAGE-02 section 5: "Не импортировать
-- без номера"). source_id is 'n123' / 'w456' / 'r789'.
DROP TABLE IF EXISTS _osm_address;
CREATE TEMP TABLE _osm_address ON COMMIT DROP AS
SELECT DISTINCT ON (source_id) *
FROM (
  -- source_id is always lowercase 'n123' / 'w456' / 'r789', whatever case the
  -- flex style used for osm_type.
  SELECT lower(btrim(s.osm_type::text)) || s.osm_id::text AS source_id,
         nullif(btrim(s.street),         '')   AS street,
         btrim(s.housenumber)                  AS housenumber,
         nullif(btrim(s.street_sr_cyrl), '')   AS street_sr_cyrl,
         nullif(btrim(s.postcode),       '')   AS postcode,
         ST_Force2D(CASE WHEN ST_SRID(s.geom) = 4326 THEN s.geom
                         ELSE ST_Transform(s.geom, 4326) END) AS geom
  FROM osm_staging.address_src s
  WHERE s.osm_id      IS NOT NULL
    AND lower(btrim(s.osm_type::text)) IN ('n', 'w', 'r')
    AND s.housenumber IS NOT NULL
    AND btrim(s.housenumber) <> ''
    AND s.geom        IS NOT NULL
    AND NOT ST_IsEmpty(s.geom)
) x
-- Prefer the variant that actually names a street when staging holds duplicates.
ORDER BY source_id, (street IS NOT NULL) DESC, (postcode IS NOT NULL) DESC;

CREATE UNIQUE INDEX _osm_address_key ON _osm_address (source_id);
ANALYZE _osm_address;

INSERT INTO public.address AS a
  (source, source_id, street, housenumber, street_sr_cyrl, postcode, street_norm, geom)
SELECT 'osm',
       source_id,
       street,
       housenumber,
       street_sr_cyrl,
       postcode,
       public.zylos_normalize_street(street),
       geom
FROM _osm_address
ON CONFLICT (source, source_id) DO UPDATE
SET street         = EXCLUDED.street,
    housenumber    = EXCLUDED.housenumber,
    street_sr_cyrl = EXCLUDED.street_sr_cyrl,
    postcode       = EXCLUDED.postcode,
    street_norm    = EXCLUDED.street_norm,
    geom           = EXCLUDED.geom
    -- building_id intentionally absent: link_buildings.sql owns it.
WHERE a.street         IS DISTINCT FROM EXCLUDED.street
   OR a.housenumber    IS DISTINCT FROM EXCLUDED.housenumber
   OR a.street_sr_cyrl IS DISTINCT FROM EXCLUDED.street_sr_cyrl
   OR a.postcode       IS DISTINCT FROM EXCLUDED.postcode
   OR a.street_norm    IS DISTINCT FROM EXCLUDED.street_norm
   OR a.geom           IS DISTINCT FROM EXCLUDED.geom;

-- Prune OSM addresses only, and only when this import produced any. RGZ rows
-- (source='rgz') are a different register and are never touched here.
DO $prune_address$
DECLARE
  n bigint;
  d bigint;
BEGIN
  SELECT count(*) INTO n FROM _osm_address;

  IF n = 0 THEN
    RAISE WARNING 'load_osm.sql: address_src produced no usable rows - keeping the '
                  'existing source=''osm'' addresses instead of deleting them';
  ELSE
    DELETE FROM public.address a
    WHERE a.source = 'osm'
      AND NOT EXISTS (SELECT 1 FROM _osm_address t WHERE t.source_id = a.source_id);
    GET DIAGNOSTICS d = ROW_COUNT;
    RAISE NOTICE 'load_osm.sql: address(osm) - % accepted from staging, % stale row(s) deleted', n, d;
  END IF;
END
$prune_address$;


-- -----------------------------------------------------------------------------
-- 5. organization, source = 'osm'
-- -----------------------------------------------------------------------------
-- Import rule (STAGE-02 section 5): non-empty name AND one of the seven
-- directory keys with a value we actually map. Street furniture
-- (amenity=bench / waste_basket / bicycle_parking / ...) never matches because
-- amenity, tourism and leisure use explicit allow-lists.
--
-- CATEGORY RESOLUTION - matches the wildcard convention of seed_categories.sql:
--   1. one primary key is chosen by priority
--        amenity > shop > healthcare > tourism > leisure > craft > office
--   2. category lookup for that (key, value):
--        a. exact   (key, value)   e.g. (amenity, cafe)   -> slug cafe
--        b. per-key (key, '*')     e.g. (shop, '*')       -> slug shop
--        c. global  slug 'other'
--   Because the allow-lists only contain values that seed_categories.sql maps
--   explicitly, and shop/office/healthcare/craft have (key,'*') rows, step (c)
--   is effectively unreachable - which is what keeps the `other` share far
--   below the 35% limit in scripts/stage02-verify.sql.
DROP TABLE IF EXISTS _osm_org;
CREATE TEMP TABLE _osm_org ON COMMIT DROP AS
WITH src AS (
  SELECT lower(btrim(s.osm_type::text)) || s.osm_id::text AS source_id,
         btrim(s.name)                           AS name,
         lower(nullif(btrim(s.amenity),    ''))  AS amenity,
         lower(nullif(btrim(s.shop),       ''))  AS shop,
         lower(nullif(btrim(s.office),     ''))  AS office,
         lower(nullif(btrim(s.tourism),    ''))  AS tourism,
         lower(nullif(btrim(s.healthcare), ''))  AS healthcare,
         lower(nullif(btrim(s.craft),      ''))  AS craft,
         lower(nullif(btrim(s.leisure),    ''))  AS leisure,
         nullif(btrim(s.cuisine),          '')   AS cuisine,
         nullif(btrim(s.brand),            '')   AS brand,
         nullif(btrim(s.phone),            '')   AS phone,
         nullif(btrim(s.website),          '')   AS website,
         nullif(btrim(s.opening_hours),    '')   AS opening_hours,
         nullif(btrim(s.level),            '')   AS level,
         nullif(btrim(s.addr_street),      '')   AS addr_street,
         nullif(btrim(s.addr_housenumber), '')   AS addr_housenumber,
         ST_Force2D(CASE WHEN ST_SRID(s.geom) = 4326 THEN s.geom
                         ELSE ST_Transform(s.geom, 4326) END) AS geom
  FROM osm_staging.poi_src s
  WHERE s.osm_id   IS NOT NULL
    AND lower(btrim(s.osm_type::text)) IN ('n', 'w', 'r')
    AND s.name     IS NOT NULL
    AND btrim(s.name) <> ''          -- DoD C9: no nameless POI
    AND s.geom     IS NOT NULL
    AND NOT ST_IsEmpty(s.geom)
),
picked AS (
  SELECT src.*,
         t.osm_key,
         t.osm_value
  FROM src
  CROSS JOIN LATERAL (
    SELECT k AS osm_key, v AS osm_value
    FROM unnest(
           ARRAY['amenity', 'shop', 'healthcare', 'tourism', 'leisure', 'craft', 'office'],
           ARRAY[src.amenity, src.shop, src.healthcare, src.tourism, src.leisure, src.craft, src.office]
         ) WITH ORDINALITY AS cand(k, v, prio)
    WHERE v IS NOT NULL
      AND CASE k
            WHEN 'amenity' THEN v IN (
                   -- STAGE-02 section 5 list ...
                   'cafe', 'restaurant', 'fast_food', 'bar', 'pub', 'ice_cream',
                   'pharmacy', 'bank', 'school', 'kindergarten', 'university',
                   'college', 'hospital', 'clinic', 'doctors', 'dentists',
                   'veterinary', 'theatre', 'cinema', 'library',
                   'place_of_worship', 'fuel', 'parking', 'police',
                   'post_office', 'townhall', 'community_centre',
                   -- ... plus other value that is unmistakably a business /
                   -- institution and has its own row in seed_categories.sql.
                   'nightclub', 'marketplace', 'driving_school', 'car_rental',
                   'car_wash', 'bureau_de_change', 'courthouse', 'embassy',
                   'fire_station', 'social_facility', 'arts_centre',
                   'food_court', 'internet_cafe', 'coworking_space',
                   'language_school', 'music_school', 'bus_station',
                   'nursing_home')
            WHEN 'tourism' THEN v IN ('hotel', 'museum', 'attraction', 'gallery',
                                      'guest_house', 'hostel')
            WHEN 'leisure' THEN v IN ('sports_centre', 'stadium', 'fitness_centre')
            WHEN 'shop'    THEN v NOT IN ('vacant', 'no', 'empty', 'closed')
            ELSE v <> 'no'   -- office / healthcare / craft: any value with a name
          END
    ORDER BY prio
    LIMIT 1
  ) t
)
SELECT DISTINCT ON (source_id)
       p.source_id,
       p.name,
       coalesce(
         (SELECT c.id FROM public.category c
           WHERE c.osm_key = p.osm_key AND c.osm_value = p.osm_value),
         (SELECT c.id FROM public.category c
           WHERE c.osm_key = p.osm_key AND c.osm_value = '*'),
         (SELECT c.id FROM public.category c WHERE c.slug = 'other')
       ) AS category_id,
       -- Raw useful tags as 'key=value'; the matched pair comes first.
       (SELECT array_agg(k || '=' || v ORDER BY (k = p.osm_key) DESC, ord)
        FROM unnest(
               ARRAY['amenity', 'shop', 'healthcare', 'tourism', 'leisure',
                     'craft', 'office', 'cuisine', 'brand'],
               ARRAY[p.amenity, p.shop, p.healthcare, p.tourism, p.leisure,
                     p.craft, p.office, p.cuisine, p.brand]
             ) WITH ORDINALITY AS tg(k, v, ord)
        WHERE v IS NOT NULL) AS tags,
       -- OSM convention: several phone numbers separated by ';'
       (SELECT array_agg(ph ORDER BY ord)
        FROM (
          SELECT btrim(u) AS ph, ord
          FROM unnest(string_to_array(coalesce(p.phone, ''), ';')) WITH ORDINALITY AS u(u, ord)
        ) q
        WHERE q.ph <> '') AS phones,
       p.website,
       p.opening_hours AS hours,
       p.level         AS floor,
       p.addr_street,
       p.addr_housenumber,
       p.geom
FROM picked p
ORDER BY p.source_id;

CREATE UNIQUE INDEX _osm_org_key ON _osm_org (source_id);
ANALYZE _osm_org;

INSERT INTO public.organization AS o
  (source, source_id, name, category_id, tags, phones, website, hours, floor,
   addr_street, addr_housenumber, addr_street_norm, geom)
SELECT 'osm',
       source_id,
       name,
       category_id,
       tags,
       phones,
       website,
       hours,
       floor,
       addr_street,
       addr_housenumber,
       public.zylos_normalize_street(addr_street),
       geom
FROM _osm_org
ON CONFLICT (source, source_id) DO UPDATE
SET name             = EXCLUDED.name,
    category_id      = EXCLUDED.category_id,
    tags             = EXCLUDED.tags,
    phones           = EXCLUDED.phones,
    website          = EXCLUDED.website,
    hours            = EXCLUDED.hours,
    floor            = EXCLUDED.floor,
    addr_street      = EXCLUDED.addr_street,
    addr_housenumber = EXCLUDED.addr_housenumber,
    addr_street_norm = EXCLUDED.addr_street_norm,
    geom             = EXCLUDED.geom
    -- address_id / building_id intentionally absent: link_buildings.sql owns them.
WHERE o.name             IS DISTINCT FROM EXCLUDED.name
   OR o.category_id      IS DISTINCT FROM EXCLUDED.category_id
   OR o.tags             IS DISTINCT FROM EXCLUDED.tags
   OR o.phones           IS DISTINCT FROM EXCLUDED.phones
   OR o.website          IS DISTINCT FROM EXCLUDED.website
   OR o.hours            IS DISTINCT FROM EXCLUDED.hours
   OR o.floor            IS DISTINCT FROM EXCLUDED.floor
   OR o.addr_street      IS DISTINCT FROM EXCLUDED.addr_street
   OR o.addr_housenumber IS DISTINCT FROM EXCLUDED.addr_housenumber
   OR o.addr_street_norm IS DISTINCT FROM EXCLUDED.addr_street_norm
   OR o.geom             IS DISTINCT FROM EXCLUDED.geom;

-- Prune source='osm' organizations only. source='editorial' rows come from the
-- stage 6 admin UI and are invisible to this import (ARCHITECTURE.md section 2,
-- principle 5).
DO $prune_org$
DECLARE
  n bigint;
  d bigint;
BEGIN
  SELECT count(*) INTO n FROM _osm_org;

  IF n = 0 THEN
    RAISE WARNING 'load_osm.sql: poi_src produced no usable rows - keeping the '
                  'existing source=''osm'' organizations instead of deleting them';
  ELSE
    DELETE FROM public.organization o
    WHERE o.source = 'osm'
      AND NOT EXISTS (SELECT 1 FROM _osm_org t WHERE t.source_id = o.source_id);
    GET DIAGNOSTICS d = ROW_COUNT;
    RAISE NOTICE 'load_osm.sql: organization(osm) - % accepted from staging, % stale row(s) deleted', n, d;
  END IF;
END
$prune_org$;


-- -----------------------------------------------------------------------------
-- 6. Report
-- -----------------------------------------------------------------------------
ANALYZE public.building;
ANALYZE public.address;
ANALYZE public.organization;

DO $report$
DECLARE
  v_bld      bigint;
  v_addr_osm bigint;
  v_org_osm  bigint;
  v_other    bigint;
  v_bnd      bigint;
BEGIN
  SELECT count(*) INTO v_bld FROM public.building;
  SELECT count(*) INTO v_addr_osm FROM public.address      WHERE source = 'osm';
  SELECT count(*) INTO v_org_osm  FROM public.organization WHERE source = 'osm';
  SELECT count(*) INTO v_bnd      FROM public.city_boundary WHERE osm_relation_id = 1649672;

  SELECT count(*) INTO v_other
  FROM public.organization o
  JOIN public.category c ON c.id = o.category_id
  WHERE o.source = 'osm' AND c.slug = 'other';

  RAISE NOTICE 'load_osm.sql done: building % [C1 >= 10000], address(osm) % [C2 >= 2000], organization(osm) % [C4 >= 1500]',
    v_bld, v_addr_osm, v_org_osm;
  RAISE NOTICE 'load_osm.sql: category `other` on % of % OSM organizations, city_boundary rows for relation 1649672: %',
    v_other, v_org_osm, v_bnd;
  RAISE NOTICE 'load_osm.sql: run infra/postgis/link_buildings.sql next - building_id/address_id are still unset.';
END
$report$;

COMMIT;
