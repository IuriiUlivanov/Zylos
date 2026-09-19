#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
GPKG="$ROOT/data/rgz/kucni_broj.gpkg"
BOUNDARY="$ROOT/data/boundary/grad-novi-sad.geojson"
NETWORK="${DOCKER_NETWORK:-zylos_default}"
PGHOST="${PGHOST:-postgis}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-zylos}"
PGPASSWORD="${PGPASSWORD:-zylos}"
PGDATABASE="${PGDATABASE:-zylos}"

if [ ! -f "$GPKG" ]; then
  echo "Missing RGZ GeoPackage: $GPKG (run scripts/download-rgz.sh first)" >&2
  exit 1
fi
if [ ! -f "$BOUNDARY" ]; then
  echo "Missing city boundary: $BOUNDARY" >&2
  exit 1
fi

PG_CONN="PG:host=$PGHOST port=$PGPORT dbname=$PGDATABASE user=$PGUSER password=$PGPASSWORD"

echo "Inspecting RGZ GeoPackage..."
docker run --rm \
  --mount "type=bind,source=$ROOT/data,target=/data" \
  ghcr.io/osgeo/gdal:ubuntu-small-latest \
  ogrinfo -so /data/rgz/kucni_broj.gpkg

docker run --rm \
  --mount "type=bind,source=$ROOT/data,target=/data" \
  ghcr.io/osgeo/gdal:ubuntu-small-latest \
  ogrinfo -al -so /data/rgz/kucni_broj.gpkg

echo "RGZ layer: kucni_broj"
echo "RGZ source CRS: EPSG:25834 (ETRS89 / UTM zone 34N)"
echo "Column mapping:"
echo "  rg_id          <- CAST(primary_key AS TEXT)"
echo "  street         <- ulica_ime_lat"
echo "  street_sr_cyrl <- ulica_ime"
echo "  housenumber    <- kucni_broj"
echo "  postcode       <- NULL (not present in source)"
echo "  geom           <- geom (reprojected to EPSG:4326, clipped to city boundary)"

echo "Truncating rgz_staging.kucni_broj_src..."
docker compose -f "$ROOT/docker-compose.yml" exec -T postgis \
  psql -U "$PGUSER" -d "$PGDATABASE" -v ON_ERROR_STOP=1 \
  -c "TRUNCATE TABLE rgz_staging.kucni_broj_src;"

echo "Clipping and loading RGZ house numbers..."
docker run --rm \
  --network "$NETWORK" \
  --mount "type=bind,source=$ROOT/data,target=/data" \
  -e PGPASSWORD="$PGPASSWORD" \
  ghcr.io/osgeo/gdal:ubuntu-small-latest \
  ogr2ogr -append -f PostgreSQL "$PG_CONN" \
    "/data/rgz/kucni_broj.gpkg" kucni_broj \
    -nln rgz_staging.kucni_broj_src \
    -sql "SELECT CAST(primary_key AS TEXT) AS rg_id, ulica_ime_lat AS street, ulica_ime AS street_sr_cyrl, kucni_broj AS housenumber, CAST(NULL AS TEXT) AS postcode, geom FROM kucni_broj" \
    -s_srs EPSG:25834 \
    -t_srs EPSG:4326 \
    -clipsrc "/data/boundary/grad-novi-sad.geojson" \
    -lco GEOMETRY_NAME=geom \
    -nlt POINT

echo "RGZ staging row count:"
docker compose -f "$ROOT/docker-compose.yml" exec -T postgis \
  psql -U "$PGUSER" -d "$PGDATABASE" -t -A \
  -c "SELECT count(*) FROM rgz_staging.kucni_broj_src;"

echo "RGZ import into staging complete."
