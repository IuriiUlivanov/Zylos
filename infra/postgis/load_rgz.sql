-- =============================================================================
-- Zylos - Stage 2 - rgz_staging -> address (source = 'rgz')
-- Docs/STAGE-02-postgis.md section 6 ("Правила RGZ и conflation"), section 8, step 6.
--
--   psql -v ON_ERROR_STOP=1 -U zylos -d zylos -f infra/postgis/load_rgz.sql
--
-- Source: RGZ Adresni registar (kucni brojevi), downloaded as GPKG, clipped with
-- data/boundary/grad-novi-sad.geojson (relation 1649672 - Novi Sad AND
-- Petrovaradin, never filtered by "opstina = Novi Sad") and reprojected to
-- EPSG:4326 by the import script.
--
-- STAGING CONTRACT (columns read by name, extra ogr columns ignored):
--   rgz_staging.kucni_broj_src(rg_id, street, street_sr_cyrl, housenumber,
--                              postcode, geom)
--   rgz_staging.ulica_src(street_id, street, street_sr_cyrl)  -- optional/unused
--     kucni_broj_src already carries the street name, and it has no street_id
--     to join on, so ulica_src may stay empty. It is kept in the contract for
--     stage 3 (search synonyms), not for this loader.
--
-- This file only ever writes address rows with source = 'rgz'. OSM addresses
-- (source = 'osm') and every organization row are out of scope here.
-- building_id is not set: infra/postgis/link_buildings.sql owns it.
-- =============================================================================

BEGIN;

SET LOCAL synchronous_commit = off;


-- -----------------------------------------------------------------------------
-- 0. Staging tolerance
-- -----------------------------------------------------------------------------
-- ogr2ogr -overwrite recreates kucni_broj_src (and adds its own ogc_fid/fid
-- columns). Re-create it when missing so this file always parses and runs.
CREATE SCHEMA IF NOT EXISTS rgz_staging;

CREATE TABLE IF NOT EXISTS rgz_staging.kucni_broj_src (
  rg_id          text,
  street         text,
  street_sr_cyrl text,
  housenumber    text,
  postcode       text,
  geom           geometry(Point, 4326)
);

CREATE TABLE IF NOT EXISTS rgz_staging.ulica_src (
  street_id      text,
  street         text,
  street_sr_cyrl text
);


-- -----------------------------------------------------------------------------
-- 1. Guards
-- -----------------------------------------------------------------------------
-- Empty staging means the download/clip step failed (RGZ login wall, wrong GPKG
-- layer, clip in degrees instead of metres - see STAGE-02 section 10). Loading
-- from it would delete every existing RGZ address, so stop instead. C3 stays
-- red until the real register is imported; that is the intended behaviour.
DO $guard_empty$
DECLARE
  n_total  bigint;
  n_usable bigint;
BEGIN
  SELECT count(*),
         count(*) FILTER (WHERE geom IS NOT NULL
                            AND NOT ST_IsEmpty(geom)
                            AND housenumber IS NOT NULL
                            AND btrim(housenumber) <> '')
    INTO n_total, n_usable
  FROM rgz_staging.kucni_broj_src;

  IF n_total = 0 THEN
    RAISE EXCEPTION
      'load_rgz.sql: rgz_staging.kucni_broj_src is empty - the RGZ download or '
      'clip did not run. Refusing to load (that would delete the existing '
      'source=''rgz'' addresses). See STAGE-02 section 4 for the data.gov.rs source.';
  END IF;

  IF n_usable = 0 THEN
    RAISE EXCEPTION
      'load_rgz.sql: kucni_broj_src holds % row(s) but none of them has both a '
      'house number and a geometry - wrong GPKG layer or wrong column mapping.',
      n_total;
  END IF;

  RAISE NOTICE 'load_rgz.sql: staging rows % (% usable), ulica_src %',
    n_total, n_usable, (SELECT count(*) FROM rgz_staging.ulica_src);
END
$guard_empty$;

-- SRID assertion. The import script is expected to hand over 4326 already
-- (RGZ ships EPSG:32634 / 8682, ogr2ogr -t_srs EPSG:4326 converts it). SRID 0
-- cannot be transformed - fail loudly rather than store meaningless
-- coordinates that would make every metre-based conflation check nonsense.
DO $guard_srid$
DECLARE
  srids int[];
BEGIN
  SELECT array_agg(DISTINCT ST_SRID(geom) ORDER BY ST_SRID(geom))
    INTO srids
  FROM rgz_staging.kucni_broj_src
  WHERE geom IS NOT NULL;

  IF srids IS NULL THEN
    RAISE EXCEPTION 'load_rgz.sql: no geometries in rgz_staging.kucni_broj_src';
  END IF;

  IF 0 = ANY (srids) THEN
    RAISE EXCEPTION
      'load_rgz.sql: kucni_broj_src contains SRID 0 geometries (SRID set: %). '
      'Re-run ogr2ogr with -a_srs <real source CRS, check with ogrinfo> '
      '-t_srs EPSG:4326.', srids;
  END IF;

  IF srids <> ARRAY[4326] THEN
    RAISE WARNING 'load_rgz.sql: kucni_broj_src SRID set is % - ST_Transform to '
                  '4326 will be applied, but the import should already deliver 4326',
      srids;
  END IF;
END
$guard_srid$;


