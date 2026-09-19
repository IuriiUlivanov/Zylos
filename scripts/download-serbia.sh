#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OSM_DIR="$ROOT/data/osm"
PBF="$OSM_DIR/serbia-latest.osm.pbf"
MD5="$PBF.md5"
URL="https://download.geofabrik.de/europe/serbia-latest.osm.pbf"
FORCE=${1:-}

mkdir -p "$OSM_DIR" "$ROOT/data/tmp"

size=0
downloaded=false
[ -f "$PBF" ] && size=$(wc -c < "$PBF" | tr -d ' ')
if [ "$FORCE" != "--force" ] && [ "$size" -gt 104857600 ]; then
  echo "Serbia PBF already exists ($size bytes); skipping download."
else
  [ "$FORCE" = "--force" ] && rm -f "$PBF"
  echo "Downloading Serbia extract (resume enabled)..."
  curl --fail --location --retry 5 --retry-delay 3 --continue-at - \
    --output "$PBF" "$URL"
  downloaded=true
fi

curl --fail --location --retry 5 --output "$MD5" "$URL.md5"

if command -v md5sum >/dev/null 2>&1; then
  (cd "$OSM_DIR" && md5sum --check "$(basename "$MD5")")
elif command -v md5 >/dev/null 2>&1; then
  expected=$(awk '{print $1}' "$MD5")
  actual=$(md5 -q "$PBF")
  [ "$actual" = "$expected" ] || {
    echo "MD5 mismatch: expected $expected, got $actual" >&2
    exit 1
  }
  echo "MD5 verified."
else
  echo "No MD5 utility found; checksum was downloaded but not verified." >&2
fi

if [ "$downloaded" = true ] || [ ! -f "$ROOT/data/tmp/serbia-downloaded-at.txt" ]; then
  date -u "+%Y-%m-%dT%H:%M:%SZ" > "$ROOT/data/tmp/serbia-downloaded-at.txt"
fi
