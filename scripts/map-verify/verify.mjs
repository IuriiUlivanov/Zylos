#!/usr/bin/env node
/**
 * Stage 1 map acceptance checks.
 *
 * HTTP + PMTiles Range, MVT contents at Grad Novi Sad fixtures,
 * water-fill coverage of the Danube, MapLibre render counts.
 *
 *   node scripts/map-verify/verify.mjs
 *   scripts/stage01-verify.ps1
 *
 * Exit 0 if every check passed. Failures are collected and printed together.
 */

import { existsSync, statSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { gunzipSync } from "node:zlib";
import Pbf from "pbf";
import { VectorTile } from "@mapbox/vector-tile";
import puppeteer from "puppeteer-core";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const baseUrl = (process.env.NAVIGATOR_PREVIEW_URL || "http://127.0.0.1:8080").replace(/\/$/, "");
const pmtilesPath = join(root, "data", "tiles", "novi-sad.pmtiles");

const VECTOR_TYPE = { 1: "Point", 2: "LineString", 3: "Polygon" };

const fixtures = {
  center: { lon: 19.845, lat: 45.255, label: "Trg slobode" },
  petrovaradin: { lon: 19.866, lat: 45.252, label: "Petrovaradin fortress" },
  liman: { lon: 19.84, lat: 45.238, label: "Liman" },
  danube: { lon: 19.86, lat: 45.26, label: "Danube (filled channel, not the bridge deck)" }
};

const failures = [];
const notices = [];

function record(id, ok, detail) {
  const status = ok ? "OK  " : "FAIL";
  console.log(`${status}  ${id.padEnd(28)} ${detail}`);
  if (!ok) failures.push(`${id}: ${detail}`);
}

async function fetchBuffer(path, headers = {}) {
  const url = path.startsWith("http") ? path : `${baseUrl}${path}`;
  const response = await fetch(url, { headers, redirect: "manual" });
  const buffer = Buffer.from(await response.arrayBuffer());
  return { response, buffer, url };
}

async function fetchText(path) {
  const { response, buffer, url } = await fetchBuffer(path);
  return { response, text: buffer.toString("utf8"), url, bytes: buffer.length };
}

function lonLatToTile(lon, lat, z) {
  const n = 2 ** z;
  const x = Math.floor(((lon + 180) / 360) * n);
  const latRad = (lat * Math.PI) / 180;
  const y = Math.floor(
    ((1 - Math.log(Math.tan(latRad) + 1 / Math.cos(latRad)) / Math.PI) / 2) * n
  );
  return { z, x, y };
}

function decodeMvt(buffer) {
  let data = buffer;
  if (data.length >= 2 && data[0] === 0x1f && data[1] === 0x8b) {
    data = gunzipSync(data);
  }
  return new VectorTile(new Pbf(data));
}

function layerStats(tile, name) {
  const layer = tile.layers[name];
  if (!layer) {
    return { count: 0, polygons: 0, lines: 0, points: 0, missing: true };
  }
  let polygons = 0;
  let lines = 0;
  let points = 0;
  for (let i = 0; i < layer.length; i += 1) {
    const type = VECTOR_TYPE[layer.feature(i).type] || "Unknown";
    if (type === "Polygon") polygons += 1;
    else if (type === "LineString") lines += 1;
    else points += 1;
  }
  return { count: layer.length, polygons, lines, points, missing: false };
}

function pointInRing(lon, lat, ring) {
  let inside = false;
  const n = ring.length;
  let j = n - 1;
  for (let i = 0; i < n; i += 1) {
    const xi = ring[i][0];
    const yi = ring[i][1];
    const xj = ring[j][0];
    const yj = ring[j][1];
    const denom = yj - yi;
    if (
      denom !== 0 &&
      yi > lat !== yj > lat &&
      lon < ((xj - xi) * (lat - yi)) / denom + xi
    ) {
      inside = !inside;
    }
    j = i;
  }
  return inside;
}

function polygonCovers(lon, lat, rings) {
  if (!rings?.length) return false;
  if (!pointInRing(lon, lat, rings[0])) return false;
  for (let i = 1; i < rings.length; i += 1) {
    if (pointInRing(lon, lat, rings[i])) return false;
  }
  return true;
}

function geometryCovers(lon, lat, geometry) {
  if (!geometry) return false;
  if (geometry.type === "Polygon") return polygonCovers(lon, lat, geometry.coordinates);
  if (geometry.type === "MultiPolygon") {
    return geometry.coordinates.some((rings) => polygonCovers(lon, lat, rings));
  }
  return false;
}

function ringBBox(ring, acc) {
  for (const point of ring || []) {
    acc[0] = Math.min(acc[0], point[0]);
    acc[1] = Math.min(acc[1], point[1]);
    acc[2] = Math.max(acc[2], point[0]);
    acc[3] = Math.max(acc[3], point[1]);
  }
}

function geometryBBox(geometry) {
  const acc = [180, 90, -180, -90];
  if (geometry?.type === "Polygon") {
    for (const ring of geometry.coordinates) ringBBox(ring, acc);
  } else if (geometry?.type === "MultiPolygon") {
    for (const polygon of geometry.coordinates) {
      for (const ring of polygon) ringBBox(ring, acc);
    }
  }
  return acc;
}

function findBrowser() {
  if (process.env.BROWSER_PATH && existsSync(process.env.BROWSER_PATH)) {
    return process.env.BROWSER_PATH;
  }
  const candidates = [
    "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
    "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
    "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
    "/usr/bin/google-chrome",
    "/usr/bin/google-chrome-stable",
    "/usr/bin/chromium",
    "/usr/bin/chromium-browser",
    "/usr/bin/microsoft-edge",
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"
  ];
  return candidates.find((path) => existsSync(path)) || null;
}

async function checkHttpAndArtifacts() {
  console.log("\n== HTTP and artifacts ==");

  const pmtilesOk = existsSync(pmtilesPath);
  const pmtilesBytes = pmtilesOk ? statSync(pmtilesPath).size : 0;
  record(
    "H5-size",
    pmtilesOk && pmtilesBytes >= 1_000_000 && pmtilesBytes <= 15_000_000,
    pmtilesOk
      ? `${pmtilesBytes} bytes (need 1–15 MB)`
      : `missing ${pmtilesPath}`
  );

  const range = await fetchBuffer("/tiles/novi-sad.pmtiles", {
    Range: "bytes=0-16383"
  });
  const contentRange = range.response.headers.get("content-range") || "";
  record(
    "H1-range",
    range.response.status === 206 && /^bytes\s+0-16383\//i.test(contentRange),
    `HTTP ${range.response.status} Content-Range=${contentRange || "(none)"}`
  );

  const home = await fetchText("/");
  record("H3-preview", home.response.status === 200 && home.bytes > 500, `HTTP ${home.response.status}, ${home.bytes} bytes`);

  const style = await fetchText("/style.json");
  let styleJson = null;
  try {
    styleJson = JSON.parse(style.text);
  } catch {
    styleJson = null;
  }
  record("H3-style", style.response.status === 200 && styleJson?.version === 8, `HTTP ${style.response.status}`);
  if (styleJson) {
    const layerIds = new Set((styleJson.layers || []).map((layer) => layer.id));
    for (const id of ["buildings", "roads", "water-fill", "housenumber"]) {
      record(`H3-layer-${id}`, layerIds.has(id), layerIds.has(id) ? "present in style.json" : "missing from style.json");
    }
    const attribution = styleJson.sources?.noviSad?.attribution || "";
    record(
      "H3-attribution",
      /openstreetmap/i.test(attribution),
      attribution || "noviSad.attribution empty"
    );
    record(
      "H5-maxzoom",
      (styleJson.sources?.noviSad?.maxzoom || 0) >= 14,
      `source maxzoom=${styleJson.sources?.noviSad?.maxzoom}`
    );
  }

  const water = await fetchText("/water-fill.geojson");
  let waterJson = null;
  try {
    waterJson = JSON.parse(water.text);
  } catch {
    waterJson = null;
  }
  const waterFeatures = waterJson?.features || [];
  record(
    "H4-water-fill",
    water.response.status === 200 && waterFeatures.length > 0,
    `HTTP ${water.response.status}, ${waterFeatures.length} features`
  );
  return { styleJson, waterJson };
}

async function checkTiles(waterJson) {
  console.log("\n== MVT fixtures ==");

  const probes = [
    {
      id: "C-center",
      point: fixtures.center,
      minBuilding: 15,
      minRoads: 50,
      minHousenumber: 20
    },
    {
      id: "C-petrovaradin",
      point: fixtures.petrovaradin,
      minBuilding: 3,
      minRoads: 20,
      minHousenumber: 0
    },
    {
      id: "C-liman",
      point: fixtures.liman,
      minBuilding: 15,
      minRoads: 30,
      minHousenumber: 0
    }
  ];

  for (const probe of probes) {
    const tileId = lonLatToTile(probe.point.lon, probe.point.lat, 14);
    const path = `/mvt/novi-sad/${tileId.z}/${tileId.x}/${tileId.y}.mvt`;
    const { response, buffer } = await fetchBuffer(path);
    record(
      `${probe.id}-http`,
      response.status === 200 && buffer.length > 0,
      `14/${tileId.x}/${tileId.y} HTTP ${response.status}, ${buffer.length} bytes`
    );
    if (response.status !== 200 || buffer.length === 0) continue;

    const tile = decodeMvt(buffer);
    const building = layerStats(tile, "building");
    const roads = layerStats(tile, "transportation");
    const numbers = layerStats(tile, "housenumber");

    record(
      `${probe.id}-building`,
      building.polygons >= probe.minBuilding,
      `${probe.point.label}: ${building.polygons} polygons (min ${probe.minBuilding})`
    );
    record(
      `${probe.id}-roads`,
      roads.lines >= probe.minRoads,
      `${probe.point.label}: ${roads.lines} lines (min ${probe.minRoads})`
    );
    if (probe.minHousenumber > 0) {
      record(
        `${probe.id}-housenumber`,
        numbers.count >= probe.minHousenumber,
        `${probe.point.label}: ${numbers.count} (min ${probe.minHousenumber})`
      );
    }
  }

  const danubeTile = lonLatToTile(19.851, 45.261, 14);
  const danubeMvt = await fetchBuffer(
    `/mvt/novi-sad/${danubeTile.z}/${danubeTile.x}/${danubeTile.y}.mvt`
  );
  if (danubeMvt.response.status === 200 && danubeMvt.buffer.length > 0) {
    const tile = decodeMvt(danubeMvt.buffer);
    const roads = layerStats(tile, "transportation");
    const waterway = layerStats(tile, "waterway");
    record(
      "C-bridge-roads",
      roads.lines >= 20,
      `bridge tile 14/${danubeTile.x}/${danubeTile.y}: ${roads.lines} transportation lines`
    );
    notices.push(
      `bridge tile water polygons=${layerStats(tile, "water").polygons}, waterway lines=${waterway.lines}`
    );
  } else {
    record("C-bridge-roads", false, `bridge tile HTTP ${danubeMvt.response.status}`);
  }

  const features = waterJson?.features || [];
  const covering = features.filter((feature) =>
    geometryCovers(fixtures.danube.lon, fixtures.danube.lat, feature.geometry)
  );
  record(
    "C-danube-fill",
    covering.length >= 1,
    covering.length >= 1
      ? `point ${fixtures.danube.lon},${fixtures.danube.lat} inside ${covering.length} water-fill polygon(s)`
      : `point ${fixtures.danube.lon},${fixtures.danube.lat} is not inside water-fill.geojson`
  );

  let widest = 0;
  for (const feature of features) {
    const bbox = geometryBBox(feature.geometry);
    widest = Math.max(widest, bbox[2] - bbox[0]);
  }
  record(
    "C-danube-extent",
    widest >= 0.1,
    `widest water-fill bbox width=${widest.toFixed(4)}° (min 0.1° ~ Danube through the city)`
  );
}

async function waitForMap(page) {
  await page.waitForFunction(
    () => Boolean(window.__zylosMap),
    { timeout: 45000 }
  );
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
      })
  );
}

