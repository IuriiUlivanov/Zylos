#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
API="${ZYLOS_API_URL:-http://127.0.0.1:3000}"
F6_LON="19.843486795879464"
F6_LAT="45.24576475"

need() {
  [ -e "$ROOT/$1" ] || { echo "Missing $1" >&2; exit 1; }
}

contains() {
  grep -q -F "$2" "$ROOT/$1" || { echo "$1 does not contain $2" >&2; exit 1; }
}

http_code() {
  curl -sS -o /tmp/zylos-stage11-body -w "%{http_code}" "$1"
}

echo "Stage 11 structural checks..."

need Docs/STAGE-11-android-building.md
need apps/android/app/src/main/java/rs/zylos/novisad/MapActivity.kt
need apps/android/app/src/main/java/rs/zylos/novisad/map/BuildingHighlight.kt
need apps/android/app/src/main/java/rs/zylos/novisad/data/api/ZylosApi.kt
need apps/android/app/src/main/java/rs/zylos/novisad/data/api/ApiClient.kt
need apps/android/app/src/main/java/rs/zylos/novisad/data/repository/BuildingRepository.kt
need apps/android/app/src/main/java/rs/zylos/novisad/viewmodel/MapViewModel.kt
need apps/android/app/src/test/java/rs/zylos/novisad/map/BuildingHighlightTest.kt
need scripts/stage11-verify.sh

contains apps/android/app/build.gradle 'com.squareup.retrofit2:retrofit'
contains apps/android/app/src/main/java/rs/zylos/novisad/data/api/ZylosApi.kt 'v1/buildings/at'
contains apps/android/app/src/main/java/rs/zylos/novisad/data/api/ZylosApi.kt 'v1/buildings/{id}'
contains apps/android/app/src/main/java/rs/zylos/novisad/data/api/ZylosApi.kt 'v1/orgs/{id}'
contains apps/android/app/src/main/java/rs/zylos/novisad/MapActivity.kt 'addOnMapClickListener'
contains apps/android/app/src/main/java/rs/zylos/novisad/MapActivity.kt 'TODO(stage-5)'
contains apps/android/app/src/main/java/rs/zylos/novisad/map/BuildingHighlight.kt 'selected-building'
contains apps/android/app/src/main/res/layout/activity_map.xml 'BottomSheetBehavior'
contains apps/android/app/src/main/res/values/strings.xml 'Nema zgrade na ovoj tački'
contains apps/android/app/src/main/java/rs/zylos/novisad/map/MapDefaults.kt 'SHEET_ANIMATION_MS = 250'
contains apps/android/app/src/main/java/rs/zylos/novisad/MapActivity.kt 'isAttributionEnabled = true'

echo "Stage 11 API checks ($API)..."

HEALTH=$(http_code "$API/v1/health")
[ "$HEALTH" = "200" ] || { echo "P2 /v1/health expected 200, got $HEALTH" >&2; exit 1; }

PROBE=$(http_code "$API/v1/buildings/at?lon=19.845&lat=45.255")
case "$PROBE" in
  5*) echo "P3 /buildings/at returned $PROBE" >&2; exit 1 ;;
esac

AT_CODE=$(http_code "$API/v1/buildings/at?lon=$F6_LON&lat=$F6_LAT")
[ "$AT_CODE" = "200" ] || { echo "A1 F6 /buildings/at expected 200, got $AT_CODE" >&2; exit 1; }
F6_ID=$(python3 -c "import json; print(json.load(open('/tmp/zylos-stage11-body'))['id'])")

DETAIL_CODE=$(http_code "$API/v1/buildings/$F6_ID")
[ "$DETAIL_CODE" = "200" ] || { echo "A2 F6 /buildings/:id expected 200, got $DETAIL_CODE" >&2; exit 1; }
python3 - <<'PY'
import json, sys
body = json.load(open("/tmp/zylos-stage11-body"))
orgs = body.get("organizations") or []
if len(orgs) < 2:
    sys.exit(f"A2 F6 organizations.length={len(orgs)}, expected >= 2")
geom = json.dumps(body.get("geometry") or {}, separators=(",", ":"))
if len(geom.encode()) > 51200:
    sys.exit(f"A4 geometry is {len(geom.encode())} bytes, budget 50 KB")
open("/tmp/zylos-stage11-org", "w").write(orgs[0]["id"])
print(f"A2/A4 orgs={len(orgs)} geometry={len(geom.encode())}B")
PY

ORG_ID=$(cat /tmp/zylos-stage11-org)
ORG_CODE=$(http_code "$API/v1/orgs/$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1], safe=''))" "$ORG_ID")")
[ "$ORG_CODE" = "200" ] || { echo "A3 /orgs/:id expected 200, got $ORG_CODE" >&2; exit 1; }

OUT=$(http_code "$API/v1/buildings/at?lon=20.46&lat=44.817")
[ "$OUT" = "422" ] || { echo "outside city expected 422, got $OUT" >&2; exit 1; }

EMPTY_ID=$(docker compose exec -T postgis sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -t -A -c "SELECT b.id::text FROM public.building b WHERE NOT EXISTS (SELECT 1 FROM public.organization o WHERE o.building_id = b.id) LIMIT 1;"' | tr -d '\r' | head -n 1 || true)
if [ -z "${EMPTY_ID:-}" ]; then
  echo "A5 no empty building id from PostGIS" >&2
  exit 1
fi
EMPTY_CODE=$(http_code "$API/v1/buildings/$EMPTY_ID")
[ "$EMPTY_CODE" = "200" ] || { echo "A5 empty building HTTP $EMPTY_CODE" >&2; exit 1; }
python3 - <<'PY'
import json, sys
body = json.load(open("/tmp/zylos-stage11-body"))
orgs = body.get("organizations") or []
if len(orgs) != 0:
    sys.exit(f"A5 expected organizations=[], got {len(orgs)}")
print("A5 empty building orgs=0")
PY

if [ -n "${JAVA_HOME:-}" ] && [ -n "${ANDROID_HOME:-}" ]; then
  (cd "$ROOT/apps/android" && ./gradlew :app:testDebugUnitTest)
else
  echo "JDK 17 or Android SDK not found; :app:testDebugUnitTest is required for STAGE-11" >&2
  exit 1
fi

echo "stage11-verify OK"
