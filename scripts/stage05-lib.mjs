import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import {
  apiUrl,
  docker,
  findBrowser,
  gzipBundle,
  percentile,
  resolveWebUrl,
  root,
  tilesUrl,
  waitForUrl,
  webDir,
} from "./stage04-lib.mjs";

export {
  apiUrl,
  docker,
  findBrowser,
  gzipBundle,
  percentile,
  resolveWebUrl,
  root,
  tilesUrl,
  waitForUrl,
  webDir,
};

export function psql(sql) {
  const result = spawnSync(
    "docker",
    [
      "compose",
      "exec",
      "-T",
      "postgis",
      "sh",
      "-c",
      'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -t -A -F "\t" -f -',
    ],
    {
      cwd: root,
      encoding: "utf8",
      input: sql,
    },
  );
  if (result.status !== 0) {
    throw new Error((result.stderr || result.stdout || "psql failed").trim());
  }
  return (result.stdout || "").trim();
}

export function parseTsv(text) {
  return text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => line.split("\t"));
}

export async function loadF6Fixture() {
  try {
    psql(readFileSync(join(root, "infra", "postgis", "fixtures.sql"), "utf8"));
  } catch {
    /* fixtures.sql is best-effort; fall through to a dynamic F6 row */
  }
  try {
    psql(`
CREATE TABLE IF NOT EXISTS public.stage05_building_fixture (
  building_id uuid PRIMARY KEY,
  lon double precision NOT NULL,
  lat double precision NOT NULL,
  org_count int NOT NULL,
  note text
);
INSERT INTO public.stage05_building_fixture (building_id, lon, lat, org_count, note)
SELECT b.id,
       ST_X(ST_PointOnSurface(b.geom)),
       ST_Y(ST_PointOnSurface(b.geom)),
       count(*)::int,
       coalesce(b.name, 'F6')
FROM public.building b
JOIN public.organization o ON o.building_id = b.id
WHERE NOT EXISTS (SELECT 1 FROM public.stage05_building_fixture)
GROUP BY b.id, b.geom, b.name
HAVING count(*) >= 2
ORDER BY count(*) DESC
LIMIT 1
ON CONFLICT (building_id) DO NOTHING;
`);
  } catch {
    /* table may already exist with a row */
  }

  const rows = parseTsv(
    psql(`
SELECT f.building_id::text,
       f.lon::text,
       f.lat::text,
       f.org_count::text,
       coalesce(f.note, ''),
       (
         SELECT 'org:' || o.source || ':' || o.source_id
         FROM public.organization o
         WHERE o.building_id = f.building_id
         ORDER BY o.name
         LIMIT 1
       )
FROM public.stage05_building_fixture f
LIMIT 1;
`),
  );
  const row = rows[0];
  if (!row) {
    return null;
  }
  return {
    id: row[0],
    lon: Number(row[1]),
    lat: Number(row[2]),
    orgCount: Number(row[3]),
    note: row[4],
    orgId: row[5] || null,
  };
}

export async function fetchJson(url) {
  const started = performance.now();
  const response = await fetch(url);
  const json = await response.json().catch(() => null);
  return { status: response.status, json, ms: performance.now() - started };
}

