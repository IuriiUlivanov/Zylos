#!/usr/bin/env node
/**
 * Stage 14 API fixtures: POST /v1/route transit (R4/R7/R8).
 *
 *   node scripts/stage14-verify.mjs
 */

import { existsSync, readFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const apiBase = (process.env.ZYLOS_API_URL ?? "http://127.0.0.1:3000").replace(/\/$/, "");
const otpBase = (process.env.OTP_URL ?? "http://127.0.0.1:8082").replace(/\/$/, "");

const FR1 = { from: { lon: 19.845, lat: 45.255 }, to: { lon: 19.84, lat: 45.238 }, mode: "transit" };
const FR2 = { from: { lon: 19.862, lat: 45.252 }, to: { lon: 19.845, lat: 45.255 }, mode: "transit" };
const OUTSIDE = { from: { lon: 20.46, lat: 44.81 }, to: { lon: 19.845, lat: 45.255 }, mode: "transit" };

let failed = 0;

function record(id, ok, detail) {
  console.log(`${ok ? "OK" : "FAIL"}  ${id}  ${detail}`);
  if (!ok) failed += 1;
}

function need(rel) {
  const ok = existsSync(join(root, rel));
  record("file:" + rel, ok, ok ? "present" : "missing");
  return ok;
}

async function postRoute(body, timeoutMs = 8000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  const started = performance.now();
  try {
    const response = await fetch(`${apiBase}/v1/route`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify(body),
      signal: controller.signal,
    });
    const text = await response.text();
    let json = null;
    try {
      json = JSON.parse(text);
    } catch {
      json = null;
    }
    return { status: response.status, json, text, ms: performance.now() - started, bytes: Buffer.byteLength(text, "utf8") };
  } finally {
    clearTimeout(timer);
  }
}

function hasTransit(body) {
  const legs = body?.legs ?? [];
  return Array.isArray(legs) && legs.length >= 1 && legs.some((leg) => leg.mode === "transit");
}

async function main() {
  console.log("Stage 14 structural checks...");
  need("Docs/mobile/STAGE-14-android-transit.md");
  need("apps/api/src/types/route.ts");
  need("apps/api/src/routes/route.ts");
  need("apps/api/src/services/otpClient.ts");
  need("infra/otp/Dockerfile");
  need("infra/otp/router-config.json");
  need("apps/android/app/src/main/java/rs/zylos/app/data/api/RouteDto.kt");
  need("apps/android/app/src/main/java/rs/zylos/app/map/RouteLayers.kt");
  need("apps/android/app/src/main/java/rs/zylos/app/viewmodel/RouteLogic.kt");
  need("scripts/stage14-verify.ps1");
  need("scripts/stage14-verify.sh");

  const gtfsFiles = ["agency.txt", "routes.txt", "stops.txt", "stop_times.txt", "trips.txt", "calendar.txt"];
  for (const file of gtfsFiles) {
    need(`data/gtfs/jgsp/${file}`);
  }

  const apiSrc = readFileSync(join(root, "apps/api/src/services/otpClient.ts"), "utf8");
  record("R4-timeout", apiSrc.includes("6000"), "ROUTE_UPSTREAM_TIMEOUT_MS = 6000");
  const androidSrc = readFileSync(
    join(root, "apps/android/app/src/main/java/rs/zylos/app/map/MapDefaults.kt"),
    "utf8",
  );
  record("R4-client", androidSrc.includes("ROUTE_CLIENT_TIMEOUT_MS = 8_000"), "8s client timeout");
  const layers = readFileSync(
    join(root, "apps/android/app/src/main/java/rs/zylos/app/map/RouteLayers.kt"),
    "utf8",
  );
  record("M6-walk", layers.includes("route-walk"), "walk layer");
  record("M6-transit", layers.includes("route-transit"), "transit layer");
  record("M7-dash", layers.includes("lineDasharray") || layers.includes("ROUTE_DASH"), "walk dash");

  console.log(`\nStage 14 API checks (${apiBase})...`);
  const health = await fetch(`${apiBase}/v1/health`).catch(() => null);
  record("P4", health?.status === 200, health ? `HTTP ${health.status}` : "api down — docker compose up -d postgis otp api");
  if (health?.status !== 200) {
    process.exit(1);
  }

  let otpOk = false;
  try {
    const otp = await fetch(`${otpBase}/otp/actuators/health`, { signal: AbortSignal.timeout(3000) });
    otpOk = otp.ok;
    if (!otpOk) {
      const gql = await fetch(`${otpBase}/otp/routers/default/index/graphql`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ query: "{ __typename }" }),
        signal: AbortSignal.timeout(3000),
      });
      otpOk = gql.status < 500;
    }
  } catch {
    otpOk = false;
  }
  record("P3", otpOk, otpOk ? "otp reachable" : `otp not reachable at ${otpBase}`);

  await postRoute(OUTSIDE, 2000);
  const outside = await postRoute(OUTSIDE, 2000);
  record(
    "A4-R8",
    outside.status === 422 && outside.json?.error === "outside_city" && outside.ms <= 30,
    `HTTP ${outside.status} ${outside.ms.toFixed(1)}ms error=${outside.json?.error ?? "?"}`,
  );

  const r1 = await postRoute(FR1, 8000);
  record(
    "A1-R4",
    r1.status === 200 && hasTransit(r1.json) && (r1.json?.duration_sec ?? 0) > 0,
    `HTTP ${r1.status} legs=${r1.json?.legs?.length ?? 0} duration=${r1.json?.duration_sec ?? "?"}`,
  );
  record("A6-R7", r1.bytes <= 200_000, `${r1.bytes} bytes`);

  const r2 = await postRoute(FR2, 8000);
  record(
    "A2-R4",
    r2.status === 200 && (r2.json?.duration_sec ?? 0) > 0,
    `HTTP ${r2.status} duration=${r2.json?.duration_sec ?? "?"}`,
  );

  const r3 = await postRoute({ from: FR1.to, to: FR1.from, mode: "transit" }, 8000);
  const ratio =
    r1.json?.duration_sec && r3.json?.duration_sec
      ? r3.json.duration_sec / r1.json.duration_sec
      : 0;
  record(
    "A3",
    r3.status === 200 && ratio >= 0.7 && ratio <= 1.3,
    `HTTP ${r3.status} ratio=${ratio ? ratio.toFixed(2) : "?"}`,
  );

  if (r1.status === 200) {
    const samples = [];
    for (let i = 0; i < 12; i += 1) {
      const row = await postRoute(FR1, 8000);
      samples.push(row.ms);
    }
    const warm = samples.slice(2).sort((a, b) => a - b);
    const p95 = warm[Math.min(warm.length - 1, Math.ceil(0.95 * warm.length) - 1)];
    record("A5-R4", p95 <= 1500, `p95=${p95.toFixed(0)}ms n=${warm.length}`);
  }

  if (failed > 0) {
    console.error(`stage14-verify.mjs failed (${failed})`);
    process.exit(1);
  }
  console.log("stage14-verify.mjs OK");
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
