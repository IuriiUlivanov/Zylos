#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

need() {
  [ -e "$ROOT/$1" ] || { echo "Missing $1" >&2; exit 1; }
}

contains() {
  grep -q -F "$2" "$ROOT/$1" || { echo "$1 does not contain $2" >&2; exit 1; }
}

need Docs/STAGE-10-android.md
need scripts/build-mbtiles.sh
need docker-compose.mobile.yml
need apps/android/settings.gradle
need apps/android/app/build.gradle
need apps/android/app/src/main/java/rs/zylos/novisad/MapActivity.kt
need apps/android/branding/icon-512.png

contains infra/preview/style.json '"type": "fill-extrusion"'
contains apps/android/app/build.gradle 'org.maplibre.gl:android-sdk'
contains apps/android/app/src/main/java/rs/zylos/novisad/map/MapStyleFactory.kt 'mbtiles://'

MBTILES="$ROOT/data/tiles/novi-sad.mbtiles"
[ -s "$MBTILES" ] || { echo "Missing $MBTILES — run scripts/build-mbtiles.sh" >&2; exit 1; }

SIZE=$(wc -c < "$MBTILES" | tr -d ' ')
if [ "$SIZE" -lt 1048576 ] || [ "$SIZE" -gt $((30 * 1024 * 1024)) ]; then
  echo "MBTiles size $SIZE is outside 1–30 MB" >&2
  exit 1
fi

docker run --rm -v "$ROOT/data:/data" python:3.12-alpine python -c '
import sqlite3, sys
blob = dict(sqlite3.connect("/data/tiles/novi-sad.mbtiles").execute("SELECT name, value FROM metadata")).get("json","")
for layer in ("building", "housenumber", "transportation"):
    if layer not in blob:
        sys.exit(f"MBTiles missing layer {layer}")
'

if [ -n "${JAVA_HOME:-}" ] && [ -n "${ANDROID_HOME:-}" ]; then
  (cd "$ROOT/apps/android" && ./gradlew :app:testDebugUnitTest)
else
  echo "JDK 17 or Android SDK not found; skipped :app:testDebugUnitTest" >&2
fi

echo "stage10-verify OK"
