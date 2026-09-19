#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
DATA="$ROOT/data"
PBF="$DATA/osm/serbia-latest.osm.pbf"
BOUNDARY="$DATA/boundary/grad-novi-sad.geojson"
FORCE=${1:-}

mkdir -p "$DATA/boundary" "$DATA/tmp"
[ -f "$PBF" ] || {
  echo "Missing $PBF; run download-serbia.sh first." >&2
  exit 1
}

if [ "$FORCE" != "--force" ] && [ -s "$BOUNDARY" ]; then
  echo "Boundary already exists; skipping extraction."
  exit 0
fi

if docker run --rm -v "$DATA:/data" iboates/osmium:latest \
    getid --overwrite -r -t \
    /data/osm/serbia-latest.osm.pbf r1649672 \
    -o /data/tmp/grad-novi-sad.osm.pbf &&
  docker run --rm -v "$DATA:/data" iboates/osmium:latest \
    export --overwrite --geometry-types=polygon \
    /data/tmp/grad-novi-sad.osm.pbf \
    -o /data/boundary/grad-novi-sad.geojson; then
  echo "Boundary extracted from the Serbia PBF."
else
  echo "osmium boundary export failed; using the documented polygon fallback." >&2
  curl --fail --location --retry 5 \
    --output "$BOUNDARY" \
    "https://polygons.openstreetmap.fr/get_geojson.py?id=1649672&params=0"
fi

test -s "$BOUNDARY" || {
  echo "osmium did not produce a boundary GeoJSON." >&2
  exit 1
}
