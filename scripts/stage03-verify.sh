#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

command -v node >/dev/null 2>&1 || {
  echo "Node.js is required for stage 3 verify." >&2
  exit 1
}

echo "Running stage 3 verify..."
node "$ROOT/scripts/stage03-verify.mjs"
