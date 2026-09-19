#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
RGZ_DIR="$ROOT/data/rgz"
ARCHIVE="$RGZ_DIR/kucni_br_gpkg.gpkg"
GPKG="$RGZ_DIR/kucni_broj.gpkg"
STAMP="$RGZ_DIR/downloaded-at.txt"
URL="https://download.geosrbija.rs/download-api/opendata-proxy/export?category=ar&layer=kucni_broj_ar&geometry=true&fileName=kucni_br_gpkg&format=gpkg"
FORCE=0

if [ "${1:-}" = "--force" ] || [ "${1:-}" = "-f" ]; then
  FORCE=1
elif [ -n "${1:-}" ]; then
  echo "Usage: $0 [--force]" >&2
  exit 2
fi

mkdir -p "$RGZ_DIR"

is_gpkg() {
  file=$1
  if [ ! -s "$file" ]; then
    return 1
  fi
  if head -c 16 "$file" | grep -q 'SQLite format 3'; then
    return 0
  fi
  return 1
}

is_zip_or_gpkg_download() {
  file=$1
  if [ ! -s "$file" ]; then
    return 1
  fi
  if head -c 16 "$file" | grep -q 'SQLite format 3'; then
    return 0
  fi
  if head -c 2 "$file" | grep -q 'PK'; then
    return 0
  fi
  if head -c 64 "$file" | grep -qi '<html'; then
    return 1
  fi
  return 1
}

extract_if_zip() {
  if head -c 2 "$ARCHIVE" | grep -q 'PK'; then
    echo "Extracting GeoPackage from downloaded ZIP archive..."
    rm -f "$GPKG"
    if command -v unzip >/dev/null 2>&1; then
      unzip -o "$ARCHIVE" -d "$RGZ_DIR"
    else
      docker run --rm \
        --mount "type=bind,source=$RGZ_DIR,target=/rgz" \
        alpine sh -c "apk add --no-cache unzip >/dev/null && unzip -o /rgz/kucni_br_gpkg.gpkg -d /rgz"
    fi
    if [ ! -f "$GPKG" ]; then
      echo "ZIP archive did not contain kucni_broj.gpkg" >&2
      exit 1
    fi
  elif [ ! -f "$GPKG" ]; then
    cp "$ARCHIVE" "$GPKG"
  fi
}

if [ "$FORCE" -eq 0 ] && [ -f "$GPKG" ] && is_gpkg "$GPKG"; then
  echo "RGZ GeoPackage already present: $GPKG"
  exit 0
fi

if [ "$FORCE" -eq 1 ]; then
  rm -f "$ARCHIVE" "$GPKG"
fi

echo "Downloading RGZ kućni brojevi (resume enabled)..."
echo "URL: $URL"
curl --fail --location --retry 5 --retry-delay 3 --continue-at - \
  --output "$ARCHIVE" "$URL"

if ! is_zip_or_gpkg_download "$ARCHIVE"; then
  echo "Downloaded file is not a GeoPackage or ZIP wrapper (HTML error page?)" >&2
  head -c 256 "$ARCHIVE" >&2 || true
  echo >&2
  exit 1
fi

extract_if_zip

if ! is_gpkg "$GPKG"; then
  echo "Extracted file is not a valid GeoPackage: $GPKG" >&2
  exit 1
fi

date -u "+%Y-%m-%dT%H:%M:%SZ" > "$STAMP"
echo "RGZ download complete: $GPKG ($(wc -c < "$GPKG" | tr -d ' ') bytes)"