-- -----------------------------------------------------------------------------
-- 2. Accepted set
-- -----------------------------------------------------------------------------
-- source_id is the RGZ "jedinstveni adresni kod" (rg_id). Some exports have
-- gaps in that column; those rows get a deterministic md5 surrogate built from
-- street + house number + rounded coordinates, so a re-import of the same file
-- produces the same source_id and upserts instead of duplicating.
DROP TABLE IF EXISTS _rgz_address;
CREATE TEMP TABLE _rgz_address ON COMMIT DROP AS
SELECT DISTINCT ON (source_id) *
FROM (
  SELECT coalesce(
           nullif(btrim(s.rg_id), ''),
           'md5:' || md5(coalesce(nullif(btrim(s.street), ''), '')
                         || '|' || btrim(s.housenumber)
                         || '|' || round(ST_X(g.geom)::numeric, 7)::text
                         || '|' || round(ST_Y(g.geom)::numeric, 7)::text)
         )                                     AS source_id,
         nullif(btrim(s.rg_id),          '')   AS rg_id,
         nullif(btrim(s.street),         '')   AS street,
         btrim(s.housenumber)                  AS housenumber,
         nullif(btrim(s.street_sr_cyrl), '')   AS street_sr_cyrl,
         nullif(btrim(s.postcode),       '')   AS postcode,
         g.geom
  FROM rgz_staging.kucni_broj_src s
  CROSS JOIN LATERAL (
    SELECT ST_Force2D(CASE WHEN ST_SRID(s.geom) = 4326 THEN s.geom
                           ELSE ST_Transform(s.geom, 4326) END) AS geom
  ) g
  WHERE s.housenumber IS NOT NULL
    AND btrim(s.housenumber) <> ''
    AND s.geom        IS NOT NULL
    AND NOT ST_IsEmpty(s.geom)
) x
-- Duplicate rg_id in one export: keep the most complete row.
ORDER BY source_id, (street IS NOT NULL) DESC, (postcode IS NOT NULL) DESC;

CREATE UNIQUE INDEX _rgz_address_key ON _rgz_address (source_id);
ANALYZE _rgz_address;


-- -----------------------------------------------------------------------------
-- 3. Upsert
-- -----------------------------------------------------------------------------
-- RGZ and OSM may describe the same door; both rows are kept on purpose
-- (STAGE-02 section 6, rule 5). Canonicalisation is a stage 3-5 concern.
INSERT INTO public.address AS a
  (source, source_id, street, housenumber, street_sr_cyrl, postcode, rg_id, street_norm, geom)
SELECT 'rgz',
       source_id,
       -- RGZ street names arrive in Cyrillic; keep whatever the export gives in
       -- `street` and mirror it into street_sr_cyrl when only one is present.
       coalesce(street, street_sr_cyrl),
       housenumber,
       street_sr_cyrl,
       postcode,
       rg_id,
       public.zylos_normalize_street(coalesce(street, street_sr_cyrl)),
       geom
FROM _rgz_address
ON CONFLICT (source, source_id) DO UPDATE
SET street         = EXCLUDED.street,
    housenumber    = EXCLUDED.housenumber,
    street_sr_cyrl = EXCLUDED.street_sr_cyrl,
    postcode       = EXCLUDED.postcode,
    rg_id          = EXCLUDED.rg_id,
    street_norm    = EXCLUDED.street_norm,
    geom           = EXCLUDED.geom
    -- building_id intentionally absent: link_buildings.sql owns it.
WHERE a.street         IS DISTINCT FROM EXCLUDED.street
   OR a.housenumber    IS DISTINCT FROM EXCLUDED.housenumber
   OR a.street_sr_cyrl IS DISTINCT FROM EXCLUDED.street_sr_cyrl
   OR a.postcode       IS DISTINCT FROM EXCLUDED.postcode
   OR a.rg_id          IS DISTINCT FROM EXCLUDED.rg_id
   OR a.street_norm    IS DISTINCT FROM EXCLUDED.street_norm
   OR a.geom           IS DISTINCT FROM EXCLUDED.geom;


-- -----------------------------------------------------------------------------
-- 4. Prune + report
-- -----------------------------------------------------------------------------
-- Only source='rgz' rows, and only after the guard proved staging is populated.
DO $prune$
DECLARE
  n bigint;
  d bigint;
BEGIN
  SELECT count(*) INTO n FROM _rgz_address;

  DELETE FROM public.address a
  WHERE a.source = 'rgz'
    AND NOT EXISTS (SELECT 1 FROM _rgz_address t WHERE t.source_id = a.source_id);
  GET DIAGNOSTICS d = ROW_COUNT;

  RAISE NOTICE 'load_rgz.sql: address(rgz) - % accepted from staging, % stale row(s) deleted', n, d;
END
$prune$;

ANALYZE public.address;

DO $report$
DECLARE
  v_rgz      bigint;
  v_surrogate bigint;
BEGIN
  SELECT count(*), count(*) FILTER (WHERE rg_id IS NULL)
    INTO v_rgz, v_surrogate
  FROM public.address WHERE source = 'rgz';

  RAISE NOTICE 'load_rgz.sql done: address(rgz) % [C3 >= 20000], % of them on an md5 surrogate id',
    v_rgz, v_surrogate;
  RAISE NOTICE 'load_rgz.sql: run infra/postgis/link_buildings.sql next - building_id is still unset.';
END
$report$;

COMMIT;
