-- =============================================================================
-- Zylos - Stage 2 - application schema (PostgreSQL 16 + PostGIS 3.5)
-- Docs/STAGE-02-postgis.md section 3.2 ("Схема") and section 7 ("Структура файлов").
--
-- Idempotent: safe to run any number of times against a live database.
--   psql -v ON_ERROR_STOP=1 -U zylos -d zylos -f infra/postgis/schema.sql
--
-- This file NEVER drops or truncates application tables. Re-running it only
-- creates what is missing. Data loading is the job of the import scripts.
--
-- IMPORT CONTRACT (read before writing import scripts):
--   * osm2pgsql (flex) MUST write into schema `osm_staging` only.
--     Never run osm2pgsql with --slim --drop or a default (public) schema:
--     it would drop the application tables defined below.
--     Recommended: osm2pgsql -O flex -S <style.lua> --schema=osm_staging ...
--   * ogr2ogr for RGZ MUST write into schema `rgz_staging` only, e.g.
--     ogr2ogr -f PostgreSQL PG:... -nln rgz_staging.kucni_broj -overwrite ...
--   * Staging schemas are disposable: import scripts may DROP/CREATE/TRUNCATE
--     anything inside them. `public` is the application contour and is off limits.
--   * Application tables are filled from staging with
--     INSERT ... ON CONFLICT (source, source_id) DO UPDATE   (address, organization)
--     INSERT ... ON CONFLICT (osm_type, osm_id) DO UPDATE    (building)
--     so that a second import does not duplicate rows (DoD R1).
--   * source = 'editorial' rows are written by the admin UI (stage 6) and MUST
--     NOT be touched by importers (ARCHITECTURE.md section 2, principle 5).
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 0. Extensions
-- -----------------------------------------------------------------------------
-- postgis   - geometry/geography types (already created by init.sql)
-- unaccent  - diacritics folding for street matching (link_buildings.sql)
-- pgcrypto  - gen_random_uuid() for surrogate primary keys
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS unaccent;
CREATE EXTENSION IF NOT EXISTS pgcrypto;


-- -----------------------------------------------------------------------------
-- 1. Staging schemas
-- -----------------------------------------------------------------------------
CREATE SCHEMA IF NOT EXISTS osm_staging;
CREATE SCHEMA IF NOT EXISTS rgz_staging;

COMMENT ON SCHEMA osm_staging IS
  'Disposable landing zone for osm2pgsql flex output. Import scripts may drop '
  'and recreate anything here. osm2pgsql must never target the public schema.';
COMMENT ON SCHEMA rgz_staging IS
  'Disposable landing zone for RGZ Adresni registar (ogr2ogr -nln '
  'rgz_staging.kucni_broj -overwrite). Truncate/drop freely, never in public.';

-- The application role owns the database in dev, but be explicit so that a
-- non-owner importer role can still use the staging schemas.
DO $grants$
DECLARE
  r text;
BEGIN
  FOREACH r IN ARRAY ARRAY['zylos', current_user] LOOP
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
      EXECUTE format('GRANT USAGE, CREATE ON SCHEMA osm_staging TO %I', r);
      EXECUTE format('GRANT USAGE, CREATE ON SCHEMA rgz_staging TO %I', r);
    END IF;
  END LOOP;
END
$grants$;


-- -----------------------------------------------------------------------------
-- 1b. Staging tables (INTERFACE CONTRACT with the import scripts)
-- -----------------------------------------------------------------------------
-- These are created here only so that load_osm.sql / load_rgz.sql can be parsed
-- and run before the first import, and so that the shape is documented in one
-- place. The importers OWN these tables:
--   * osm2pgsql -O flex --schema=osm_staging drops and recreates
--     building_src / address_src / poi_src with exactly these names and columns;
--   * ogr2ogr -nln osm_staging.city_boundary_src -overwrite (GeoJSON boundary)
--     and -nln rgz_staging.kucni_broj_src -overwrite (RGZ GPKG) do the same and
--     usually add their own columns (ogc_fid, fid, ...).
-- Therefore: the loaders reference these tables BY COLUMN NAME only and never
-- assume the column order, the exact type modifiers or the absence of extra
-- columns. city_boundary_src is consumed through `geom` alone.
-- Recommended ogr2ogr flags so `geom` really is called geom and is 4326:
--   -lco GEOMETRY_NAME=geom -nlt PROMOTE_TO_MULTI -t_srs EPSG:4326
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

