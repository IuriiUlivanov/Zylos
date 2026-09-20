#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

need() {
  [ -e "$ROOT/$1" ] || { echo "Missing $1" >&2; exit 1; }
}

contains() {
  grep -q -F "$2" "$ROOT/$1" || { echo "$1 does not contain $2" >&2; exit 1; }
}

echo "Stage 14 structural checks..."

need Docs/STAGE-14-android-transit.md
need apps/api/src/types/route.ts
need apps/api/src/routes/route.ts
need apps/api/src/services/otpClient.ts
need infra/otp/Dockerfile
need apps/android/app/src/main/java/rs/zylos/app/data/api/RouteDto.kt
need apps/android/app/src/main/java/rs/zylos/app/map/RouteLayers.kt
need apps/android/app/src/main/java/rs/zylos/app/viewmodel/RouteLogic.kt
need scripts/stage14-verify.mjs
need scripts/stage14-verify.sh

need data/gtfs/jgsp/agency.txt
need data/gtfs/jgsp/routes.txt
need data/gtfs/jgsp/stops.txt
need data/gtfs/jgsp/stop_times.txt
need data/gtfs/jgsp/trips.txt
need data/gtfs/jgsp/calendar.txt

contains apps/android/app/src/main/java/rs/zylos/app/data/api/ZylosApi.kt 'v1/route'
contains apps/android/app/src/main/java/rs/zylos/app/map/MapDefaults.kt 'ROUTE_CLIENT_TIMEOUT_MS = 8_000'
contains apps/android/app/src/main/java/rs/zylos/app/map/RouteLayers.kt 'route-walk'
contains apps/android/app/src/main/java/rs/zylos/app/map/RouteLayers.kt 'lineDasharray'
contains apps/android/app/src/main/res/values/strings.xml 'Napravi rutu'
contains apps/android/app/src/main/java/rs/zylos/app/MapActivity.kt 'TODO(stage-5)'
contains docker-compose.yml 'container_name: otp'

echo "Stage 14 API fixtures..."
node "$ROOT/scripts/stage14-verify.mjs"

if [ -f "$ROOT/apps/api/package.json" ]; then
  echo "Running API vitest..."
  (cd "$ROOT/apps/api" && if [ ! -d node_modules/vitest ]; then npm ci; fi && npm test)
fi

if [ -n "${JAVA_HOME:-}" ] && [ -n "${ANDROID_HOME:-}" ]; then
  (cd "$ROOT/apps/android" && ./gradlew :app:testDebugUnitTest)
else
  echo "JDK 17 or Android SDK not found; :app:testDebugUnitTest is required for STAGE-14" >&2
  exit 1
fi

echo "stage14-verify OK"
