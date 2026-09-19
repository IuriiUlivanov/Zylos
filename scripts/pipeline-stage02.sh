#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
FORCE_ARG=
if [ "${1:-}" = "--force" ] || [ "${1:-}" = "-f" ]; then
  FORCE_ARG=--force
elif [ -n "${1:-}" ]; then
  echo "Usage: $0 [--force]" >&2
  exit 2
fi

wait_for_sql() {
  file=$1
  timeout=${2:-600}
  elapsed=0
  while [ ! -f "$file" ]; do
    if [ "$elapsed" -ge "$timeout" ]; then
      echo "Timed out waiting for $file" >&2
      exit 1
    fi
    echo "Waiting for $file ..."
    sleep 5
    elapsed=$((elapsed + 5))
  done
}

run_psql_file() {
  rel=$1
  file="$ROOT/$rel"
  if [ ! -f "$file" ]; then
    echo "Required SQL file is missing: $rel" >&2
    exit 1
  fi
  docker compose -f "$ROOT/docker-compose.yml" exec -T postgis \
    psql -U zylos -d zylos -v ON_ERROR_STOP=1 -f - < "$file"
}

mkdir -p "$ROOT/data/rgz" "$ROOT/data/tmp"

cd "$ROOT"
echo "Step 1: ensuring PostGIS is up..."
docker compose up -d postgis --wait

echo "Step 2: applying schema and seed categories..."
run_psql_file infra/postgis/schema.sql
run_psql_file infra/postgis/seed_categories.sql

echo "Step 3: loading city boundary..."
sh "$ROOT/scripts/import-osm.sh" boundary

echo "Step 4: OSM import..."
sh "$ROOT/scripts/import-osm.sh" osm
wait_for_sql "$ROOT/infra/postgis/load_osm.sql"
run_psql_file infra/postgis/load_osm.sql

echo "Step 5: downloading RGZ if needed..."
sh "$ROOT/scripts/download-rgz.sh" $FORCE_ARG

echo "Step 6: RGZ clip + import..."
sh "$ROOT/scripts/import-rgz.sh"
wait_for_sql "$ROOT/infra/postgis/load_rgz.sql"
run_psql_file infra/postgis/load_rgz.sql

echo "Step 7: linking buildings..."
run_psql_file infra/postgis/link_buildings.sql

if [ -f "$ROOT/infra/postgis/fixtures.sql" ]; then
  echo "Step 7b: loading F4 fixtures..."
  run_psql_file infra/postgis/fixtures.sql
fi

echo "Step 8: verification..."
VERIFY_LOG="$ROOT/data/tmp/stage02-verify.log"
if docker compose exec -T postgis \
  psql -U zylos -d zylos -v ON_ERROR_STOP=1 \
  -f - < "$ROOT/scripts/stage02-verify.sql" \
  > "$VERIFY_LOG" 2>&1; then
  cat "$VERIFY_LOG"
else
  verify_status=$?
  cat "$VERIFY_LOG"
  exit "$verify_status"
fi

echo "Step 9: updating data/README.md..."
python - "$ROOT/data/README.md" "$VERIFY_LOG" "$ROOT/data/rgz/downloaded-at.txt" <<'PY'
import pathlib, re, sys
readme_path, log_path, stamp_path = map(pathlib.Path, sys.argv[1:])
verify = log_path.read_text(encoding="utf-8", errors="replace")
metrics = {}
pat = re.compile(
    r"\|\s*(C[1-7])\s*\|\s*([^|]+)\|\s*([^|]+)\|\s*([^|]+)\|\s*(OK|FAIL)\s*\|"
)
for match in pat.finditer(verify):
    metrics[match.group(1)] = {
        "metric": match.group(2).strip(),
        "value": match.group(3).strip(),
        "req": match.group(4).strip(),
        "status": match.group(5).strip(),
    }
rgz_date = "unknown"
if stamp_path.exists():
    rgz_date = stamp_path.read_text(encoding="utf-8").strip()
existing = readme_path.read_text(encoding="utf-8") if readme_path.exists() else ""
existing = re.split(r"\r?\n## Stage 2", existing, maxsplit=1)[0].rstrip()
rows = []
for key in ("C1", "C2", "C3", "C4", "C5", "C6", "C7"):
    m = metrics.get(key)
    if m:
        rows.append(f"| {key} | {m['metric']} | {m['value']} | {m['req']} | {m['status']} |")
section = f"""

## Stage 2 PostGIS

- OSM license: ODbL, © OpenStreetMap contributors
- RGZ license: open data from [data.gov.rs Adresni registar](https://data.gov.rs/sr/datasets/adresni-registar/) / GeoSrbija
- RGZ downloaded at: {rgz_date}
- RGZ layer: `kucni_broj` (EPSG:25834 -> EPSG:4326, clipped to relation 1649672)

| # | Metric | Value | Required | Status |
|---|--------|------:|----------|--------|
{chr(10).join(rows)}
"""
if "C5" in metrics:
    section += f"\n- Share of RGZ addresses linked to buildings (C5): {metrics['C5']['value']}\n"
readme_path.write_text(existing + section, encoding="utf-8")
PY

echo "Staging counts:"
docker compose exec -T postgis psql -U zylos -d zylos -t -A <<'SQL'
SELECT 'building_src=' || count(*) FROM osm_staging.building_src
UNION ALL SELECT 'address_src=' || count(*) FROM osm_staging.address_src
UNION ALL SELECT 'poi_src=' || count(*) FROM osm_staging.poi_src
UNION ALL SELECT 'kucni_broj_src=' || count(*) FROM rgz_staging.kucni_broj_src;
SQL

echo "Stage 2 pipeline finished."