CREATE TABLE IF NOT EXISTS rgz_staging.kucni_broj_src (
  rg_id          text,
  street         text,
  street_sr_cyrl text,
  housenumber    text,
  postcode       text,
  geom           geometry(Point, 4326)
);

-- Optional: the RGZ street register. May stay empty - kucni_broj_src already
-- carries the street name, so the loaders do not depend on this table.
CREATE TABLE IF NOT EXISTS rgz_staging.ulica_src (
  street_id      text,
  street         text,
  street_sr_cyrl text
);

COMMENT ON TABLE osm_staging.building_src IS
  'osm2pgsql flex output: building=* ways/relations. Recreated by every import.';
COMMENT ON TABLE osm_staging.address_src IS
  'osm2pgsql flex output: addr:housenumber nodes + ST_PointOnSurface of outlines.';
COMMENT ON TABLE osm_staging.poi_src IS
  'osm2pgsql flex output: named POI (amenity/shop/office/tourism/healthcare/craft/leisure).';
COMMENT ON TABLE osm_staging.city_boundary_src IS
  'ogr2ogr output of data/boundary/grad-novi-sad.geojson (relation 1649672). '
  'Extra ogr columns are ignored: only `geom` is read.';
COMMENT ON TABLE rgz_staging.kucni_broj_src IS
  'ogr2ogr output of the RGZ Adresni registar house numbers, clipped with the '
  'city polygon and reprojected to EPSG:4326.';
COMMENT ON TABLE rgz_staging.ulica_src IS
  'Optional RGZ street register. Loaders tolerate an empty table.';


-- -----------------------------------------------------------------------------
-- 2. Helper functions
-- -----------------------------------------------------------------------------

-- zylos_translit_sr(text)
-- Serbian Cyrillic (sr-Cyrl) -> plain ASCII Latin. RGZ ships street names in
-- Cyrillic, OSM mostly in Latin; without this step the two never compare equal.
-- Deterministic 1:1 table, no fuzzy rules.
CREATE OR REPLACE FUNCTION public.zylos_translit_sr(raw text)
RETURNS text
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
STRICT
AS $$
  SELECT translate(
           replace(replace(replace(replace(lower(raw),
             'љ', 'lj'), 'њ', 'nj'), 'џ', 'dz'), 'ђ', 'dj'),
           -- а б в г д е ж з и ј к л м н о п р с т у ф х ц ч ш ћ
           'абвгдежзијклмнопрстуфхцчшћ',
           'abvgdezzijklmnoprstufhccsc'
         );
$$;

COMMENT ON FUNCTION public.zylos_translit_sr(text) IS
  'Serbian Cyrillic to ASCII Latin transliteration used by zylos_normalize_street.';

-- zylos_normalize_street(text)
-- lower() + unaccent() + punctuation stripping + whitespace collapsing.
-- "Булевар ослобођења" and "Bulevar oslobođenja" both become "bulevar oslobodjenja".
-- Declared IMMUTABLE on purpose: unaccent() is only STABLE because it resolves
-- its dictionary through search_path; the dictionary is pinned to
-- public.unaccent here, so the result is in practice immutable. This lets the
-- function be used in functional indexes / generated columns if ever needed.
CREATE OR REPLACE FUNCTION public.zylos_normalize_street(raw text)
RETURNS text
LANGUAGE plpgsql
IMMUTABLE
PARALLEL SAFE
SET search_path = pg_catalog, public
AS $$
DECLARE
  s text;