async function inspectView(page, lon, lat, zoom) {
  return page.evaluate(
    async ({ lon: targetLon, lat: targetLat, zoom: targetZoom }) => {
      const map = window.__zylosMap;
      await new Promise((resolve) => {
        map.once("idle", resolve);
        map.jumpTo({ center: [targetLon, targetLat], zoom: targetZoom });
      });
      const layerIds = ["buildings", "buildings-3d", "roads", "water-fill", "water", "housenumber"];
      const rendered = {};
      for (const id of layerIds) {
        rendered[id] = map.getLayer(id)
          ? map.queryRenderedFeatures({ layers: [id] }).length
          : -1;
      }
      return {
        zoom: map.getZoom(),
        center: map.getCenter(),
        rendered,
        sourceBuildings: map.querySourceFeatures("noviSad", { sourceLayer: "building" }).length,
        sourceHousenumber: map.querySourceFeatures("noviSad", { sourceLayer: "housenumber" }).length,
        error: window.__zylosMapError,
        attribution: document.querySelector(".maplibregl-ctrl-attrib")?.textContent || ""
      };
    },
    { lon, lat, zoom }
  );
}

async function checkRender() {
  console.log("\n== MapLibre render ==");
  const executablePath = findBrowser();
  if (!executablePath) {
    record("R0-browser", false, "no Chrome/Edge found; set BROWSER_PATH");
    return;
  }
  record("R0-browser", true, executablePath);

  const browser = await puppeteer.launch({
    executablePath,
    headless: true,
    args: [
      "--hide-scrollbars",
      "--window-size=1400,900",
      "--use-gl=angle",
      "--use-angle=swiftshader",
      "--enable-webgl",
      "--ignore-gpu-blocklist"
    ]
  });

  try {
    const page = await browser.newPage();
    await page.setViewport({ width: 1400, height: 900, deviceScaleFactor: 1 });
    page.setDefaultTimeout(45000);
    await page.goto(`${baseUrl}/#14/45.255/19.845`, {
      waitUntil: "domcontentloaded",
      timeout: 60000
    });
    await waitForMap(page);

    const center = await inspectView(page, fixtures.center.lon, fixtures.center.lat, 15);
    record(
      "R-center-buildings",
      center.rendered.buildings > 0,
      `z15 ${fixtures.center.label}: buildings=${center.rendered.buildings}, source=${center.sourceBuildings}`
    );
    record(
      "R-center-roads",
      center.rendered.roads > 0,
      `z15 roads=${center.rendered.roads}`
    );
    record(
      "R-attribution",
      /openstreetmap/i.test(center.attribution),
      center.attribution.trim() || "attribution control empty"
    );

    const petro = await inspectView(
      page,
      fixtures.petrovaradin.lon,
      fixtures.petrovaradin.lat,
      15
    );
    record(
      "R-petrovaradin-buildings",
      petro.rendered.buildings > 0,
      `z15 ${fixtures.petrovaradin.label}: buildings=${petro.rendered.buildings} (empty means wrong relation 9273976)`
    );

    const liman = await inspectView(page, fixtures.liman.lon, fixtures.liman.lat, 15);
    record(
      "R-liman-buildings",
      liman.rendered.buildings > 0,
      `z15 ${fixtures.liman.label}: buildings=${liman.rendered.buildings}`
    );

    const danube = await inspectView(page, fixtures.danube.lon, fixtures.danube.lat, 14);
    const waterRendered = Math.max(danube.rendered["water-fill"] || 0, danube.rendered.water || 0);
    record(
      "R-danube-fill",
      waterRendered > 0,
      `z14 ${fixtures.danube.label}: water-fill=${danube.rendered["water-fill"]}, water=${danube.rendered.water}`
    );

    const labels = await inspectView(page, fixtures.center.lon, fixtures.center.lat, 16);
    record(
      "R-housenumber-source",
      labels.sourceHousenumber > 0,
      `z16 center housenumber source=${labels.sourceHousenumber}, rendered=${labels.rendered.housenumber}`
    );

    const fatal =
      (center.error || petro.error || liman.error || danube.error) &&
      center.rendered.buildings <= 0;
    record(
      "R-no-fatal-error",
      !fatal,
      center.error || petro.error || liman.error || danube.error || "no map error"
    );
  } finally {
    await browser.close();
  }
}

const { waterJson } = await checkHttpAndArtifacts();
await checkTiles(waterJson);
await checkRender();

if (notices.length) {
  console.log("\nNotices:");
  for (const notice of notices) console.log(`  - ${notice}`);
}

if (failures.length) {
  console.log(`\nstage 1 map verify FAILED, ${failures.length} check(s):`);
  for (const failure of failures) console.log(`  - ${failure}`);
  process.exit(1);
}

console.log("\nstage 1 map verify PASSED");
