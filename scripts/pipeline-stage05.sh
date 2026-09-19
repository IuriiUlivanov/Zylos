#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
PMTILES="$ROOT/data/tiles/novi-sad.pmtiles"
WEB="$ROOT/apps/web"
API="$ROOT/apps/api"
SCRIPTS="$ROOT/scripts"

command -v docker >/dev/null 2>&1 || {
  echo "Docker is required but was not found in PATH." >&2
  exit 1
}
command -v node >/dev/null 2>&1 || {
  echo "Node.js is required for stage 5." >&2
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

echo "Step 1: docker compose up postgis meilisearch api tiles..."
docker compose up -d --build postgis meilisearch api tiles --wait

echo "Step 2: installing script dependencies..."
cd "$SCRIPTS"
if [ -f package-lock.json ]; then
  npm ci
else
  npm install
fi
cd "$ROOT"

echo "Step 3: installing, testing and building apps/web..."
cd "$WEB"
if [ -f package-lock.json ]; then
  npm ci
else
  npm install
fi
npm test
npm run build
cd "$ROOT"

echo "Step 4: building apps/api..."
cd "$API"
if [ -f package-lock.json ]; then
  npm ci
else
  npm install
fi
npm run build
cd "$ROOT"

echo "Step 5: verification..."
export SKIP_WEB_BUILD=1
node "$SCRIPTS/stage05-verify.mjs"

echo "Step 6: benchmark..."
node "$SCRIPTS/stage05-benchmark.mjs"

if [ "${SKIP_STAGE04:-0}" != "1" ]; then
  echo "Step 7: stage 4 verify subset/full..."
  export SKIP_WEB_BUILD=1
  node "$SCRIPTS/stage04-verify.mjs"
fi

if [ -n "$PMTILES_BEFORE" ] && [ -f "$PMTILES" ]; then
  PMTILES_AFTER=$(stat -c %Y "$PMTILES" 2>/dev/null || stat -f %m "$PMTILES")
  if [ "$PMTILES_AFTER" != "$PMTILES_BEFORE" ]; then
    echo "R2 failed: data/tiles/novi-sad.pmtiles mtime changed" >&2
    exit 1
  fi
fi

echo
echo "Stage 5 pipeline finished."
echo "  Web:   http://127.0.0.1:5173"
echo "  API:   http://127.0.0.1:3000/v1/buildings/at?lon=19.8435&lat=45.2458"
echo "  Tiles: http://127.0.0.1:8080"
