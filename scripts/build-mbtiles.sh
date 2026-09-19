#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
DATA="$ROOT/data"
SOURCE="$DATA/osm/novi-sad.osm.pbf"
OUTPUT="$DATA/tiles/novi-sad.mbtiles"
PMTILES="$DATA/tiles/novi-sad.pmtiles"
FORCE=${1:-}
MAX_BYTES=$((30 * 1024 * 1024))

mkdir -p "$DATA/tiles" "$DATA/tmp"
[ -s "$SOURCE" ] || { echo "Missing Novi Sad OSM extract." >&2; exit 1; }

PMTILES_MTIME=""
if [ -f "$PMTILES" ]; then
  PMTILES_MTIME=$(stat -c %Y "$PMTILES" 2>/dev/null || stat -f %m "$PMTILES")
fi

if [ "$FORCE" != "--force" ] && [ -s "$OUTPUT" ]; then
  echo "Novi Sad MBTiles already exists; skipping build."
else
  docker run --rm \
    -e JAVA_TOOL_OPTIONS=-Xmx4g \
    -v "$DATA:/data" \
    ghcr.io/onthegomap/planetiler:latest \
    --osm-path=/data/osm/novi-sad.osm.pbf \
    --output=/data/tiles/novi-sad.mbtiles \
    --download \
    --maxzoom=14 \
    --force
fi

if [ -n "$PMTILES_MTIME" ] && [ -f "$PMTILES" ]; then
  AFTER=$(stat -c %Y "$PMTILES" 2>/dev/null || stat -f %m "$PMTILES")
  if [ "$AFTER" != "$PMTILES_MTIME" ]; then
    echo "R2 failed: data/tiles/novi-sad.pmtiles mtime changed" >&2
    exit 1
  fi
fi

SIZE=$(wc -c < "$OUTPUT" | tr -d ' ')
if [ "$SIZE" -lt 1048576 ] || [ "$SIZE" -gt "$MAX_BYTES" ]; then
  echo "MBTiles size $SIZE bytes is outside 1–30 MB budget" >&2
  exit 1
fi

docker run --rm -v "$DATA:/data" python:3.12-alpine python -c '
import sqlite3, sys
con = sqlite3.connect("/data/tiles/novi-sad.mbtiles")
rows = dict(con.execute("SELECT name, value FROM metadata"))
blob = rows.get("json") or ""
print(blob)
open("/data/tmp/novi-sad-mbtiles.json", "w", encoding="utf-8").write(blob)
for layer in ("building", "housenumber", "transportation"):
    if layer not in blob:
        sys.exit(f"Required layer {layer} was not found in MBTiles metadata.")
'

echo "MBTiles OK: $OUTPUT ($SIZE bytes)"