BEGIN
  IF raw IS NULL THEN
    RETURN NULL;
  END IF;

  s := lower(btrim(raw));

  -- Serbian Latin digraph/diacritics first, so that they survive unaccent()
  -- in a predictable way (dj, not d).
  s := replace(s, 'đ', 'dj');
  s := translate(s, 'čćšž', 'ccsz');

  -- Serbian Cyrillic -> Latin
  s := public.zylos_translit_sr(s);

  -- everything else with diacritics (hu/de/... street names do occur)
  s := public.unaccent('public.unaccent'::regdictionary, s);

  -- punctuation, abbreviation dots, quotes -> space; collapse whitespace
  s := regexp_replace(s, '[^a-z0-9]+', ' ', 'g');
  s := btrim(regexp_replace(s, '\s+', ' ', 'g'));

  IF s = '' THEN
    RETURN NULL;
  END IF;

  RETURN s;
END
$$;

COMMENT ON FUNCTION public.zylos_normalize_street(text) IS
  'Normalized street key for conflation: lower + transliterate + unaccent + '
  'strip punctuation + collapse whitespace. Returns NULL for empty input.';

-- zylos_normalize_housenumber(text)
-- "12 A" / "12a" / "12/A." all collapse to "12a". Keeps separators that carry
-- meaning in Serbian addressing ("/" and "-").
CREATE OR REPLACE FUNCTION public.zylos_normalize_housenumber(raw text)
RETURNS text
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
  SELECT nullif(
           btrim(regexp_replace(lower(coalesce(raw, '')), '[^a-z0-9/-]+', '', 'g')),
           ''
         );
$$;

COMMENT ON FUNCTION public.zylos_normalize_housenumber(text) IS
  'Normalized house number key for conflation (lower, strip spaces and dots).';

DROP FUNCTION IF EXISTS public.navigator_translit_sr(text);
DROP FUNCTION IF EXISTS public.navigator_normalize_street(text);
DROP FUNCTION IF EXISTS public.navigator_normalize_housenumber(text);


-- -----------------------------------------------------------------------------
-- 3. city_boundary - exactly one polygon, OSM relation 1649672 (Grad Novi Sad)
-- -----------------------------------------------------------------------------
-- Single row by construction: the primary key is pinned to 1 and
-- osm_relation_id is unique, so both of these upserts keep exactly one row.
--   INSERT INTO city_boundary (id, osm_relation_id, geom)
--   VALUES (1, 1649672, ST_Multi(ST_SetSRID(<geojson geom>, 4326)))
--   ON CONFLICT (osm_relation_id) DO UPDATE
--     SET geom      = EXCLUDED.geom,
--         loaded_at = now();
-- Never TRUNCATE + INSERT inside the import: the verify script and stage 4+ API
-- read this row.
CREATE TABLE IF NOT EXISTS public.city_boundary (
  id              smallint    NOT NULL DEFAULT 1,
  osm_relation_id bigint      NOT NULL DEFAULT 1649672,
  name            text        NOT NULL DEFAULT 'Grad Novi Sad',
  geom            geometry(MultiPolygon, 4326) NOT NULL,
  loaded_at       timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT city_boundary_pkey       PRIMARY KEY (id),
  CONSTRAINT city_boundary_single_chk CHECK (id = 1),
  CONSTRAINT city_boundary_rel_uniq   UNIQUE (osm_relation_id)
);

COMMENT ON TABLE  public.city_boundary IS
  'One and only one row: the Grad Novi Sad polygon (OSM relation 1649672). '
  'Used for C10 (points outside the city) and for clipping in later stages.';
COMMENT ON COLUMN public.city_boundary.osm_relation_id IS
  'Must stay 1649672 (Grad Novi Sad = Novi Sad + Petrovaradin + Sremska Kamenica).';


-- -----------------------------------------------------------------------------
-- 4. category - OSM tag -> product category dictionary
-- -----------------------------------------------------------------------------
-- Wildcard convention (see seed_categories.sql):
--   osm_value = '*' means "any value of this key that has no explicit row",
--   osm_key = '*' AND osm_value = '*' is the `other` catch-all.
CREATE TABLE IF NOT EXISTS public.category (
  id        uuid NOT NULL DEFAULT gen_random_uuid(),
  slug      text NOT NULL,
  name_sr   text NOT NULL,
  name_ru   text NOT NULL,
  name_en   text NOT NULL,
  osm_key   text NOT NULL,
  osm_value text NOT NULL,
  icon      text NOT NULL,
  CONSTRAINT category_pkey        PRIMARY KEY (id),
  CONSTRAINT category_slug_uniq   UNIQUE (slug),
  CONSTRAINT category_osm_kv_uniq UNIQUE (osm_key, osm_value)
);

