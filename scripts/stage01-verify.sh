#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
VERIFY_DIR="$ROOT/scripts/map-verify"

command -v node >/dev/null 2>&1 || {
  echo "Node.js is required for stage 1 map verify." >&2
  exit 1
}

if [ ! -d "$VERIFY_DIR/node_modules" ]; then
  echo "Installing map-verify dependencies..."
  cd "$VERIFY_DIR"
  if [ -f package-lock.json ]; then
    npm ci
  else
    npm install
  fi
fi

echo "Running stage 1 map verify..."
node "$VERIFY_DIR/verify.mjs"
