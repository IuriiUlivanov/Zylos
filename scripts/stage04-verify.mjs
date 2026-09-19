#!/usr/bin/env node
/**
 * Stage 4 web map acceptance checks.
 *
 *   node scripts/stage04-verify.mjs
 *   scripts/stage04-verify.ps1
 *
 * Exit 0 if every check passed.
 */

import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import puppeteer from "puppeteer-core";
import {
  apiUrl,
  docker,
  findBrowser,
  gzipBundle,
  npmRun,
  resolveWebUrl,
  root,
  scanDistSecrets,
  tilesUrl,
  webDir,
} from "./stage04-lib.mjs";

const distDir = join(webDir, "dist");
const rows = [];
let failed = 0;
const notices = [];

function record(id, ok, detail) {
  const status = ok ? "OK" : "FAIL";
  if (!ok) {
    failed += 1;
  }
  rows.push({ id, status, detail });
  console.log(`${status.padEnd(4)} ${id.padEnd(22)} ${detail}`);
}

async function fetchJson(url, headers = {}) {
  const started = performance.now();
  const response = await fetch(url, { headers });
  const json = await response.json().catch(() => null);
  return { status: response.status, json, ms: performance.now() - started, headers: response.headers };
}

async function fetchHead(url, headers = {}) {
  const response = await fetch(url, { method: "GET", headers, redirect: "manual" });
  return { status: response.status, headers: response.headers };
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

async function waitForMap(page, timeout = 45000) {
  await page.waitForFunction(() => Boolean(window.__zylosMap), { timeout });
  await page.evaluate(
    () =>
      new Promise((resolve, reject) => {
        const map = window.__zylosMap;
        const timer = setTimeout(() => reject(new Error("map idle timeout")), 30000);
        const done = () => {
          clearTimeout(timer);
          resolve();
        };
        if (map.loaded() && map.areTilesLoaded()) {
          map.once("idle", done);
          return;
        }
        map.once("idle", done);
      }),
  );
}

async function inspectCenter(page, lon, lat, zoom) {
  return page.evaluate(
    async ({ lon: targetLon, lat: targetLat, zoom: targetZoom }) => {
      const map = window.__zylosMap;
      await new Promise((resolve) => {
        map.once("idle", resolve);
        map.jumpTo({ center: [targetLon, targetLat], zoom: targetZoom });
      });
      const layerIds = ["buildings", "roads", "water-fill", "water", "poi-dot", "poi-label"];
      const rendered = {};
      for (const id of layerIds) {
        rendered[id] = map.getLayer(id) ? map.queryRenderedFeatures({ layers: [id] }).length : -1;
      }
      const source = map.getSource("noviSad");
      return {
        zoom: map.getZoom(),
        center: map.getCenter(),
        rendered,
        error: window.__zylosMapError,
        attribution: document.querySelector(".maplibregl-ctrl-attrib")?.textContent || "",
        maxzoom: source?.maxzoom ?? source?._options?.maxzoom ?? null,
        sourceId: Boolean(source),
      };
    },
    { lon, lat, zoom },
  );
}

function loadPoiFixtures() {
  try {
    const raw = docker(
      "compose",
      "exec",
      "-T",
      "postgis",
      "sh",
      "-c",
      "psql -U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\" -t -A -F '\t' -c \"SELECT f.source_id, o.source, o.name FROM stage02_poi_fixture f JOIN organization o ON o.source_id = f.source_id ORDER BY f.source_id;\"",
    );
    return raw
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter(Boolean)
      .map((line) => {
        const [source_id, source, ...nameParts] = line.split("\t");
        return { source_id, source, name: nameParts.join("\t"), id: `org:${source}:${source_id}` };
      });
  } catch (err) {
    console.error(err);
    return [];
  }
}

async function typeQuery(page, value) {
  const input = await page.waitForSelector("[data-testid=search-input]");
  await input.click({ clickCount: 3 });
  await page.keyboard.press("Backspace");
  if (value) {
    await input.type(value, { delay: 0 });
  }
}

async function waitForHits(page, minCount, timeout = 8000) {
  await page.waitForFunction(
    (min) => document.querySelectorAll("[data-testid=search-hit]").length >= min,
    { timeout },
    minCount,
  );
}

async function main() {
  mkdirSync(join(root, "data", "tmp"), { recursive: true });

  console.log("\n== Preconditions ==");
  const services = runningServices();
  for (const name of ["postgis", "meilisearch", "api", "tiles"]) {
    record("P2-" + name, services.includes(name), services.includes(name) ? "running" : `not running (${services.join(", ") || "none"})`);
  }

  const range = await fetchHead(`${tilesUrl}/tiles/novi-sad.pmtiles`, { Range: "bytes=0-16383" });
  record("P3", range.status === 206, `PMTiles Range HTTP ${range.status}`);

  const search = await fetchJson(`${apiUrl}/v1/search?q=apotek`);
  const searchHits = search.json?.hits ?? [];
  record("P4", search.status === 200 && searchHits.length >= 5, `apotek hits=${searchHits.length}`);

  console.log("\n== Build ==");
  try {
    if (process.env.SKIP_WEB_BUILD === "1" && existsSync(join(distDir, "index.html"))) {
      record("W1", true, "dist/index.html (SKIP_WEB_BUILD)");
    } else {
      if (!existsSync(join(webDir, "node_modules"))) {
        npmRun(["ci"], webDir);
      }
      npmRun(["run", "build"], webDir);
      record("W1", existsSync(join(distDir, "index.html")), "dist/index.html");
    }
  } catch (err) {
    record("W1", false, err instanceof Error ? err.message : String(err));
  }

  const envExample = existsSync(join(root, ".env.example"))
    ? readFileSync(join(root, ".env.example"), "utf8")
    : "";
  record(
    "W3",
    envExample.includes("VITE_API_URL") && envExample.includes("VITE_TILES_URL"),
    "VITE_API_URL and VITE_TILES_URL in .env.example",
  );

  const secrets = scanDistSecrets(distDir);
  record("W4", secrets.length === 0, secrets.length ? secrets.join(", ") : "no secrets in dist");

  const bundle = gzipBundle(distDir);
  record(
    "L1",
    bundle.withMaplibre <= 900 * 1024,
    `gzip JS+CSS ${Math.round(bundle.withMaplibre / 1024)} KB (limit 900), without maplibre ${Math.round(bundle.withoutMaplibre / 1024)} KB`,
  );

  const debounceSrc = readFileSync(join(webDir, "src", "lib", "constants.ts"), "utf8");
  record("S1-debounce", /SEARCH_DEBOUNCE_MS\s*=\s*150/.test(debounceSrc), "SEARCH_DEBOUNCE_MS = 150");
  record("S2-minlen", /SEARCH_MIN_LENGTH\s*=\s*2/.test(debounceSrc), "SEARCH_MIN_LENGTH = 2");

  const executablePath = findBrowser();
  if (!executablePath) {
    record("R0-browser", false, "no Chrome/Edge found; set BROWSER_PATH");
  } else {
    record("R0-browser", true, executablePath);
  }

  let preview = { url: "http://127.0.0.1:4173", proc: null };
  try {
    preview = await resolveWebUrl();
  } catch (err) {
    record("W2", false, err instanceof Error ? err.message : String(err));
  }
  if (preview.url) {
    record("W2", true, preview.url);
  }

  const tileRequests = [];
  let orgRequests = 0;

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
      page.on("response", (response) => {
        const url = response.url();
        if (url.includes("/mvt/") || url.includes(".pmtiles") || url.includes("water-fill")) {
          tileRequests.push({
            url,
            status: response.status(),
            range: response.request().headers().range || response.headers()["content-range"] || "",
          });
        }
        if (/\/v1\/orgs\//.test(url)) {
          orgRequests += 1;
        }
      });

      console.log("\n== UI fixtures ==");
      await page.goto(preview.url, { waitUntil: "domcontentloaded", timeout: 60000 });
      await waitForMap(page);
      const searchInput = await page.$("#search, [data-testid=search-input]");
      record("F1", Boolean(searchInput) && Boolean(await page.$("[data-testid=map]")), "map + #search");

      const center = await inspectCenter(page, 19.845, 45.255, 15);
      record("M1", center.rendered.buildings > 0, `z15 buildings=${center.rendered.buildings}`);
      record("M2", center.rendered.roads > 0, `z15 roads=${center.rendered.roads}`);
      record("M3", /openstreetmap/i.test(center.attribution), center.attribution.trim() || "attribution empty");
      record("F5", center.rendered.buildings > 0, `z15 center buildings=${center.rendered.buildings}`);
      record("L3", !/login|signin|войти|prijava/i.test(await page.content()), "no login screen");

      const styleMeta = await page.evaluate(() => {
        const map = window.__zylosMap;
        const source = map.getSource("noviSad");
        const style = map.getStyle();
        return {
          hasNoviSad: Boolean(source),
          maxzoom: source?.maxzoom ?? null,
          name: style?.name || "",
          glyphs: style?.glyphs || "",
        };
      });
      record(
        "M5",
        styleMeta.hasNoviSad && (styleMeta.maxzoom == null || styleMeta.maxzoom === 14) && !/protomaps/i.test(styleMeta.name),
        `source noviSad maxzoom=${styleMeta.maxzoom} name=${styleMeta.name || "(unnamed)"}`,
      );

      const danube = await inspectCenter(page, 19.86, 45.26, 14);
      const waterRendered = Math.max(danube.rendered["water-fill"] || 0, danube.rendered.water || 0);
      record("M4", waterRendered > 0, `z14 Danube water-fill=${danube.rendered["water-fill"]} water=${danube.rendered.water}`);

      await inspectCenter(page, 19.845, 45.255, 14);

      const searchCalls = [];
      await page.setRequestInterception(true);
      const onRequest = (request) => {
        if (request.url().includes("/v1/search")) {
          searchCalls.push(request.url());
        }
        if (!request.isInterceptResolutionHandled()) {
          request.continue();
        }
      };
      page.on("request", onRequest);

      await typeQuery(page, "a");
      await new Promise((resolve) => setTimeout(resolve, 350));
      const shortHits = await page.$$("[data-testid=search-hit]");
      record("S2", shortHits.length === 0 && searchCalls.length === 0, `q=a hits=${shortHits.length} apiCalls=${searchCalls.length}`);

      searchCalls.length = 0;
      await typeQuery(page, "");
      const searchField = await page.$("[data-testid=search-input]");
      await searchField.type("apo", { delay: 8 });
      await new Promise((resolve) => setTimeout(resolve, 20));
      await searchField.type("tek", { delay: 8 });
      try {
        await waitForHits(page, 5, 8000);
      } catch {
        /* counted below */
      }
      const staleQuery = await page.$eval("[data-testid=search-dropdown]", (el) => el.getAttribute("data-query")).catch(() => "");
      record("S3", staleQuery === "apotek", `dropdown data-query=${staleQuery}`);

      const apotekHits = await page.$$eval("[data-testid=search-hit]", (els) =>
        els.map((el) => ({
          id: el.getAttribute("data-id"),
          kind: el.getAttribute("data-kind"),
          lat: Number(el.getAttribute("data-lat")),
          lon: Number(el.getAttribute("data-lon")),
          text: (el.textContent || "").trim(),
        })),
      );
      const pharmacyText = apotekHits.some((hit) => /apotek|pharmacy|аптек/i.test(hit.text));
      record("F2", apotekHits.length >= 5, `apotek dropdown=${apotekHits.length}`);
      record("S4", apotekHits.length >= 5 && pharmacyText, `pharmacy text=${pharmacyText}`);

      const firstOrg = apotekHits.find((hit) => hit.kind === "organization") ?? apotekHits[0];
      if (firstOrg) {
        await page.evaluate((id) => {
          document.querySelector(`[data-testid="search-hit"][data-id="${id}"]`)?.click();
        }, firstOrg.id);
        await page.waitForSelector(
          '[data-testid=bottom-sheet][data-state="peek"], [data-testid=bottom-sheet][data-state="building"]',
          { timeout: 4000 },
        );
        const sheetTitle = await page.evaluate(() => window.__zylosApp?.selectionTitle || "");
        record("F3", Boolean(sheetTitle), `sheet title="${sheetTitle}"`);
        record(
          "S6",
          /is-open/.test(await page.$eval("[data-testid=bottom-sheet]", (el) => el.className)) &&
            Boolean(sheetTitle),
          `sheet title="${sheetTitle}"`,
        );

        await page.evaluate(
          () =>
            new Promise((resolve) => {
              const map = window.__zylosMap;
              const timer = setTimeout(resolve, 1500);
              map.once("moveend", () => {
                clearTimeout(timer);
                resolve();
              });
            }),
        );
        const afterFly = await page.evaluate(() => {
          const map = window.__zylosMap;
          const center = map.getCenter();
          const marker = map.getSource("selected-marker");
          return { lng: center.lng, lat: center.lat, hasMarker: Boolean(marker) };
        });
        const near =
          Math.abs(afterFly.lng - firstOrg.lon) <= 0.01 && Math.abs(afterFly.lat - firstOrg.lat) <= 0.01;
        record("S5", near && afterFly.hasMarker, `center=${afterFly.lng.toFixed(4)},${afterFly.lat.toFixed(4)} target=${firstOrg.lon},${firstOrg.lat}`);
        record("S8-url", new URL(page.url()).searchParams.get("sel") === firstOrg.id, `url ${page.url()}`);
      } else {
        record("F3", false, "no search hits to click");
        record("S5", false, "no hit");
        record("S6", false, "no hit");
        record("S8-url", false, "no hit");
      }

      page.off("request", onRequest);
      await page.setRequestInterception(false);

      const selId = firstOrg?.id;
      if (selId) {
        await page.goto(`${preview.url}/?q=apotek&sel=${encodeURIComponent(selId)}`, {
          waitUntil: "domcontentloaded",
          timeout: 60000,
        });
        await waitForMap(page);
        await page.waitForFunction(
          () =>
            document.querySelector(
              '[data-testid=bottom-sheet][data-state="peek"], [data-testid=bottom-sheet][data-state="building"]',
            ),
          { timeout: 10000 },
        );
        const restored = await page.evaluate(() => ({
          q: document.querySelector("[data-testid=search-input]")?.value || "",
          sheet: document.querySelector("[data-testid=bottom-sheet]")?.getAttribute("data-state"),
          title: window.__zylosApp?.selectionTitle,
        }));
        record("S8", restored.q === "apotek" && (restored.sheet === "peek" || restored.sheet === "building"), JSON.stringify(restored));
      }

      await page.goto(`${preview.url}/?q=futo`, { waitUntil: "domcontentloaded", timeout: 60000 });
      await waitForMap(page);
      try {
        await waitForHits(page, 3, 8000);
      } catch {
        /* counted below */
      }
      const futoHits = await page.$$("[data-testid=search-hit]");
      record("F4", futoHits.length >= 3, `q=futo hits=${futoHits.length}`);

      console.log("\n== POI click ==");
      await page.goto(preview.url, { waitUntil: "domcontentloaded", timeout: 60000 });
      await waitForMap(page);
      const poiTarget = await page.evaluate(async () => {
        const map = window.__zylosMap;
        await new Promise((resolve) => {
          map.once("idle", resolve);
          map.jumpTo({ center: [19.845, 45.255], zoom: 16 });
        });
        const layers = ["poi-dot", "poi-label"].filter((id) => map.getLayer(id));
        const features = layers.length ? map.queryRenderedFeatures({ layers }) : [];
        const center = map.project(map.getCenter());
        let best = null;
        let bestDist = Infinity;
        for (const feature of features) {
          if (feature.geometry.type !== "Point") {
            continue;
          }
          const point = map.project(feature.geometry.coordinates);
          const dist = (point.x - center.x) ** 2 + (point.y - center.y) ** 2;
          if (dist < bestDist) {
            bestDist = dist;
            const props = feature.properties || {};
            best = {
              x: point.x,
              y: point.y,
              name: props["name:sr-Latn"] || props["name:latin"] || props.name || "POI",
            };
          }
        }
        return { count: features.length, best };
      });
      if (poiTarget.best) {
        const canvasBox = await page.$eval(".maplibregl-canvas", (el) => {
          const rect = el.getBoundingClientRect();
          return { x: rect.x, y: rect.y };
        });
        await page.mouse.click(canvasBox.x + poiTarget.best.x, canvasBox.y + poiTarget.best.y);
        await page.waitForFunction(() => window.__zylosApp?.sheet === "peek", { timeout: 4000 }).catch(() => null);
      }
      const poiState = await page.evaluate(() => ({
        name: window.__zylosApp?.selectionTitle || "",
        sheet: window.__zylosApp?.sheet,
      }));
      record(
        "Poi1",
        Boolean(poiState.name) && poiState.sheet === "peek",
        `poi features=${poiTarget.count} title="${poiState.name}"`,
      );
      record("Poi3", orgRequests === 0, `/orgs/:id calls=${orgRequests}`);

      await page.evaluate(async () => {
        const map = window.__zylosMap;
        await new Promise((resolve) => {
          map.once("idle", resolve);
          map.jumpTo({ center: [19.86, 45.26], zoom: 14 });
        });
      });
      const waterClick = await page.evaluate(() => {
        const canvas = document.querySelector(".maplibregl-canvas");
        const rect = canvas.getBoundingClientRect();
        return { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 };
      });
      await page.mouse.click(waterClick.x, waterClick.y);
      await new Promise((resolve) => setTimeout(resolve, 200));
      const afterEmpty = await page.evaluate(() => window.__zylosApp?.sheet);
      record("Poi2", afterEmpty === "closed", `empty click sheet=${afterEmpty} (closes selection)`);

      console.log("\n== S7 unavailable ==");
      const s7page = await browser.newPage();
      await s7page.setViewport({ width: 1400, height: 900, deviceScaleFactor: 1 });
      await s7page.evaluateOnNewDocument(() => {
        const orig = window.fetch.bind(window);
        window.fetch = async (input, init) => {
          const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
          if (String(url).includes("/v1/search")) {
            return new Response(JSON.stringify({ error: "search_unavailable" }), {
              status: 503,
              headers: { "Content-Type": "application/json" },
            });
          }
          return orig(input, init);
        };
      });
      await s7page.goto(preview.url, { waitUntil: "domcontentloaded", timeout: 60000 });
      await waitForMap(s7page);
      await typeQuery(s7page, "apotek");
      await s7page.waitForFunction(
        () => /поиск недоступен/i.test(document.body.innerText) || /поиск недоступен/i.test(window.__zylosApp?.error || ""),
        { timeout: 5000 },
      ).catch(() => null);
      const s7state = await s7page.evaluate(() => ({
        error: window.__zylosApp?.error || "",
        buildings: window.__zylosMap?.getLayer("buildings")
          ? window.__zylosMap.queryRenderedFeatures({ layers: ["buildings"] }).length
          : 0,
      }));
      record(
        "S7",
        s7state.error.toLowerCase().includes("недоступен") && s7state.buildings > 0,
        `error="${s7state.error}" buildings=${s7state.buildings}`,
      );
      await s7page.close();

      console.log("\n== F6 fixtures ==");
      const fixtures = loadPoiFixtures();
      record("F6-pre", fixtures.length >= 3, `fixture rows=${fixtures.length}`);
      let f6Ok = fixtures.length >= 3;
      const f6Details = [];
      for (const fixture of fixtures) {
        await page.goto(`${preview.url}/?q=${encodeURIComponent(fixture.name)}`, {
          waitUntil: "domcontentloaded",
          timeout: 60000,
        });
        await waitForMap(page);
        try {
          await waitForHits(page, 1, 8000);
        } catch {
          f6Ok = false;
          f6Details.push(`${fixture.name} → no hits`);
          continue;
        }
        const ids = await page.$$eval("[data-testid=search-hit]", (els) =>
          els.slice(0, 5).map((el) => el.getAttribute("data-id")),
        );
        const found = ids.includes(fixture.id);
        if (!found) {
          f6Ok = false;
          f6Details.push(`${fixture.name} → miss ${ids.join(",")}`);
          continue;
        }
        await page.evaluate((id) => {
          document.querySelector(`[data-testid="search-hit"][data-id="${id}"]`)?.click();
        }, fixture.id);
        await page.waitForSelector(
          '[data-testid=bottom-sheet][data-state="peek"], [data-testid=bottom-sheet][data-state="building"]',
          { timeout: 4000 },
        );
        const title = await page.evaluate(() => window.__zylosApp?.selectionTitle || "");
        const opened = Boolean(title);
        if (!opened) {
          f6Ok = false;
        }
        f6Details.push(`${fixture.name} → ${opened ? "sheet" : "no sheet"}`);
      }
      record("F6", f6Ok, f6Details.join("; ") || "none");

      const fullPmtiles = tileRequests.filter(
        (item) => item.url.includes(".pmtiles") && item.status === 200 && !item.range,
      );
      const mvtOk = tileRequests.some((item) => item.url.includes("/mvt/") && (item.status === 200 || item.status === 206));
      record("M6", mvtOk && fullPmtiles.length === 0, `mvt requests=${tileRequests.filter((i) => i.url.includes("/mvt/")).length}, full pmtiles=${fullPmtiles.length}`);
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
    bundle,
    rows,
    notices,
  };
  writeFileSync(join(root, "data", "tmp", "stage04-verify.json"), JSON.stringify(report, null, 2) + "\n");

  if (notices.length) {
    console.log("\nNotices:");
    for (const notice of notices) {
      console.log(`  - ${notice}`);
    }
  }

  if (failed > 0) {
    console.error(`\nstage04-verify failed: ${failed} check(s)`);
    process.exit(1);
  }
  console.log("\nstage04-verify passed.");
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