COMMENT ON TABLE  public.category IS
  'Category dictionary. One row per (osm_key, osm_value); slug is the stable '
  'business key used by API and Meilisearch.';
COMMENT ON COLUMN public.category.osm_value IS
  'Concrete OSM value, or ''*'' for the per-key catch-all (shop/office/healthcare/craft).';
COMMENT ON COLUMN public.category.icon IS 'maki-style icon name.';


-- -----------------------------------------------------------------------------
-- 5. building - OSM building outlines
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.building (
  id              uuid    NOT NULL DEFAULT gen_random_uuid(),
  osm_type        char(1) NOT NULL,
  osm_id          bigint  NOT NULL,
  geom            geometry(MultiPolygon, 4326) NOT NULL,
  name            text    NULL,
  building_levels int     NULL,
  height_m        numeric NULL,
  CONSTRAINT building_pkey         PRIMARY KEY (id),
  CONSTRAINT building_osm_type_chk CHECK (osm_type IN ('n', 'w', 'r')),
  CONSTRAINT building_osm_uniq     UNIQUE (osm_type, osm_id)
);

COMMENT ON TABLE  public.building IS
  'OSM building=* outlines (building=no excluded, area < 8 m2 excluded, '
  'ST_MakeValid applied on import). Always MultiPolygon/4326.';
COMMENT ON COLUMN public.building.osm_type IS 'n = node, w = way, r = relation.';
COMMENT ON COLUMN public.building.height_m IS 'OSM height tag in metres, numeric part only.';


-- -----------------------------------------------------------------------------
-- 6. address - OSM addr:* nodes/ways and RGZ kucni brojevi
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.address (
  id             uuid NOT NULL DEFAULT gen_random_uuid(),
  source         text NOT NULL,
  source_id      text NOT NULL,
  street         text NULL,
  housenumber    text NOT NULL,
  street_sr_cyrl text NULL,
  postcode       text NULL,
  rg_id          text NULL,
  street_norm    text NULL,
  geom           geometry(Point, 4326) NOT NULL,
  building_id    uuid NULL,
  CONSTRAINT address_pkey            PRIMARY KEY (id),
  CONSTRAINT address_source_chk      CHECK (source IN ('osm', 'rgz')),
  CONSTRAINT address_housenumber_chk CHECK (btrim(housenumber) <> ''),
  CONSTRAINT address_source_uniq     UNIQUE (source, source_id),
  CONSTRAINT address_building_fk     FOREIGN KEY (building_id)
    REFERENCES public.building (id) ON DELETE SET NULL
);

COMMENT ON TABLE  public.address IS
  'Addresses from two independent sources. OSM and RGZ duplicates of the same '
  'door are expected and allowed at stage 2 (see STAGE-02 section 6, rule 5).';
COMMENT ON COLUMN public.address.source_id IS
  'OSM: ''n123'' / ''w456'' / ''r789''. RGZ: jedinstveni adresni kod.';
COMMENT ON COLUMN public.address.rg_id IS 'RGZ jedinstveni adresni kod, NULL for OSM rows.';
COMMENT ON COLUMN public.address.street_norm IS
  'zylos_normalize_street(street). Maintained by link_buildings.sql; '
  'importers may prefill it, it is recomputed on every link run.';
COMMENT ON COLUMN public.address.building_id IS
  'Set by link_buildings.sql only. NULL is legitimate (no outline in OSM).';


