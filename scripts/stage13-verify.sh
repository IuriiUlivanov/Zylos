#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
API="${ZYLOS_API_URL:-http://127.0.0.1:3000}"

need() {
  [ -e "$ROOT/$1" ] || { echo "Missing $1" >&2; exit 1; }
}

contains() {
  grep -q -F "$2" "$ROOT/$1" || { echo "$1 does not contain $2" >&2; exit 1; }
}

http_code() {
  curl -sS -o /tmp/zylos-stage13-body -w "%{http_code}" "$1"
}

echo "Stage 13 structural checks..."

need Docs/STAGE-13-android-poi.md
need Docs/design/MOBILE-POI-ZOOM.md
need infra/preview/style-mobile.json
need infra/preview/style.json
need apps/android/app/src/main/java/rs/zylos/app/map/OrgPinLogic.kt
need apps/android/app/src/main/java/rs/zylos/app/map/OrgPins.kt
need apps/android/app/src/main/java/rs/zylos/app/map/SearchPins.kt
need apps/android/app/src/test/java/rs/zylos/app/map/OrgPinLogicTest.kt
need apps/android/app/src/test/java/rs/zylos/app/viewmodel/SearchMultiLogicTest.kt
need scripts/stage13-verify.sh

contains apps/android/app/src/main/java/rs/zylos/app/data/api/ZylosApi.kt 'v1/orgs'
contains apps/android/app/src/main/java/rs/zylos/app/data/repository/OrgRepository.kt 'inBbox'
contains apps/android/app/src/main/java/rs/zylos/app/map/MapDefaults.kt 'ORG_PINS_DEBOUNCE_MS = 300'
contains apps/android/app/src/main/java/rs/zylos/app/map/MapDefaults.kt 'ORG_PINS_MIN_ZOOM = 15'
contains apps/android/app/src/main/java/rs/zylos/app/map/MapDefaults.kt 'SEARCH_MULTI_MAX_PINS = 15'
contains apps/android/app/src/main/java/rs/zylos/app/map/MapDefaults.kt 'style-mobile.json'
contains apps/android/app/src/main/java/rs/zylos/app/map/OrgPins.kt 'org-pins'
contains apps/android/app/src/main/java/rs/zylos/app/map/SearchPins.kt 'search-pins'
contains apps/android/app/src/main/res/values/strings.xml 'Prikaži sve na karti'
contains apps/android/app/src/main/java/rs/zylos/app/MapActivity.kt 'TODO(stage-5)'
contains apps/android/app/build.gradle 'style-mobile.json'
contains infra/preview/style-mobile.json 'poi-dot'
contains infra/preview/style-mobile.json 'poi-label'
contains infra/preview/style.json 'fill-extrusion'

echo "Stage 13 API checks ($API)..."

HEALTH=$(http_code "$API/v1/health")
[ "$HEALTH" = "200" ] || { echo "P2 /v1/health expected 200, got $HEALTH" >&2; exit 1; }

BBOX=$(http_code "$API/v1/orgs?bbox=19.83,45.24,19.86,45.26&limit=40")
[ "$BBOX" = "200" ] || { echo "A1 bbox expected 200, got $BBOX" >&2; exit 1; }
python3 - <<'PY'
import json, sys
pins = json.load(open("/tmp/zylos-stage13-body"))
if not isinstance(pins, list) or len(pins) < 1:
    sys.exit(f"A1 pins={pins if not isinstance(pins, list) else len(pins)}")
if len(pins) > 40:
    sys.exit(f"A2 limit=40 returned {len(pins)}")
for pin in pins:
    if not pin.get("id") or not pin.get("name") or not isinstance(pin.get("lon"), (int, float)) or not isinstance(pin.get("lat"), (int, float)):
        sys.exit("A1 pin missing id/name/lon/lat")
print(f"B5a/B5c pins={len(pins)}")
PY

BAD=$(http_code "$API/v1/orgs?bbox=foo")
[ "$BAD" = "400" ] || { echo "A4 invalid bbox expected 400, got $BAD" >&2; exit 1; }
python3 - <<'PY'
import json, sys
body = json.load(open("/tmp/zylos-stage13-body"))
if body.get("error") != "invalid_bbox":
    sys.exit(f"A4 expected invalid_bbox, got {body}")
print("A4 invalid_bbox OK")
PY

SEARCH=$(http_code "$API/v1/search?q=apotek&limit=15")
[ "$SEARCH" = "200" ] || { echo "X7 search expected 200, got $SEARCH" >&2; exit 1; }
python3 - <<'PY'
import json, sys
body = json.load(open("/tmp/zylos-stage13-body"))
hits = body.get("hits") or []
if len(hits) < 3:
    sys.exit(f"X1 apotek hits={len(hits)}, expected >= 3")
print(f"X1/X7 search hits={len(hits)}")
PY

echo "A5 p95 bbox curl localhost..."
python3 - <<PY
import subprocess, sys
api = "$API"
samples = []
for i in range(33):
    out = subprocess.check_output(
        ["curl", "-sS", "-o", "/dev/null", "-w", "%{time_total}", "--max-time", "15",
         f"{api}/v1/orgs?bbox=19.83,45.24,19.86,45.26&limit=40"],
        text=True,
    )
    samples.append(float(out) * 1000.0)
warm = sorted(samples[3:])
p95 = warm[27]
if p95 > 150:
    sys.exit(f"A5 p95={p95}ms exceeds 150ms bbox localhost")
print(f"A5 p95={p95:.1f}ms (n=30 after warmup)")
PY

if [ -n "${JAVA_HOME:-}" ] && [ -n "${ANDROID_HOME:-}" ]; then
  (cd "$ROOT/apps/android" && ./gradlew :app:testDebugUnitTest)
else
  echo "JDK 17 or Android SDK not found; :app:testDebugUnitTest is required for STAGE-13" >&2
  exit 1
fi

echo "stage13-verify OK"
