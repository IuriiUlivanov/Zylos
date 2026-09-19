-- =============================================================================
-- Zylos - Stage 2 - acceptance checks
-- Docs/STAGE-02-postgis.md sections 3.2, 3.3, 3.4 and 9.
--
--   psql -v ON_ERROR_STOP=1 -U zylos -d zylos -f scripts/stage02-verify.sql
--
-- Exit code 0    -> stage 2 Definition of Done is met.
-- Exit code != 0 -> at least one threshold failed; the exception message lists
--                   every failed check, and the NOTICE table printed just above
--                   it holds all measured values (paste into data/README.md).
--                   psql reports SQL errors under ON_ERROR_STOP as exit code 3,
--                   so pipeline wrappers must test for non-zero, not for 1.
--
-- The script is read only. It never writes to application tables.
--
-- Checks are collected instead of aborting on the first failure: a single run
-- has to tell the operator everything that is wrong, not just the first thing.
-- Any failure still ends in RAISE EXCEPTION, so ON_ERROR_STOP gives exit 1.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- Preflight (S1): without these objects nothing else can be measured.
-- -----------------------------------------------------------------------------
DO $preflight$
DECLARE
  missing text[];
  t       text;
BEGIN
  FOREACH t IN ARRAY ARRAY['public.city_boundary', 'public.category', 'public.building',
                           'public.address', 'public.organization',
                           'public.stage02_poi_fixture'] LOOP
    IF to_regclass(t) IS NULL THEN
      missing := missing || t;
    END IF;
  END LOOP;

  IF to_regprocedure('public.zylos_normalize_street(text)') IS NULL THEN
    missing := missing || 'function public.zylos_normalize_street(text)'::text;
  END IF;

  IF missing IS NOT NULL THEN
    RAISE EXCEPTION 'S1 FAILED: missing objects: %. Apply infra/postgis/schema.sql first.',
      array_to_string(missing, ', ');
  END IF;
END
$preflight$;


-- -----------------------------------------------------------------------------
-- Main verification
-- -----------------------------------------------------------------------------
DO $verify$
DECLARE
  -- fixture probes (WGS84 lon/lat)
  p_center   geometry := ST_SetSRID(ST_MakePoint(19.845, 45.255), 4326);  -- Trg slobode
  p_petrovar geometry := ST_SetSRID(ST_MakePoint(19.862, 45.252), 4326);  -- Petrovaradin
  p_liman    geometry := ST_SetSRID(ST_MakePoint(19.840, 45.238), 4326);  -- Liman

  v_boundary geometry;

  -- schema metrics
  v_srid_building  int;
  v_srid_address   int;
  v_srid_org       int;
  v_srid_boundary  int;
  v_gist_building  boolean;
  v_gist_address   boolean;
  v_gist_org       boolean;
  v_geog_idx       int;
  v_uc_building    boolean;
  v_uc_address     boolean;
  v_uc_org         boolean;
  v_uc_cat_slug    boolean;
  v_uc_cat_kv      boolean;
  v_categories     bigint;
  v_slugs_missing  text;
  v_boundary_rows  bigint;
  v_boundary_rel   bigint;

  -- data metrics (C1..C10)
  v_buildings      bigint;
  v_addr_osm       bigint;
  v_addr_rgz       bigint;
  v_org_osm        bigint;
  v_org_total      bigint;
  v_rgz_linked     bigint;
  v_rgz_pct        numeric;
  v_org_linked     bigint;
  v_org_pct        numeric;
  v_bld_with_org   bigint;
  v_invalid_bld    bigint;
  v_org_noname     bigint;
  v_org_outside    bigint;
  v_addr_outside   bigint;
  v_other_pct      numeric;

  -- fixtures F1..F6
  v_f1_bld         bigint;
  v_f1_org         bigint;
  v_f2_bld         bigint;
  v_f3_bld         bigint;
  v_f5_addr        bigint;
  v_f6_bld         bigint;
  v_fx_rows        bigint;
  v_fx_missing     bigint;

  failures         text[] := ARRAY[]::text[];
  rec              record;
