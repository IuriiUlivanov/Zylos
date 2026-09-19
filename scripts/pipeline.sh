#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
FORCE_ARG=
if [ "${1:-}" = "--force" ]; then
  FORCE_ARG=--force
elif [ -n "${1:-}" ]; then
  echo "Usage: $0 [--force]" >&2
  exit 2
fi

command -v docker >/dev/null 2>&1 || {
  echo "Docker is required." >&2
  exit 1
}
docker info >/dev/null 2>&1 || {
  echo "Docker daemon is not available." >&2
  exit 1
}

mkdir -p \
  "$ROOT/data/osm" "$ROOT/data/tiles" "$ROOT/data/boundary" \
  "$ROOT/data/tmp" "$ROOT/data/planetiler-tmp"

sh "$ROOT/scripts/download-serbia.sh" $FORCE_ARG
sh "$ROOT/scripts/fetch-boundary.sh" $FORCE_ARG
sh "$ROOT/scripts/extract-novi-sad.sh" $FORCE_ARG
sh "$ROOT/scripts/build-pmtiles.sh" $FORCE_ARG

fileinfo="$ROOT/data/tmp/novi-sad-fileinfo.txt"
pmtiles_info="$ROOT/data/tmp/novi-sad-pmtiles.txt"
bbox=$(grep -i -m1 "Bounding box" "$fileinfo" | cut -d: -f2- | sed 's/^[[:space:]]*//' || true)
nodes=$(grep -i -m1 "Number of nodes" "$fileinfo" | cut -d: -f2- | sed 's/^[[:space:]]*//' || true)
ways=$(grep -i -m1 "Number of ways" "$fileinfo" | cut -d: -f2- | sed 's/^[[:space:]]*//' || true)
relations=$(grep -i -m1 "Number of relations" "$fileinfo" | cut -d: -f2- | sed 's/^[[:space:]]*//' || true)
downloaded_at=$(cat "$ROOT/data/tmp/serbia-downloaded-at.txt" 2>/dev/null || date -u "+%Y-%m-%dT%H:%M:%SZ")

for layer in building housenumber transportation; do
  grep -qi "$layer" "$pmtiles_info" || {
    echo "Required layer '$layer' was not found in PMTiles metadata." >&2
    exit 1
  }
done

cat > "$ROOT/data/README.md" <<EOF
# Extract Novi Sad

- OSM source: https://download.geofabrik.de/europe/serbia-latest.osm.pbf
- Downloaded at: $downloaded_at
- Boundary: OSM relation 1649672 (Grad Novi Sad)
- License: ODbL, © OpenStreetMap contributors
- serbia-latest.osm.pbf: $(wc -c < "$ROOT/data/osm/serbia-latest.osm.pbf" | tr -d ' ') bytes
- novi-sad.osm.pbf: $(wc -c < "$ROOT/data/osm/novi-sad.osm.pbf" | tr -d ' ') bytes
- novi-sad.pmtiles: $(wc -c < "$ROOT/data/tiles/novi-sad.pmtiles" | tr -d ' ') bytes
- osmium fileinfo bbox: ${bbox:-see data/tmp/novi-sad-fileinfo.txt}
- OSM entities: nodes=${nodes:-unknown}, ways=${ways:-unknown}, relations=${relations:-unknown}
- planetiler maxzoom: 14
EOF

cd "$ROOT"
docker compose up -d --wait

sh "$ROOT/scripts/stage01-verify.sh"

echo
echo "Stage 1 services are ready:"
echo "  Preview: http://localhost:8080/"
echo "  PMTiles: http://localhost:8080/tiles/novi-sad.pmtiles"
echo "  Map verify: passed"
