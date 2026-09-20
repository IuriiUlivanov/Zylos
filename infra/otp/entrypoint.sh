#!/bin/sh
set -eu

DATA="${OTP_DATA:-/var/opentripplanner}"
GTFS_DIR="${GTFS_DIR:-/gtfs/jgsp}"
mkdir -p "$DATA"

cp -f /otp-config-src/build-config.json "$DATA/build-config.json"
cp -f /otp-config-src/router-config.json "$DATA/router-config.json"
cp -f /otp-config-src/otp-config.json "$DATA/otp-config.json"

if [ -d "$GTFS_DIR" ]; then
  existing=$(ls "$GTFS_DIR"/*gtfs*.zip 2>/dev/null | head -n 1 || true)
  if [ -n "$existing" ]; then
    cp -f "$existing" "$DATA/jgsp-gtfs.zip"
  elif [ -f "$GTFS_DIR/agency.txt" ]; then
    (cd "$GTFS_DIR" && zip -q -r "$DATA/jgsp-gtfs.zip" .)
  fi
fi

if [ ! -f /otp/otp-shaded.jar ]; then
  echo "OTP jar missing" >&2
  ls -la /otp >&2 || true
  exit 1
fi

if [ ! -f "$DATA/graph.obj" ]; then
  echo "OTP: building graph (first start, up to ~90s)..."
  java -jar /otp/otp-shaded.jar --build --save "$DATA"
fi

echo "OTP: loading graph..."
exec java -jar /otp/otp-shaded.jar --load --serve "$DATA"
