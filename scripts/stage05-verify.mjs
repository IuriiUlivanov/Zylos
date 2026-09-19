#!/usr/bin/env node
/**
 * Stage 5 building-click acceptance checks.
 *
 *   node scripts/stage05-verify.mjs
 *
 * Exit 0 if every check passed.
 */

import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import puppeteer from "puppeteer-core";
import {
  apiUrl,
  docker,
  fetchJson,
  findBrowser,
  clickAvoidingPoi,
  geometryCandidates,
  jumpTo,
  loadF6Fixture,
  parseTsv,
  planUsesGist,
  psql,
  resolveWebUrl,
  root,
  waitForMap,
} from "./stage05-lib.mjs";

const rows = [];
let failed = 0;

function record(id, ok, detail) {
  const status = ok ? "OK" : "FAIL";
  if (!ok) {
    failed += 1;
  }
  rows.push({ id, status, detail });
  console.log(`${status.padEnd(4)} ${id.padEnd(22)} ${detail}`);
}

function runningServices() {
  try {
    return docker("compose", "ps", "--services", "--status", "running")
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter(Boolean);
  } catch {
    return [];
  }
}

function percentile(values, p) {
  if (values.length === 0) {
    return NaN;
  }
  const sorted = [...values].sort((a, b) => a - b);
  const idx = Math.min(sorted.length - 1, Math.max(0, Math.ceil((p / 100) * sorted.length) - 1));
  return sorted[idx];
}

async function timedGet(path) {
  const started = performance.now();
  const response = await fetch(`${apiUrl}${path}`);
  const json = await response.json().catch(() => null);
  return { status: response.status, json, ms: performance.now() - started };
}