export async function waitForMap(page, timeout = 45000) {
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

export async function jumpTo(page, lon, lat, zoom) {
  await page.evaluate(
    async ({ lon: targetLon, lat: targetLat, zoom: targetZoom }) => {
      const map = window.__zylosMap;
      await new Promise((resolve) => {
        map.once("idle", resolve);
        map.jumpTo({ center: [targetLon, targetLat], zoom: targetZoom });
      });
    },
    { lon, lat, zoom },
  );
}

export function geometryCandidates(detail, fallback) {
  const points = [{ lon: fallback.lon, lat: fallback.lat }];
  if (detail?.centroid) {
    points.push({ lon: detail.centroid.lon, lat: detail.centroid.lat });
  }
  const geom = detail?.geometry;
  const rings =
    geom?.type === "Polygon"
      ? geom.coordinates
      : geom?.type === "MultiPolygon"
        ? geom.coordinates[0]
        : null;
  const ring = Array.isArray(rings) ? rings[0] : null;
  if (Array.isArray(ring)) {
    for (const coord of ring.slice(0, 12)) {
      if (Array.isArray(coord) && Number.isFinite(coord[0]) && Number.isFinite(coord[1])) {
        points.push({ lon: coord[0], lat: coord[1] });
      }
    }
  }
  return points;
}

export async function screenPointAvoidingPoi(page, candidates) {
  const canvasBox = await page.$eval(".maplibregl-canvas", (el) => {
    const rect = el.getBoundingClientRect();
    return { x: rect.x, y: rect.y };
  });
  for (const point of candidates) {
    await jumpTo(page, point.lon, point.lat, 17);
    const hit = await page.evaluate((lon, lat) => {
      const map = window.__zylosMap;
      const projected = map.project([lon, lat]);
      const layers = ["poi-dot", "poi-label"].filter((id) => map.getLayer(id));
      const pois = layers.length ? map.queryRenderedFeatures(projected, { layers }) : [];
      return { x: projected.x, y: projected.y, poi: pois.length > 0 };
    }, point.lon, point.lat);
    if (!hit.poi) {
      return { x: canvasBox.x + hit.x, y: canvasBox.y + hit.y, lon: point.lon, lat: point.lat };
    }
  }
  const last = candidates[0];
  await jumpTo(page, last.lon, last.lat, 17);
  const projected = await page.evaluate((lon, lat) => {
    const point = window.__zylosMap.project([lon, lat]);
    return { x: point.x, y: point.y };
  }, last.lon, last.lat);
  return { x: canvasBox.x + projected.x, y: canvasBox.y + projected.y, lon: last.lon, lat: last.lat };
}

export async function clickAvoidingPoi(page, candidates) {
  const point = await screenPointAvoidingPoi(page, candidates);
  await page.mouse.click(point.x, point.y);
  return point;
}

export async function findFixturePixel(page, buildingId, apiBase) {
  const canvasBox = await page.$eval(".maplibregl-canvas", (el) => {
    const rect = el.getBoundingClientRect();
    return { x: rect.x, y: rect.y };
  });
  const local = await page.evaluate(
    async (targetId, api) => {
      const map = window.__zylosMap;
      const center = map.project(map.getCenter());
      const poiLayers = ["poi-dot", "poi-label"].filter((id) => map.getLayer(id));
      for (let radius = 0; radius <= 140; radius += 5) {
        const steps = radius === 0 ? 1 : 18;
        for (let step = 0; step < steps; step += 1) {
          const angle = (step / steps) * Math.PI * 2;
          const point = {
            x: center.x + radius * Math.cos(angle),
            y: center.y + radius * Math.sin(angle),
          };
          const pois = poiLayers.length ? map.queryRenderedFeatures(point, { layers: poiLayers }) : [];
          if (pois.length > 0) {
            continue;
          }
          const lngLat = map.unproject(point);
          try {
            const response = await fetch(
              `${api}/v1/buildings/at?lon=${lngLat.lng}&lat=${lngLat.lat}`,
            );
            if (response.status !== 200) {
              continue;
            }
            const body = await response.json();
            if (body.id === targetId) {
              return point;
            }
          } catch {
            continue;
          }
        }
      }
      return null;
    },
    buildingId,
    apiBase,
  );
  if (!local) {
    throw new Error(`no clickable pixel for building ${buildingId}`);
  }
  return { x: canvasBox.x + local.x, y: canvasBox.y + local.y };
}

export function planUsesGist(planJson) {
  const text = JSON.stringify(planJson);
  return /Index Scan|Bitmap Index Scan|Index Only Scan/i.test(text) && /building_geom_gix|building_geog_gix/i.test(text);
}
