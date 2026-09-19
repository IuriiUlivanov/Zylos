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
  curl -sS -o /tmp/zylos-stage12-body -w "%{http_code}" "$1"
}

echo "Stage 12 structural checks..."

need Docs/STAGE-12-android-search.md
need apps/android/app/src/main/java/rs/zylos/novisad/MapActivity.kt
need apps/android/app/src/main/java/rs/zylos/novisad/map/SearchMarker.kt
need apps/android/app/src/main/java/rs/zylos/novisad/data/api/ZylosApi.kt
need apps/android/app/src/main/java/rs/zylos/novisad/data/repository/SearchRepository.kt
need apps/android/app/src/main/java/rs/zylos/novisad/data/local/ZylosDatabase.kt
need apps/android/app/src/main/java/rs/zylos/novisad/data/local/SearchHistoryDao.kt
need apps/android/app/src/main/java/rs/zylos/novisad/ui/search/SearchDropdownAdapter.kt
need apps/android/app/src/test/java/rs/zylos/novisad/viewmodel/SearchLogicTest.kt
need apps/android/app/src/test/java/rs/zylos/novisad/data/local/SearchHistoryDaoTest.kt
need scripts/stage12-verify.sh

contains apps/android/app/src/main/java/rs/zylos/novisad/data/api/ZylosApi.kt 'v1/search'
contains apps/android/app/src/main/java/rs/zylos/novisad/map/MapDefaults.kt 'SEARCH_DEBOUNCE_MS = 150'
contains apps/android/app/src/main/java/rs/zylos/novisad/map/MapDefaults.kt 'SEARCH_MIN_LENGTH = 2'
contains apps/android/app/src/main/java/rs/zylos/novisad/map/SearchMarker.kt 'selected-marker'
contains apps/android/app/src/main/java/rs/zylos/novisad/data/local/SearchHistoryEntity.kt 'search_history'
contains apps/android/app/src/main/res/layout/activity_map.xml 'searchInput'
contains apps/android/app/src/main/res/values/strings.xml 'Ništa nije pronađeno'
contains apps/android/app/src/main/res/values/strings.xml 'Pretraga privremeno nedostupna'
contains apps/android/app/src/main/java/rs/zylos/novisad/MapActivity.kt 'TODO(stage-5)'
contains apps/android/app/build.gradle 'androidx.room:room-runtime'

echo "Stage 12 API checks ($API)..."

HEALTH=$(http_code "$API/v1/health")
[ "$HEALTH" = "200" ] || { echo "P2 /v1/health expected 200, got $HEALTH" >&2; exit 1; }

F1=$(http_code "$API/v1/search?q=apotek&limit=15")
[ "$F1" = "200" ] || { echo "A1 F1 expected 200, got $F1" >&2; exit 1; }
python3 - <<'PY'
import json, sys
body = json.load(open("/tmp/zylos-stage12-body"))
hits = body.get("hits") or []
if len(hits) < 5:
    sys.exit(f"A1 F1 hits={len(hits)}, expected >= 5")
if any(h.get("kind") != "organization" for h in hits):
    sys.exit("A1 F1 expected all hits kind=organization")
if not any(h.get("category_slug") == "pharmacy" for h in hits):
    sys.exit("A1 F1 expected >= 1 category_slug=pharmacy")
if len(hits) > 15:
    sys.exit(f"A5 hits={len(hits)} exceeds 15")
print(f"A1 F1 hits={len(hits)}")
PY

F3=$(http_code "$API/v1/search?q=bulevar&limit=15")
[ "$F3" = "200" ] || { echo "A2 F3 expected 200, got $F3" >&2; exit 1; }
python3 - <<'PY'
import json, sys
body = json.load(open("/tmp/zylos-stage12-body"))
hits = body.get("hits") or []
if len(hits) < 10:
    sys.exit(f"A2 F3 hits={len(hits)}, expected >= 10")
addr = [h for h in hits if h.get("kind") == "address"]
if len(addr) < 5:
    sys.exit(f"A2 F3 address hits={len(addr)}, expected >= 5")
print(f"A2 F3 hits={len(hits)} address={len(addr)}")
PY

F5=$(http_code "$API/v1/search?q=a")
[ "$F5" = "200" ] || { echo "A3 F5 expected 200, got $F5" >&2; exit 1; }
python3 - <<'PY'
import json, sys
body = json.load(open("/tmp/zylos-stage12-body"))
if body.get("hits"):
    sys.exit("A3 F5 expected hits=[]")
print("A3 F5 empty OK")
PY

F6=$(http_code "$API/v1/search?q=")
[ "$F6" = "200" ] || { echo "A4 F6 expected 200, got $F6" >&2; exit 1; }
python3 - <<'PY'
import json, sys
body = json.load(open("/tmp/zylos-stage12-body"))
if body.get("hits"):
    sys.exit("A4 F6 expected hits=[]")
ms = int(body.get("processingTimeMs") or 0)
if ms > 20:
    sys.exit(f"A4 F6 processingTimeMs={ms}, expected <= 20")
print(f"A4 F6 processingTimeMs={ms}")
PY

echo "S1 p95 curl localhost..."
python3 - <<PY
import subprocess, sys
api = "$API"
samples = []
for i in range(33):
    out = subprocess.check_output(
        ["curl", "-sS", "-o", "/dev/null", "-w", "%{time_total}", "--max-time", "15",
         f"{api}/v1/search?q=apotek&limit=10"],
        text=True,
    )
    samples.append(float(out) * 1000.0)
warm = sorted(samples[3:])
p95 = warm[27]
if p95 > 200:
    sys.exit(f"S1 p95={p95}ms exceeds 200ms e2e localhost")
print(f"S1 p95={p95:.1f}ms (n=30 after warmup)")
PY

if [ -n "${JAVA_HOME:-}" ] && [ -n "${ANDROID_HOME:-}" ]; then
  (cd "$ROOT/apps/android" && ./gradlew :app:testDebugUnitTest)
else
  echo "JDK 17 or Android SDK not found; :app:testDebugUnitTest is required for STAGE-12" >&2
  exit 1
fi

echo "stage12-verify OK"
