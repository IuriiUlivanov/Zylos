#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
DATA="$ROOT/data"
SOURCE="$DATA/osm/novi-sad.osm.pbf"
OUTPUT="$DATA/tiles/novi-sad.pmtiles"
FORCE=${1:-}

mkdir -p "$DATA/tiles" "$DATA/planetiler-tmp"
[ -s "$SOURCE" ] || { echo "Missing Novi Sad OSM extract." >&2; exit 1; }

if [ "$FORCE" != "--force" ] && [ -s "$OUTPUT" ]; then
  echo "Novi Sad PMTiles already exists; skipping build."
else
  docker run --rm \
    -e JAVA_TOOL_OPTIONS=-Xmx4g \
    -v "$DATA:/data" \
    ghcr.io/onthegomap/planetiler:latest \
    --osm-path=/data/osm/novi-sad.osm.pbf \
    --output=/data/tiles/novi-sad.pmtiles \
    --download \
    --maxzoom=14 \
    --force
fi

docker run --rm -v "$DATA:/data" protomaps/go-pmtiles:latest \
  show --metadata /data/tiles/novi-sad.pmtiles | tee "$DATA/tmp/novi-sad-pmtiles.txt"
