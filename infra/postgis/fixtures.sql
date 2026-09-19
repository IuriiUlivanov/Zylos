-- Stage 2 fixture POI (criterion F4).
-- Three real OSM objects near Trg slobode, picked after the first successful
-- import. A re-import must find the same source_id values in organization.
--
-- Apply after load_osm.sql; scripts/stage02-verify.sql enforces F4 once this
-- table holds at least three rows.

INSERT INTO public.stage02_poi_fixture (source_id, kind) VALUES
  ('n871146541',   'bank'),      -- OTP banka, ~42 m from Trg slobode
  ('n11478763570', 'cafe'),      -- Кафетерија, ~70 m from Trg slobode
  ('n11849684670', 'pharmacy')   -- Laurus, ~66 m from Trg slobode
ON CONFLICT (source_id) DO UPDATE SET kind = EXCLUDED.kind;

-- Stage 5 F6: Big Fashion (66 organizations), recorded after the first green import.
CREATE TABLE IF NOT EXISTS public.stage05_building_fixture (
  building_id uuid PRIMARY KEY,
  lon double precision NOT NULL,
  lat double precision NOT NULL,
  org_count int NOT NULL,
  note text
);

INSERT INTO public.stage05_building_fixture (building_id, lon, lat, org_count, note)
VALUES (
  '9e208165-8b3f-4277-bb92-9ade9c49121f',
  19.843486795879464,
  45.24576475,
  66,
  'Big Fashion'
)
ON CONFLICT (building_id) DO UPDATE
  SET lon = EXCLUDED.lon,
      lat = EXCLUDED.lat,
      org_count = EXCLUDED.org_count,
      note = EXCLUDED.note;
