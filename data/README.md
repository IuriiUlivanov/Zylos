# Extract Novi Sad

- OSM source: https://download.geofabrik.de/europe/serbia-latest.osm.pbf
- Downloaded at: 2026-09-16T21:12:35Z
- Boundary: OSM relation 1649672 (Grad Novi Sad)
- License: ODbL, © OpenStreetMap contributors
- serbia-latest.osm.pbf: 239845389 bytes
- novi-sad.osm.pbf: 7252506 bytes
- novi-sad.pmtiles: 4875047 bytes
- osmium fileinfo bbox: (19.1403875,44.6841882,20.5401075,45.5345132)
- OSM entities: nodes=697201, ways=120955, relations=2289
- planetiler maxzoom: 14
- novi-sad.mbtiles: 5169152 bytes (Android offline, zoom 0–14)

## Stage 14 GTFS / OTP

- Unpack the official JGSP Novi Sad GTFS zip into `data/gtfs/jgsp/` (not in git).
- Required: `agency.txt`, `routes.txt`, `stops.txt`, `stop_times.txt`, `trips.txt`, `calendar.txt`.
- OTP graph is built on first `docker compose up otp` (≤ 90 s, R6) and stored in the `otp_graph` volume.

## Stage 2 PostGIS

- OSM license: ODbL, © OpenStreetMap contributors
- RGZ license: open data from [data.gov.rs Adresni registar](https://data.gov.rs/sr/datasets/adresni-registar/) / GeoSrbija
- RGZ downloaded at: 2026-09-16T22:38:00Z
- RGZ layer: `kucni_broj` (EPSG:25834 -> EPSG:4326, clipped to relation 1649672)

| # | Metric | Value | Required | Status |
|---|--------|------:|----------|--------|
| C1 | building | 82617 | >= 10000 | OK |
| C2 | address source=osm | 70095 | >= 2000 | OK |
| C3 | address source=rgz | 67900 | >= 20000 | OK |
| C4 | organization source=osm | 3709 | >= 1500 | OK |
| C5 | rgz addresses linked to building, % | 88.11 | >= 50 | OK |
| C6 | organizations linked to building, % | 97.79 | >= 40 | OK |
| C7 | buildings with >= 1 organization | 2352 | >= 300 | OK |
| C8 | invalid building geometry | 0 | = 0 | OK |
| C9 | organizations without name | 0 | = 0 | OK |
| C10 | points outside city (5 m) | 0 | = 0 | OK |

- Share of RGZ addresses linked to buildings (C5): 88.11
- F4 fixtures: `n871146541` OTP banka (bank), `n11478763570` Кафетерија (cafe), `n11849684670` Laurus (pharmacy)

## Stage 3 Meilisearch

- Index: `zylos`
- Indexed at: 2026-09-17T23:29:06.983Z
- Address documents: 137993
- Organization documents: 3709
- p95 GET /v1/search `q=apotek`: 49.0 ms (S1, n=30, warmup 5)
- p95 short/empty `q`: 0.0 ms (S4)
- Meilisearch RSS: 70.7 MB (S6)

## Stage 4 web map

- Measured at: 2026-09-18T19:58:50.264Z
- gzip JS+CSS with MapLibre: 341 KB (L1, limit 900)
- T1 streets visible: 1332 ms (limit 1500)
- T2 buildings z14: 1345 ms (limit 2500)
- L2 TTI: 1470 ms (limit 3500)
- S1 e2e p95 GET /v1/search q=apotek: 77.6 ms (limit 200, n=30)

## Stage 5 building click

- Measured at: 2026-09-18T21:33:01.435Z
- F6 building: `9e208165-8b3f-4277-bb92-9ade9c49121f` Big Fashion
- B1 GET /v1/orgs/:id p95: 27.7 ms (limit 80)
- B2 GET /v1/buildings/at p95: 65.1 ms (limit 100)
- B3 GET /v1/buildings/:id p95: 21.9 ms (limit 150)
- B4 click → org list p95: 70.0 ms (limit 350)
- B5 GET /v1/orgs bbox p95: 30.7 ms, max 200 (limit 200 / 150 ms)
- B6 GeoJSON contour: 322 B (limit 51200)
- B7 empty building: OK