-- -----------------------------------------------------------------------------
-- 7. organization - POI directory
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.organization (
  id                uuid   NOT NULL DEFAULT gen_random_uuid(),
  source            text   NOT NULL,
  source_id         text   NOT NULL,
  name              text   NOT NULL,
  category_id       uuid   NULL,
  tags              text[] NULL,
  phones            text[] NULL,
  website           text   NULL,
  hours             text   NULL,
  floor             text   NULL,
  addr_street       text   NULL,
  addr_housenumber  text   NULL,
  addr_street_norm  text   NULL,
  geom              geometry(Point, 4326) NOT NULL,
  address_id        uuid   NULL,
  building_id       uuid   NULL,
  CONSTRAINT organization_pkey        PRIMARY KEY (id),
  CONSTRAINT organization_source_chk  CHECK (source IN ('osm', 'editorial')),
  CONSTRAINT organization_name_chk    CHECK (btrim(name) <> ''),
  CONSTRAINT organization_source_uniq UNIQUE (source, source_id),
  CONSTRAINT organization_category_fk FOREIGN KEY (category_id)
    REFERENCES public.category (id),
  CONSTRAINT organization_address_fk  FOREIGN KEY (address_id)
    REFERENCES public.address (id) ON DELETE SET NULL,
  CONSTRAINT organization_building_fk FOREIGN KEY (building_id)
    REFERENCES public.building (id) ON DELETE SET NULL
);

COMMENT ON TABLE  public.organization IS
  'POI directory. Unnamed POI are not imported (DoD C9). geom is always a point '
  '(node coordinate or ST_PointOnSurface of the POI outline) and is never '
  'rewritten by the conflation step.';
COMMENT ON COLUMN public.organization.source_id IS 'OSM: ''n123'' / ''w456'' / ''r789''.';
COMMENT ON COLUMN public.organization.tags IS
  'Raw useful OSM tags as ''key=value'' strings (cuisine, brand, ...).';
COMMENT ON COLUMN public.organization.addr_street IS
  'Raw addr:street from OSM, kept for matching before/independently of address_id.';
COMMENT ON COLUMN public.organization.addr_street_norm IS
  'zylos_normalize_street(addr_street). Maintained by link_buildings.sql.';
COMMENT ON COLUMN public.organization.address_id IS 'Set by link_buildings.sql only.';
COMMENT ON COLUMN public.organization.building_id IS 'Set by link_buildings.sql only.';


-- -----------------------------------------------------------------------------
-- 8. stage02_poi_fixture - F4 regression anchors
-- -----------------------------------------------------------------------------
-- Stays empty until the first successful import. A human (or the import agent)
-- then picks 3 real POI in the centre - cafe / pharmacy / bank - and writes them
-- into infra/postgis/fixtures.sql as INSERTs into this table. Until it has >= 3
-- rows, scripts/stage02-verify.sql skips F4 with a NOTICE instead of failing.
CREATE TABLE IF NOT EXISTS public.stage02_poi_fixture (
  source_id text NOT NULL,
  kind      text NULL,
  note      text NULL,
  CONSTRAINT stage02_poi_fixture_pkey PRIMARY KEY (source_id)
);

COMMENT ON TABLE public.stage02_poi_fixture IS
  'F4 fixtures: organization.source_id values that must survive re-imports. '
  'Populated by infra/postgis/fixtures.sql after the first successful import.';


-- -----------------------------------------------------------------------------
-- 9. Constraint top-up for databases created by an older revision of this file
-- -----------------------------------------------------------------------------
-- PostgreSQL has no ADD CONSTRAINT IF NOT EXISTS; this loop is the equivalent.
DO $constraints$
DECLARE
  rec record;
