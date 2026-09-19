#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
DATA="$ROOT/data"
SOURCE="$DATA/osm/serbia-latest.osm.pbf"
BOUNDARY="$DATA/boundary/grad-novi-sad.geojson"
OUTPUT="$DATA/osm/novi-sad.osm.pbf"
FORCE=${1:-}

mkdir -p "$DATA/osm" "$DATA/tmp"
[ -s "$SOURCE" ] || { echo "Missing Serbia PBF." >&2; exit 1; }
[ -s "$BOUNDARY" ] || { echo "Missing Grad Novi Sad boundary." >&2; exit 1; }

if [ "$FORCE" != "--force" ] && [ -s "$OUTPUT" ]; then
  echo "Novi Sad extract already exists; skipping extraction."
else
  docker run --rm -v "$DATA:/data" iboates/osmium:latest \
    extract --overwrite \
    --strategy=complete_ways \
    --polygon=/data/boundary/grad-novi-sad.geojson \
    /data/osm/serbia-latest.osm.pbf \
    -o /data/osm/novi-sad.osm.pbf
fi

docker run --rm -v "$DATA:/data" iboates/osmium:latest \
  fileinfo -e /data/osm/novi-sad.osm.pbf | tee "$DATA/tmp/novi-sad-fileinfo.txt"