BEGIN
  -- ===========================================================================
  -- Collect
  -- ===========================================================================

  -- S2: SRID of every geometry column must be 4326
  v_srid_building := Find_SRID('public', 'building',      'geom');
  v_srid_address  := Find_SRID('public', 'address',       'geom');
  v_srid_org      := Find_SRID('public', 'organization',  'geom');
  v_srid_boundary := Find_SRID('public', 'city_boundary', 'geom');

  -- S3: GIST on every geometry column
  SELECT EXISTS (
    SELECT 1 FROM pg_index i
    JOIN pg_class c ON c.oid = i.indexrelid
    JOIN pg_am    am ON am.oid = c.relam
    WHERE i.indrelid = 'public.building'::regclass
      AND am.amname = 'gist'
      AND pg_get_indexdef(i.indexrelid) ~ 'USING gist \(geom\)'
  ) INTO v_gist_building;

  SELECT EXISTS (
    SELECT 1 FROM pg_index i
    JOIN pg_class c ON c.oid = i.indexrelid
    JOIN pg_am    am ON am.oid = c.relam
    WHERE i.indrelid = 'public.address'::regclass
      AND am.amname = 'gist'
      AND pg_get_indexdef(i.indexrelid) ~ 'USING gist \(geom\)'
  ) INTO v_gist_address;

  SELECT EXISTS (
    SELECT 1 FROM pg_index i
    JOIN pg_class c ON c.oid = i.indexrelid
    JOIN pg_am    am ON am.oid = c.relam
    WHERE i.indrelid = 'public.organization'::regclass
      AND am.amname = 'gist'
      AND pg_get_indexdef(i.indexrelid) ~ 'USING gist \(geom\)'
  ) INTO v_gist_org;

  -- advisory only: the geography functional indexes used by link_buildings.sql
  SELECT count(*) INTO v_geog_idx
  FROM pg_index i
  JOIN pg_class c ON c.oid = i.indexrelid
  JOIN pg_am    am ON am.oid = c.relam
  WHERE i.indrelid IN ('public.building'::regclass,
                       'public.address'::regclass,
                       'public.organization'::regclass)
    AND am.amname = 'gist'
    AND pg_get_indexdef(i.indexrelid) ~ 'geography';

  -- S3: unique constraints, checked by column set (not by constraint name)
  SELECT EXISTS (
    SELECT 1 FROM pg_constraint c
    WHERE c.conrelid = 'public.building'::regclass AND c.contype = 'u'
      AND (SELECT array_agg(a.attname::text ORDER BY a.attname)
             FROM unnest(c.conkey) k
             JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k)
          = ARRAY['osm_id', 'osm_type']
  ) INTO v_uc_building;

  SELECT EXISTS (
    SELECT 1 FROM pg_constraint c
    WHERE c.conrelid = 'public.address'::regclass AND c.contype = 'u'
      AND (SELECT array_agg(a.attname::text ORDER BY a.attname)
             FROM unnest(c.conkey) k
             JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k)
          = ARRAY['source', 'source_id']
  ) INTO v_uc_address;

  SELECT EXISTS (
    SELECT 1 FROM pg_constraint c
    WHERE c.conrelid = 'public.organization'::regclass AND c.contype = 'u'
      AND (SELECT array_agg(a.attname::text ORDER BY a.attname)
             FROM unnest(c.conkey) k
             JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k)
          = ARRAY['source', 'source_id']
  ) INTO v_uc_org;

  SELECT EXISTS (
    SELECT 1 FROM pg_constraint c
    WHERE c.conrelid = 'public.category'::regclass AND c.contype = 'u'
      AND (SELECT array_agg(a.attname::text ORDER BY a.attname)
             FROM unnest(c.conkey) k
             JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k)
          = ARRAY['slug']
  ) INTO v_uc_cat_slug;

  SELECT EXISTS (
    SELECT 1 FROM pg_constraint c
    WHERE c.conrelid = 'public.category'::regclass AND c.contype = 'u'
      AND (SELECT array_agg(a.attname::text ORDER BY a.attname)
             FROM unnest(c.conkey) k
             JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k)
          = ARRAY['osm_key', 'osm_value']
  ) INTO v_uc_cat_kv;

  -- S4: dictionary
  SELECT count(*) INTO v_categories FROM public.category;

  SELECT coalesce(string_agg(s, ', ' ORDER BY s), '-')
    INTO v_slugs_missing
  FROM unnest(ARRAY['cafe', 'restaurant', 'pharmacy', 'bank', 'school',
                    'hospital', 'shop', 'other']) AS s
  WHERE NOT EXISTS (SELECT 1 FROM public.category c WHERE c.slug = s);

  -- city boundary (needed for C10)
  SELECT count(*), max(osm_relation_id) INTO v_boundary_rows, v_boundary_rel
  FROM public.city_boundary;
  SELECT geom INTO v_boundary FROM public.city_boundary ORDER BY id LIMIT 1;

  -- C1..C4 volumes
  SELECT count(*) INTO v_buildings FROM public.building;

  SELECT count(*) FILTER (WHERE source = 'osm'),
         count(*) FILTER (WHERE source = 'rgz'),
         count(*) FILTER (WHERE source = 'rgz' AND building_id IS NOT NULL)
    INTO v_addr_osm, v_addr_rgz, v_rgz_linked
  FROM public.address;

  SELECT count(*),
         count(*) FILTER (WHERE source = 'osm'),
         count(*) FILTER (WHERE building_id IS NOT NULL),
         count(DISTINCT building_id),
         count(*) FILTER (WHERE name IS NULL OR btrim(name) = '')
    INTO v_org_total, v_org_osm, v_org_linked, v_bld_with_org, v_org_noname
  FROM public.organization;

  -- C5 / C6 link rates
  v_rgz_pct := coalesce(round(100.0 * v_rgz_linked  / nullif(v_addr_rgz,  0), 2), 0);
  v_org_pct := coalesce(round(100.0 * v_org_linked  / nullif(v_org_total, 0), 2), 0);

  -- C8 geometry validity
  SELECT count(*) INTO v_invalid_bld
  FROM public.building WHERE NOT ST_IsValid(geom);

  -- C10 points outside the city (5 m tolerance).
  -- ST_Intersects first (prepared-geometry cached, cheap for the ~99% inside),
  -- the metric ST_DWithin only runs for the leftovers.
  IF v_boundary IS NULL THEN
    v_addr_outside := NULL;
    v_org_outside  := NULL;
  ELSE
    SELECT count(*) INTO v_addr_outside
    FROM public.address a
    WHERE NOT ST_Intersects(v_boundary, a.geom)
      AND NOT ST_DWithin(v_boundary::geography, a.geom::geography, 5.0);

    SELECT count(*) INTO v_org_outside
    FROM public.organization o
    WHERE NOT ST_Intersects(v_boundary, o.geom)
      AND NOT ST_DWithin(v_boundary::geography, o.geom::geography, 5.0);
  END IF;

  -- category `other` share
  SELECT coalesce(round(100.0 * count(*) FILTER (WHERE c.slug = 'other')
                        / nullif(count(*), 0), 2), 0)
    INTO v_other_pct
  FROM public.organization o
  JOIN public.category c ON c.id = o.category_id;

  -- F1 Trg slobode, 80 m
  SELECT count(*) INTO v_f1_bld
  FROM public.building b
  WHERE ST_DWithin(b.geom::geography, p_center::geography, 80.0);

  SELECT count(*) INTO v_f1_org
  FROM public.organization o
  WHERE ST_DWithin(o.geom::geography, p_center::geography, 80.0);

  -- F2 Petrovaradin, 200 m (guards against the wrong relation being extracted)
  SELECT count(*) INTO v_f2_bld
  FROM public.building b
  WHERE ST_DWithin(b.geom::geography, p_petrovar::geography, 200.0);

  -- F3 Liman, 200 m
  SELECT count(*) INTO v_f3_bld
  FROM public.building b
  WHERE ST_DWithin(b.geom::geography, p_liman::geography, 200.0);

  -- F5 a real numbered address, inside a building, in the centre
  SELECT count(*) INTO v_f5_addr
  FROM public.address a
  WHERE a.housenumber ~ '^[0-9]+'
    AND btrim(coalesce(a.street, '')) <> ''
    AND a.building_id IS NOT NULL
    AND ST_DWithin(a.geom::geography, p_center::geography, 2000.0);

  -- F6 at least one building hosting >= 2 organizations
  SELECT count(*) INTO v_f6_bld
  FROM (
    SELECT building_id
    FROM public.organization
    WHERE building_id IS NOT NULL
    GROUP BY building_id
    HAVING count(*) >= 2
  ) x;

  -- F4 optional: only enforced once infra/postgis/fixtures.sql has been written
  SELECT count(*) INTO v_fx_rows FROM public.stage02_poi_fixture;
  IF v_fx_rows >= 3 THEN
    SELECT count(*) INTO v_fx_missing
    FROM public.stage02_poi_fixture f
    WHERE NOT EXISTS (
      SELECT 1 FROM public.organization o
      WHERE o.source = 'osm' AND o.source_id = f.source_id
    );
  ELSE
    v_fx_missing := NULL;
  END IF;

  -- ===========================================================================
  -- Score
  -- ===========================================================================
  -- A NULL `pass` (e.g. a metric that could not be measured because
  -- city_boundary is empty) counts as a failure, never as a pass.
  SET LOCAL client_min_messages = warning;
  DROP TABLE IF EXISTS _stage02_metrics;
  SET LOCAL client_min_messages = notice;

  CREATE TEMP TABLE _stage02_metrics (
    ord    int,
    code   text,
    metric text,
    val    text,
    req    text,
    pass   boolean
  ) ON COMMIT DROP;

  INSERT INTO _stage02_metrics (ord, code, metric, val, req, pass) VALUES
    ( 1, 'S2', 'SRID building.geom',              v_srid_building::text, '= 4326', v_srid_building = 4326),
    ( 2, 'S2', 'SRID address.geom',               v_srid_address::text,  '= 4326', v_srid_address  = 4326),
    ( 3, 'S2', 'SRID organization.geom',          v_srid_org::text,      '= 4326', v_srid_org      = 4326),
    ( 4, 'S2', 'SRID city_boundary.geom',         v_srid_boundary::text, '= 4326', v_srid_boundary = 4326),
    ( 5, 'S3', 'GIST building.geom',              v_gist_building::text, 'exists', v_gist_building),
    ( 6, 'S3', 'GIST address.geom',               v_gist_address::text,  'exists', v_gist_address),
    ( 7, 'S3', 'GIST organization.geom',          v_gist_org::text,      'exists', v_gist_org),
    ( 8, 'S3', 'UNIQUE building(osm_type,osm_id)',v_uc_building::text,   'exists', v_uc_building),
    ( 9, 'S3', 'UNIQUE address(source,source_id)',v_uc_address::text,    'exists', v_uc_address),
    (10, 'S3', 'UNIQUE organization(source,source_id)', v_uc_org::text,  'exists', v_uc_org),
    (11, 'S3', 'UNIQUE category(slug)',           v_uc_cat_slug::text,   'exists', v_uc_cat_slug),
    (12, 'S3', 'UNIQUE category(osm_key,osm_value)', v_uc_cat_kv::text,  'exists', v_uc_cat_kv),
    (13, 'S4', 'category rows',                   v_categories::text,    '>= 30',  v_categories >= 30),
    (14, 'S4', 'required slugs missing',          v_slugs_missing,       'none',   v_slugs_missing = '-'),
    (15, 'P',  'city_boundary rows',              v_boundary_rows::text, '= 1',    v_boundary_rows = 1),
    (16, 'P',  'city_boundary osm_relation_id',   coalesce(v_boundary_rel::text, 'null'),
                                                                         '= 1649672', v_boundary_rel = 1649672),
    (17, 'C1', 'building',                        v_buildings::text,     '>= 10000', v_buildings >= 10000),
    (18, 'C2', 'address source=osm',              v_addr_osm::text,      '>= 2000',  v_addr_osm  >= 2000),
    (19, 'C3', 'address source=rgz',              v_addr_rgz::text,      '>= 20000', v_addr_rgz  >= 20000),
    (20, 'C4', 'organization source=osm',         v_org_osm::text,       '>= 1500',  v_org_osm   >= 1500),
    (21, 'C5', 'rgz addresses linked to building, %', v_rgz_pct::text,   '>= 50',    v_rgz_pct   >= 50),
    (22, 'C6', 'organizations linked to building, %', v_org_pct::text,   '>= 40',    v_org_pct   >= 40),
    (23, 'C7', 'buildings with >= 1 organization', v_bld_with_org::text, '>= 300',   v_bld_with_org >= 300),
    (24, 'C8', 'invalid building geometry',       v_invalid_bld::text,   '= 0',      v_invalid_bld = 0),
    (25, 'C9', 'organizations without name',      v_org_noname::text,    '= 0',      v_org_noname = 0),
    (26, 'C10','organizations outside city (5 m)', coalesce(v_org_outside::text, 'n/a'),
                                                                         '= 0',      v_org_outside = 0),
    (27, 'C10','addresses outside city (5 m)',    coalesce(v_addr_outside::text, 'n/a'),
                                                                         '= 0',      v_addr_outside = 0),
    (28, 'Q1', 'category `other` share, %',       v_other_pct::text,     '< 35',     v_other_pct < 35),
    (29, 'F1', 'buildings within 80 m of Trg slobode',   v_f1_bld::text, '>= 5',     v_f1_bld >= 5),
    (30, 'F1', 'organizations within 80 m of Trg slobode', v_f1_org::text, '>= 1',   v_f1_org >= 1),
    (31, 'F2', 'buildings within 200 m of Petrovaradin', v_f2_bld::text, '>= 1',     v_f2_bld >= 1),
    (32, 'F3', 'buildings within 200 m of Liman',        v_f3_bld::text, '>= 20',    v_f3_bld >= 20),
    (33, 'F5', 'numbered addresses in a building (centre)', v_f5_addr::text, '>= 1', v_f5_addr >= 1),
    (34, 'F6', 'buildings with >= 2 organizations',      v_f6_bld::text, '>= 1',     v_f6_bld >= 1);

  IF v_fx_rows >= 3 THEN
    INSERT INTO _stage02_metrics VALUES
      (35, 'F4', 'fixture POI not found after import', v_fx_missing::text, '= 0', v_fx_missing = 0);
  ELSE
    INSERT INTO _stage02_metrics VALUES
      (35, 'F4', 'fixture POI re-import check', v_fx_rows || ' fixtures',
           'skipped until 3 rows', TRUE);
  END IF;

  -- ===========================================================================
  -- Report
  -- ===========================================================================
  RAISE NOTICE '';
  RAISE NOTICE '### Stage 2 metrics (%)', to_char(now(), 'YYYY-MM-DD HH24:MI:SS TZ');
  RAISE NOTICE '';
  RAISE NOTICE '| # | Metric | Value | Required | Status |';
  RAISE NOTICE '|---|---|---:|---|---|';

  FOR rec IN SELECT ord, code, metric, val, req, coalesce(pass, FALSE) AS pass
             FROM _stage02_metrics ORDER BY ord LOOP
    RAISE NOTICE '| % | % | % | % | % |',
      rec.code, rec.metric, coalesce(rec.val, 'null'), rec.req,
      CASE WHEN rec.pass THEN 'OK' ELSE 'FAIL' END;

    IF NOT rec.pass THEN
      failures := failures || format('%s %s: got %s, required %s',
                                     rec.code, rec.metric, coalesce(rec.val, 'null'), rec.req);
    END IF;
  END LOOP;

  RAISE NOTICE '';
  RAISE NOTICE 'address total: %, organizations total: %, rgz linked: %, org linked: %',
    v_addr_osm + v_addr_rgz, v_org_total, v_rgz_linked, v_org_linked;

  IF v_fx_rows < 3 THEN
    RAISE NOTICE 'F4 skipped: fixtures not loaded (stage02_poi_fixture has % of 3 rows). '
                 'Pick 3 central POI after the first import and commit '
                 'infra/postgis/fixtures.sql.', v_fx_rows;
  END IF;

  IF v_geog_idx < 3 THEN
    RAISE NOTICE 'hint: only % of 3 geography GIST indexes present; link_buildings.sql '
                 'will fall back to sequential scans.', v_geog_idx;
  END IF;

  RAISE NOTICE '';

  -- ===========================================================================
  -- Verdict
  -- ===========================================================================
  IF array_length(failures, 1) > 0 THEN
    RAISE EXCEPTION E'stage 2 verify FAILED, % check(s) below threshold:\n  - %',
      array_length(failures, 1),
      array_to_string(failures, E'\n  - ');
  END IF;

  RAISE NOTICE 'stage 2 verify PASSED: all schema, volume, quality and fixture checks are green.';
END
$verify$;
