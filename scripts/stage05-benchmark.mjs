#!/usr/bin/env node
/**
 * Stage 5 performance gates B1–B7.
 *
 *   node scripts/stage05-benchmark.mjs
 */

import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import puppeteer from "puppeteer-core";
import {
  apiUrl,
  findBrowser,
  jumpTo,
  loadF6Fixture,
  parseTsv,
  percentile,
  psql,
  resolveWebUrl,
  root,
  waitForMap,
} from "./stage05-lib.mjs";

async function timedGet(path) {
  const started = performance.now();
  const response = await fetch(`${apiUrl}${path}`);
  const json = await response.json().catch(() => null);
  return { status: response.status, json, ms: performance.now() - started };
}

async function measure(path, samples, warmup, expectStatus = 200) {
  for (let i = 0; i < warmup; i += 1) {
    await timedGet(path);
  }
  const times = [];
  for (let i = 0; i < samples; i += 1) {
    const result = await timedGet(path);
    if (result.status !== expectStatus) {
      throw new Error(`${path} returned ${result.status}`);
    }
    times.push(result.ms);
  }
  return {
    p50: percentile(times, 50),
    p95: percentile(times, 95),
    n: samples,
    warmup,
  };
}

function updateReadme(report) {
  const readmePath = join(root, "data", "README.md");
  const existing = existsSync(readmePath) ? readFileSync(readmePath, "utf8") : "";
  const withoutStage5 = existing.split(/\r?\n## Stage 5(?:\b|$)/)[0].trimEnd();
  const fmt = (value) => (value == null || Number.isNaN(value) ? "n/a" : value.toFixed(1));
  const section = `

## Stage 5 building click

- Measured at: ${report.generatedAt}
- F6 building: \`${report.f6?.id ?? "n/a"}\` ${report.f6?.note ?? ""}
- B1 GET /v1/orgs/:id p95: ${fmt(report.b1?.p95)} ms (limit 80)
- B2 GET /v1/buildings/at p95: ${fmt(report.b2?.p95)} ms (limit 100)
- B3 GET /v1/buildings/:id p95: ${fmt(report.b3?.p95)} ms (limit 150)
- B4 click → org list p95: ${fmt(report.b4?.p95)} ms (limit 350)
- B5 GET /v1/orgs bbox p95: ${fmt(report.b5?.p95)} ms, max ${report.bboxCount ?? "n/a"} (limit 200 / 150 ms)
- B6 GeoJSON contour: ${report.geomBytes ?? "n/a"} B (limit 51200)
- B7 empty building: ${report.b7Ok ? "OK" : "FAIL"}
`;
  writeFileSync(readmePath, `${withoutStage5}${section}`.trimEnd() + "\n");
}

async function main() {
  mkdirSync(join(root, "data", "tmp"), { recursive: true });
  const f6 = await loadF6Fixture();
  if (!f6) {
    throw new Error("F6 fixture is required for stage05-benchmark");
  }

  console.log(`Benchmarking ${apiUrl} F6=${f6.id} ...`);
  const detail = await timedGet(`/v1/buildings/${f6.id}`);
  if (detail.status !== 200) {
    throw new Error(`/buildings/${f6.id} HTTP ${detail.status}`);
  }
  const geomBytes = Buffer.byteLength(JSON.stringify(detail.json.geometry ?? {}), "utf8");
  const orgId = detail.json.organizations?.[0]?.id || f6.orgId;
  if (!orgId) {
    throw new Error("F6 building has no organizations");
  }

  const b1 = await measure(`/v1/orgs/${encodeURIComponent(orgId)}`, 30, 5);
  const b2 = await measure(`/v1/buildings/at?lon=${f6.lon}&lat=${f6.lat}`, 30, 5);
  const b3 = await measure(`/v1/buildings/${f6.id}`, 30, 5);
  const bboxPath = "/v1/orgs?bbox=19.83,45.24,19.86,45.26";
  const bboxProbe = await timedGet(bboxPath);
  const bboxCount = Array.isArray(bboxProbe.json) ? bboxProbe.json.length : 0;
  const b5 = await measure(bboxPath, 30, 5);

  let emptyRow = null;
  try {
    emptyRow = parseTsv(
      psql(`
SELECT b.id::text,
       ST_X(ST_PointOnSurface(b.geom))::text,
       ST_Y(ST_PointOnSurface(b.geom))::text
FROM public.building b
WHERE NOT EXISTS (SELECT 1 FROM public.organization o WHERE o.building_id = b.id)
LIMIT 1;
`),
    )[0];
  } catch {
    emptyRow = null;
  }
  let b7Ok = false;
  if (emptyRow) {
    const emptyAt = await timedGet(`/v1/buildings/at?lon=${emptyRow[1]}&lat=${emptyRow[2]}`);
    const emptyId = emptyAt.status === 200 ? emptyAt.json.id : emptyRow[0];
    const emptyDetail = await timedGet(`/v1/buildings/${emptyId}`);
    b7Ok =
      emptyDetail.status === 200 &&
      Array.isArray(emptyDetail.json?.organizations) &&
      emptyDetail.json.organizations.length === 0;
  }

  const executablePath = findBrowser();
  if (!executablePath) {
    throw new Error("no Chrome/Edge found; set BROWSER_PATH");
  }
  const preview = await resolveWebUrl();
  const browser = await puppeteer.launch({
    executablePath,
    headless: true,
    args: [
      "--hide-scrollbars",
      "--window-size=1400,900",
      "--use-gl=angle",
      "--use-angle=swiftshader",
      "--enable-webgl",
      "--ignore-gpu-blocklist",
    ],
  });

  const clickTimes = [];
  try {
    const page = await browser.newPage();
    await page.setViewport({ width: 1400, height: 900, deviceScaleFactor: 1 });
    page.setDefaultTimeout(45000);
    await page.goto(`${preview.url}/?bldg=${encodeURIComponent(f6.id)}`, {
      waitUntil: "domcontentloaded",
      timeout: 60000,
    });
    await waitForMap(page);
    await page.waitForSelector("[data-testid=building-org]", { timeout: 8000 });
    await page.waitForFunction(() => typeof window.__zylosPickAt === "function", { timeout: 4000 });

    async function closeBuildingSheet() {
      await page.evaluate(() => document.querySelector("[data-testid=bottom-sheet] .bottom-sheet__close")?.click());
      await page.waitForFunction(
        () => window.__zylosApp?.sheet === "closed" && document.querySelectorAll("[data-testid=building-org]").length === 0,
        { timeout: 4000 },
      );
    }

    async function pickFixture() {
      await page.evaluate(async (lon, lat) => {
        if (window.__zylosPerf) {
          window.__zylosPerf.clickStartedAt = performance.now();
          window.__zylosPerf.buildingListAt = null;
        }
        await window.__zylosPickAt?.(lon, lat);
      }, f6.lon, f6.lat);
      await page.waitForFunction(
        () =>
          Boolean(
            window.__zylosPerf?.clickStartedAt &&
              window.__zylosPerf?.buildingListAt &&
              window.__zylosPerf.buildingListAt >= window.__zylosPerf.clickStartedAt &&
              document.querySelectorAll("[data-testid=building-org]").length >= 2,
          ),
        { timeout: 4000, polling: 16 },
      );
      return page.evaluate(
        () => (window.__zylosPerf?.buildingListAt ?? 0) - (window.__zylosPerf?.clickStartedAt ?? 0),
      );
    }

    for (let i = 0; i < 12; i += 1) {
      await closeBuildingSheet();
      await pickFixture();
    }
    for (let i = 0; i < 30; i += 1) {
      await closeBuildingSheet();
      clickTimes.push(await pickFixture());
    }
  } finally {
    await browser.close();
    if (preview.proc) {
      preview.proc.kill();
    }
  }

  const b4 = { p50: percentile(clickTimes, 50), p95: percentile(clickTimes, 95), n: clickTimes.length };
  console.log("B4 samples", [...clickTimes].sort((a, b) => a - b).map((ms) => ms.toFixed(1)).join(", "));

  const report = {
    generatedAt: new Date().toISOString(),
    f6,
    b1,
    b2,
    b3,
    b4,
    b5,
    bboxCount,
    geomBytes,
    b7Ok,
  };

  const gates = [
    ["B1", b1.p95, 80],
    ["B2", b2.p95, 100],
    ["B3", b3.p95, 150],
    ["B4", b4.p95, 350],
    ["B5", b5.p95, 150],
    ["B6", geomBytes, 51200],
  ];
  const failed = [];
  for (const [id, value, limit] of gates) {
    const ok = value <= limit;
    console.log(`${ok ? "OK" : "FAIL"} ${id} ${value.toFixed(1)} (limit ${limit})`);
    if (!ok) {
      failed.push(id);
    }
  }
  console.log(`${b7Ok ? "OK" : "FAIL"} B7 empty building`);
  if (!b7Ok) {
    failed.push("B7");
  }
  if (bboxCount > 200) {
    console.log(`FAIL B5 count ${bboxCount} (limit 200)`);
    failed.push("B5-count");
  }

  writeFileSync(join(root, "data", "tmp", "stage05-benchmark.json"), JSON.stringify(report, null, 2) + "\n");
  updateReadme(report);

  if (failed.length) {
    throw new Error(`stage05-benchmark failed: ${failed.join(", ")}`);
  }
  console.log("stage05-benchmark passed.");
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
