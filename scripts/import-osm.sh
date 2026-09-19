#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
PBF="$ROOT/data/osm/novi-sad.osm.pbf"
BOUNDARY="$ROOT/data/boundary/grad-novi-sad.geojson"
STYLE="$ROOT/infra/postgis/osm-flex.lua"
NETWORK="${DOCKER_NETWORK:-zylos_default}"
PGHOST="${PGHOST:-postgis}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-zylos}"
PGPASSWORD="${PGPASSWORD:-zylos}"
PGDATABASE="${PGDATABASE:-zylos}"

MODE="${1:-all}"
if [ "$MODE" != "all" ] && [ "$MODE" != "boundary" ] && [ "$MODE" != "osm" ]; then
  echo "Usage: $0 [all|boundary|osm]" >&2
  exit 2
fi

command -v docker >/dev/null 2>&1 || {
  echo "Docker is required." >&2
  exit 1
}

if [ ! -f "$PBF" ]; then
  echo "Missing OSM extract: $PBF" >&2
  exit 1
fi
if [ ! -f "$BOUNDARY" ]; then
  echo "Missing city boundary: $BOUNDARY" >&2
  exit 1
fi
if [ ! -f "$STYLE" ]; then
  echo "Missing osm2pgsql flex style: $STYLE" >&2
  exit 1
fi

PG_CONN="PG:host=$PGHOST port=$PGPORT dbname=$PGDATABASE user=$PGUSER password=$PGPASSWORD"

ensure_schemas() {
  docker compose -f "$ROOT/docker-compose.yml" exec -T postgis \
    psql -U "$PGUSER" -d "$PGDATABASE" -v ON_ERROR_STOP=1 <<'SQL'
CREATE SCHEMA IF NOT EXISTS osm_staging;
CREATE SCHEMA IF NOT EXISTS rgz_staging;
SQL
}

load_boundary_staging() {
  echo "Loading city boundary into osm_staging.city_boundary_src..."
  docker run --rm \
    --network "$NETWORK" \
    --mount "type=bind,source=$ROOT/data,target=/data" \
    -e PGPASSWORD="$PGPASSWORD" \
    ghcr.io/osgeo/gdal:ubuntu-small-latest \
    ogr2ogr -overwrite -f PostgreSQL "$PG_CONN" \
      "/data/boundary/grad-novi-sad.geojson" grad-novi-sad \
      -nln osm_staging.city_boundary_src \
      -lco GEOMETRY_NAME=geom \
      -t_srs EPSG:4326 \
      -nlt PROMOTE_TO_MULTI
}

promote_city_boundary() {
  echo "Promoting boundary into public.city_boundary..."
  docker compose -f "$ROOT/docker-compose.yml" exec -T postgis \
    psql -U "$PGUSER" -d "$PGDATABASE" -v ON_ERROR_STOP=1 <<'SQL'
INSERT INTO public.city_boundary (id, osm_relation_id, geom)
SELECT
  1,
  1649672,
  ST_Multi(
    ST_CollectionExtract(
      ST_MakeValid(ST_UnaryUnion(ST_Collect(geom))),
      3
    )
  )
FROM osm_staging.city_boundary_src
WHERE geom IS NOT NULL
ON CONFLICT (osm_relation_id) DO UPDATE
  SET geom = EXCLUDED.geom,
      loaded_at = now();
SQL
}

import_osm_pbf() {
  echo "Running osm2pgsql flex into osm_staging..."
  docker run --rm \
    --network "$NETWORK" \
    --mount "type=bind,source=$ROOT/data,target=/data" \
    --mount "type=bind,source=$ROOT/infra/postgis,target=/style" \
    -e PGPASSWORD="$PGPASSWORD" \
    iboates/osm2pgsql:latest \
    osm2pgsql \
      --create \
      --slim \
      --drop \
      --schema osm_staging \
      --middle-schema osm_staging \
      --output flex \
      --style /style/osm-flex.lua \
      --host "$PGHOST" \
      --port "$PGPORT" \
      --username "$PGUSER" \
      --database "$PGDATABASE" \
      /data/osm/novi-sad.osm.pbf
}

ensure_schemas

case "$MODE" in
  boundary)
    load_boundary_staging
    promote_city_boundary
    ;;
  osm)
    import_osm_pbf
    ;;
  all)
    load_boundary_staging
    promote_city_boundary
    import_osm_pbf
    ;;
esac

echo "OSM import step finished (mode=$MODE)."