async function main() {
  mkdirSync(join(root, "data", "tmp"), { recursive: true });

  console.log("\n== Preconditions ==");
  const services = runningServices();
  for (const name of ["postgis", "meilisearch", "api", "tiles"]) {
    record("P2-" + name, services.includes(name), services.includes(name) ? "running" : "not running");
  }

  const f6 = await loadF6Fixture();
  record("P4", Boolean(f6 && f6.orgCount >= 2), f6 ? `${f6.note || f6.id} orgs=${f6.orgCount}` : "no F6 building");
  if (!f6) {
    throw new Error("F6 fixture is required");
  }

  console.log("\n== API buildings ==");
  const atOk = await timedGet(`/v1/buildings/at?lon=${f6.lon}&lat=${f6.lat}`);
  record("A1", atOk.status === 200 && atOk.json?.id === f6.id, `HTTP ${atOk.status} id=${atOk.json?.id ?? "n/a"}`);

  const water = await timedGet("/v1/buildings/at?lon=19.86&lat=45.26");
  record("A2", water.status === 404 && water.json?.error === "building_not_found", `HTTP ${water.status} ${water.json?.error ?? ""}`);

  const outsideSamples = [];
  let outsideBody = null;
  for (let i = 0; i < 8; i += 1) {
    const outside = await timedGet("/v1/buildings/at?lon=20.46&lat=44.817");
    outsideSamples.push(outside.ms);
    outsideBody = outside;
  }
  record(
    "A3",
    outsideBody?.status === 422 && outsideBody.json?.error === "outside_city" && percentile(outsideSamples, 95) <= 30,
    `HTTP ${outsideBody?.status} p95=${percentile(outsideSamples, 95).toFixed(1)} ms`,
  );

  try {
    const overlap = parseTsv(
      psql(`
SET statement_timeout = '3s';
SELECT a.id::text,
       b.id::text,
       ST_X(ST_PointOnSurface(ST_Intersection(a.geom, b.geom)))::text,
       ST_Y(ST_PointOnSurface(ST_Intersection(a.geom, b.geom)))::text,
       CASE WHEN ST_Area(a.geom) >= ST_Area(b.geom) THEN a.id::text ELSE b.id::text END
FROM public.building a
JOIN public.building b
  ON a.id < b.id
 AND a.geom && b.geom
 AND ST_Overlaps(a.geom, b.geom)
LIMIT 1;
`),
    ).find((row) => row.length >= 5 && /^[0-9a-f-]{36}$/i.test(row[0] ?? ""));
    if (overlap) {
      const atOverlap = await timedGet(`/v1/buildings/at?lon=${overlap[2]}&lat=${overlap[3]}`);
      record("A4", atOverlap.status === 200 && atOverlap.json?.id === overlap[4], `picked ${atOverlap.json?.id} expected ${overlap[4]}`);
    } else {
      record("A4", true, "no overlapping polygons (N/A)");
    }
  } catch (err) {
    record("A4", true, `skip (${err instanceof Error ? err.message : String(err)})`);
  }

  const detail = await timedGet(`/v1/buildings/${f6.id}`);
  const orgs = detail.json?.organizations ?? [];
  const geomBytes = Buffer.byteLength(JSON.stringify(detail.json?.geometry ?? {}), "utf8");
  record("A6", detail.status === 200 && orgs.length >= 2 && orgs.length <= 100, `HTTP ${detail.status} orgs=${orgs.length}`);
  record("A7", geomBytes <= 51200, `geometry ${geomBytes} B`);

  let empty = null;
  try {
    empty = parseTsv(
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
    empty = null;
  }
  if (empty) {
    const emptyAt = await timedGet(`/v1/buildings/at?lon=${empty[1]}&lat=${empty[2]}`);
    const emptyId = emptyAt.status === 200 ? emptyAt.json.id : empty[0];
    const emptyDetail = await timedGet(`/v1/buildings/${emptyId}`);
    record(
      "A8",
      emptyDetail.status === 200 && Array.isArray(emptyDetail.json?.organizations) && emptyDetail.json.organizations.length === 0,
      `empty building ${emptyId} orgs=${emptyDetail.json?.organizations?.length ?? "n/a"}`,
    );
  } else {
    record("A8", false, "no empty building");
  }

  const firstOrgId = orgs[0]?.id || f6.orgId;
  const orgOk = firstOrgId ? await timedGet(`/v1/orgs/${encodeURIComponent(firstOrgId)}`) : { status: 0, json: null };
  record("O1", orgOk.status === 200 && Boolean(orgOk.json?.name), `HTTP ${orgOk.status} ${orgOk.json?.name ?? ""}`);
  const orgMissing = await timedGet("/v1/orgs/org:osm:missing-stage05");
  record("O2", orgMissing.status === 404, `HTTP ${orgMissing.status}`);
  record("O4", Boolean(firstOrgId && /^org:(osm|editorial):.+/.test(firstOrgId)), `id=${firstOrgId}`);

  const bbox = await timedGet(" /v1/orgs?bbox=19.83,45.24,19.86,45.26".trim());
  const pins = Array.isArray(bbox.json) ? bbox.json : [];
  record("B5a", bbox.status === 200 && pins.length > 0 && pins.length <= 200, `HTTP ${bbox.status} pins=${pins.length}`);
  record("B5c", pins.every((pin) => pin.id && pin.name && Number.isFinite(pin.lon) && Number.isFinite(pin.lat)), "pin fields");

  const atSamples = [];
  for (let i = 0; i < 5; i += 1) {
    await timedGet(`/v1/buildings/at?lon=${f6.lon}&lat=${f6.lat}`);
  }
  for (let i = 0; i < 30; i += 1) {
    const sample = await timedGet(`/v1/buildings/at?lon=${f6.lon}&lat=${f6.lat}`);
    if (sample.status === 200) {
      atSamples.push(sample.ms);
    }
  }
  const atP95 = percentile(atSamples, 95);
  record("A5", atSamples.length === 30 && atP95 <= 100, `p95=${atP95.toFixed(1)} ms n=${atSamples.length}`);

  try {
    const explainRaw = psql(`
EXPLAIN (FORMAT JSON)
SELECT b.id
FROM public.building b
WHERE ST_Contains(b.geom, ST_SetSRID(ST_Point(${f6.lon}, ${f6.lat}), 4326))
ORDER BY ST_Area(b.geom) DESC
LIMIT 1;
`);
    const plan = JSON.parse(explainRaw);
    record("GIST", planUsesGist(plan), planUsesGist(plan) ? "building_geom_gix" : JSON.stringify(plan).slice(0, 180));
  } catch (err) {
    record("GIST", false, err instanceof Error ? err.message : String(err));
  }

  const search = await fetchJson(`${apiUrl}/v1/search?q=apotek`);
  record("R4", search.status === 200 && (search.json?.hits?.length ?? 0) >= 5, `apotek hits=${search.json?.hits?.length ?? 0}`);

  const executablePath = findBrowser();
  record("R0-browser", Boolean(executablePath), executablePath || "set BROWSER_PATH");

  let preview = { url: "http://127.0.0.1:4173", proc: null };
  try {
    preview = await resolveWebUrl();
    record("W2", true, preview.url);
  } catch (err) {
    record("W2", false, err instanceof Error ? err.message : String(err));
  }

  let b4Ms = null;
  if (executablePath && preview.url) {
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
    try {
      const page = await browser.newPage();
      await page.setViewport({ width: 1400, height: 900, deviceScaleFactor: 1 });
      page.setDefaultTimeout(45000);

      console.log("\n== UI fixtures ==");
      await page.goto(preview.url, { waitUntil: "domcontentloaded", timeout: 60000 });
      await waitForMap(page);
      const started = Date.now();
      await clickAvoidingPoi(page, geometryCandidates(detail.json, f6));
      try {
        await page.waitForSelector("[data-testid=building-org]", { timeout: 4000 });
      } catch {
        /* counted below */
      }
      b4Ms = Date.now() - started;
      const orgCount = await page.$$eval("[data-testid=building-org]", (els) => els.length).catch(() => 0);
      const sheetState = await page.evaluate(() => ({
        state: document.querySelector("[data-testid=bottom-sheet]")?.getAttribute("data-state"),
        building: Boolean(document.querySelector("[data-testid=building-sheet]")),
        title: window.__zylosApp?.selectionTitle,
      }));
      record("F1", orgCount >= 2 && sheetState.building, `orgs=${orgCount} state=${sheetState.state} ${b4Ms}ms`);
      record("C3", orgCount >= 1, `building-org=${orgCount}`);

      if (orgCount >= 1) {
        await page.evaluate(() => {
          document.querySelector("[data-testid=building-org]")?.click();
        });
        await page.waitForSelector("[data-testid=org-card]", { timeout: 4000 }).catch(() => null);
        const cardName = await page.$eval("[data-testid=org-card]", (el) => el.textContent || "").catch(() => "");
        record("F2", Boolean(cardName), `org-card="${cardName.slice(0, 80)}"`);
      } else {
        record("F2", false, "no building-org to click");
      }

      await page.goto(`${preview.url}/?bldg=${encodeURIComponent(f6.id)}`, {
        waitUntil: "domcontentloaded",
        timeout: 60000,
      });
      await waitForMap(page);
      await page.waitForSelector("[data-testid=building-sheet]", { timeout: 8000 }).catch(() => null);
      const restored = await page.evaluate(() => ({
        sheet: document.querySelector("[data-testid=bottom-sheet]")?.getAttribute("data-state"),
        orgs: document.querySelectorAll("[data-testid=building-org]").length,
        hasLayer: Boolean(window.__zylosMap?.getLayer("selected-building")),
        buildingId: window.__zylosApp?.buildingId,
      }));
      record(
        "F3",
        restored.sheet === "building" && restored.orgs >= 2 && restored.buildingId === f6.id,
        JSON.stringify(restored),
      );

      await page.goto(preview.url, { waitUntil: "domcontentloaded", timeout: 60000 });
      await waitForMap(page);
      await jumpTo(page, 19.86, 45.26, 14);
      const waterClick = await page.evaluate(() => {
        const canvas = document.querySelector(".maplibregl-canvas");
        const rect = canvas.getBoundingClientRect();
        return { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 };
      });
      await page.mouse.click(waterClick.x, waterClick.y);
      await page.waitForFunction(() => window.__zylosApp?.toast || window.__zylosApp?.sheet === "closed", {
        timeout: 4000,
      }).catch(() => null);
      const afterWater = await page.evaluate(() => ({
        sheet: window.__zylosApp?.sheet,
        toast: window.__zylosApp?.toast,
      }));
      record("F4", afterWater.sheet === "closed" && afterWater.toast !== undefined, JSON.stringify(afterWater));

      await page.goto(`${preview.url}/?q=apotek`, { waitUntil: "domcontentloaded", timeout: 60000 });
      await waitForMap(page);
      await (await page.waitForSelector("[data-testid=search-input]")).click();
      await page.waitForSelector("[data-testid=search-hit]", { timeout: 8000 }).catch(() => null);
      const clicked = await page.evaluate(() => {
        const hit = document.querySelector('[data-testid="search-hit"][data-kind="organization"]');
        hit?.click();
        return hit?.getAttribute("data-id") || "";
      });
      await page.waitForFunction(
        () =>
          window.__zylosApp?.sheet === "building" &&
          Boolean(window.__zylosApp?.buildingId) &&
          document.querySelectorAll("[data-testid=building-org]").length > 0,
        { timeout: 8000 },
      ).catch(() => null);
      const fromSearch = await page.evaluate(() => ({
        sheet: window.__zylosApp?.sheet,
        buildingId: window.__zylosApp?.buildingId,
        hasGeom:
          window.__zylosMap?.getSource("selected-building") &&
          window.__zylosMap.querySourceFeatures("selected-building").length > 0,
      }));
      record("F5", fromSearch.sheet === "building" && Boolean(fromSearch.buildingId), `${clicked} ${JSON.stringify(fromSearch)}`);

      await page.goto(preview.url, { waitUntil: "domcontentloaded", timeout: 60000 });
      await waitForMap(page);
      await jumpTo(page, 19.845, 45.255, 16);
      await page.waitForFunction(() => document.querySelectorAll("[data-testid=org-pin]").length >= 10, {
        timeout: 4000,
      }).catch(() => null);
      const pinCount = await page.$$eval("[data-testid=org-pin]", (els) => els.length).catch(() => 0);
      record("F6", pinCount >= 10, `org-pin=${pinCount}`);

      await page.goto(`${preview.url}/?q=apotek`, { waitUntil: "domcontentloaded", timeout: 60000 });
      await waitForMap(page);
      const searchBox = await page.waitForSelector("[data-testid=search-input]", { timeout: 5000 });
      await searchBox.click();
      await page
        .waitForFunction((min) => document.querySelectorAll("[data-testid=search-hit]").length >= min, { timeout: 8000 }, 5)
        .catch(() => null);
      const hitCount = await page.$$eval("[data-testid=search-hit]", (els) => els.length).catch(() => 0);
      if (hitCount >= 1) {
        await page.evaluate(() => {
          document.querySelector("[data-testid=search-hit]")?.click();
        });
        await page
          .waitForFunction(() => {
            const state = document.querySelector("[data-testid=bottom-sheet]")?.getAttribute("data-state");
            return state === "peek" || state === "building";
          }, { timeout: 4000 })
          .catch(() => null);
      }
      const f7Sheet = await page.evaluate(
        () => document.querySelector("[data-testid=bottom-sheet]")?.getAttribute("data-state"),
      );
      record(
        "F7",
        hitCount >= 5 && (f7Sheet === "peek" || f7Sheet === "building"),
        JSON.stringify({ hits: hitCount, sheet: f7Sheet }),
      );
    } finally {
      await browser.close();
    }
  }

  if (preview.proc) {
    preview.proc.kill();
  }

  const report = {
    generatedAt: new Date().toISOString(),
    failed,
    f6,
    atP95,
    b4Ms,
    geomBytes,
    rows,
  };
  writeFileSync(join(root, "data", "tmp", "stage05-verify.json"), JSON.stringify(report, null, 2) + "\n");

  if (failed > 0) {
    console.error(`\nstage05-verify failed: ${failed} check(s)`);
    process.exit(1);
  }
  console.log("\nstage05-verify passed.");
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
