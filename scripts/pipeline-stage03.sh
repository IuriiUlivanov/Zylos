#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
PMTILES="$ROOT/data/tiles/novi-sad.pmtiles"

command -v docker >/dev/null 2>&1 || {
  echo "Docker is required but was not found in PATH." >&2
  exit 1
}
command -v node >/dev/null 2>&1 || {
  echo "Node.js is required for stage 3." >&2
  exit 1
}

mkdir -p "$ROOT/data/tmp"

if [ ! -f "$ROOT/.env" ]; then
  cp "$ROOT/.env.example" "$ROOT/.env"
  echo "Created .env from .env.example"
fi

PMTILES_BEFORE=
if [ -f "$PMTILES" ]; then
  PMTILES_BEFORE=$(stat -c %Y "$PMTILES" 2>/dev/null || stat -f %m "$PMTILES")
fi

cd "$ROOT"

echo "Step 1: docker compose up postgis meilisearch api..."
docker compose up -d --build postgis meilisearch api --wait

echo "Step 2: installing script dependencies..."
cd "$ROOT/scripts"
if [ -f package-lock.json ]; then
  npm ci
else
  npm install
fi
cd "$ROOT"

echo "Step 3: indexing PostGIS → Meilisearch..."
node "$ROOT/scripts/index-meilisearch.mjs"

echo "Step 4: verification..."
node "$ROOT/scripts/stage03-verify.mjs"

echo "Step 5: benchmark..."
node "$ROOT/scripts/stage03-benchmark.mjs"

if [ -n "$PMTILES_BEFORE" ] && [ -f "$PMTILES" ]; then
  PMTILES_AFTER=$(stat -c %Y "$PMTILES" 2>/dev/null || stat -f %m "$PMTILES")
  if [ "$PMTILES_AFTER" != "$PMTILES_BEFORE" ]; then
    echo "R3 failed: data/tiles/novi-sad.pmtiles mtime changed" >&2
    exit 1
  fi
fi

echo
echo "Stage 3 pipeline finished."
echo "  Search: http://127.0.0.1:3000/v1/search?q=apotek"
echo "  Health: http://127.0.0.1:3000/v1/health"