BEGIN
  FOR rec IN
    SELECT *
    FROM (VALUES
      ('city_boundary', 'city_boundary_single_chk', 'CHECK (id = 1)'),
      ('city_boundary', 'city_boundary_rel_uniq',   'UNIQUE (osm_relation_id)'),
      ('category',      'category_slug_uniq',       'UNIQUE (slug)'),
      ('category',      'category_osm_kv_uniq',     'UNIQUE (osm_key, osm_value)'),
      ('building',      'building_osm_type_chk',    'CHECK (osm_type IN (''n'', ''w'', ''r''))'),
      ('building',      'building_osm_uniq',        'UNIQUE (osm_type, osm_id)'),
      ('address',       'address_source_chk',       'CHECK (source IN (''osm'', ''rgz''))'),
      ('address',       'address_housenumber_chk',  'CHECK (btrim(housenumber) <> '''')'),
      ('address',       'address_source_uniq',      'UNIQUE (source, source_id)'),
      ('address',       'address_building_fk',
         'FOREIGN KEY (building_id) REFERENCES public.building (id) ON DELETE SET NULL'),
      ('organization',  'organization_source_chk',  'CHECK (source IN (''osm'', ''editorial''))'),
      ('organization',  'organization_name_chk',    'CHECK (btrim(name) <> '''')'),
      ('organization',  'organization_source_uniq', 'UNIQUE (source, source_id)'),
      ('organization',  'organization_category_fk',
         'FOREIGN KEY (category_id) REFERENCES public.category (id)'),
      ('organization',  'organization_address_fk',
         'FOREIGN KEY (address_id) REFERENCES public.address (id) ON DELETE SET NULL'),
      ('organization',  'organization_building_fk',
         'FOREIGN KEY (building_id) REFERENCES public.building (id) ON DELETE SET NULL')
    ) AS v(tbl, conname, definition)
    WHERE NOT EXISTS (
      SELECT 1
      FROM pg_constraint c
      WHERE c.conrelid = ('public.' || v.tbl)::regclass
        AND c.conname  = v.conname
    )
  LOOP
    EXECUTE format('ALTER TABLE public.%I ADD CONSTRAINT %I %s',
                   rec.tbl, rec.conname, rec.definition);
    RAISE NOTICE 'schema.sql: added missing constraint %.%', rec.tbl, rec.conname;
  END LOOP;
END
$constraints$;

-- organization.category_id is nullable on purpose (STAGE-02 section 3.2 does not
-- require NOT NULL): an editorial row may be created before its category is
-- chosen. The OSM loader always resolves a category (worst case slug `other`).
-- DROP NOT NULL is a no-op when the column is already nullable, so this is
-- idempotent and also downgrades databases created by an older revision.
ALTER TABLE public.organization ALTER COLUMN category_id DROP NOT NULL;


-- -----------------------------------------------------------------------------
-- 10. Indexes
-- -----------------------------------------------------------------------------
-- Geometry GIST: ST_Contains / ST_Intersects / bbox queries (DoD S3).
CREATE INDEX IF NOT EXISTS city_boundary_geom_gix ON public.city_boundary USING GIST (geom);
CREATE INDEX IF NOT EXISTS building_geom_gix      ON public.building      USING GIST (geom);
CREATE INDEX IF NOT EXISTS address_geom_gix       ON public.address       USING GIST (geom);
CREATE INDEX IF NOT EXISTS organization_geom_gix  ON public.organization  USING GIST (geom);

-- Geography GIST on the same columns: ST_DWithin(..., <metres>) and KNN (<->)
-- in link_buildings.sql are geography operations and cannot use the geometry
-- index. The geometry::geography cast is immutable, so this is a plain
-- functional index.
CREATE INDEX IF NOT EXISTS building_geog_gix     ON public.building     USING GIST ((geom::geography));
CREATE INDEX IF NOT EXISTS address_geog_gix      ON public.address      USING GIST ((geom::geography));
CREATE INDEX IF NOT EXISTS organization_geog_gix ON public.organization USING GIST ((geom::geography));

-- Attribute indexes for conflation and for the stage 4 API.
CREATE INDEX IF NOT EXISTS address_building_idx      ON public.address      (building_id);
CREATE INDEX IF NOT EXISTS address_street_norm_idx   ON public.address      (street_norm);
CREATE INDEX IF NOT EXISTS address_source_idx        ON public.address      (source);
CREATE INDEX IF NOT EXISTS organization_building_idx ON public.organization (building_id);
CREATE INDEX IF NOT EXISTS organization_address_idx  ON public.organization (address_id);
CREATE INDEX IF NOT EXISTS organization_category_idx ON public.organization (category_id);
CREATE INDEX IF NOT EXISTS organization_addr_idx     ON public.organization (addr_street_norm, addr_housenumber);


-- -----------------------------------------------------------------------------
-- 11. Done
-- -----------------------------------------------------------------------------
DO $done$
BEGIN
  RAISE NOTICE 'schema.sql applied: city_boundary, category, building, address, organization, stage02_poi_fixture';
  RAISE NOTICE 'staging schemas ready: osm_staging (osm2pgsql flex), rgz_staging (ogr2ogr)';
END
$done$;
