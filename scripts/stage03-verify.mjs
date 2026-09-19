import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { execFileSync } from "node:child_process";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");

function loadEnvFile(pathname) {
  if (!existsSync(pathname)) {
    return;
  }
  for (const raw of readFileSync(pathname, "utf8").split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith("#")) {
      continue;
    }
    const eq = line.indexOf("=");
    if (eq === -1) {
      continue;
    }
    const key = line.slice(0, eq).trim();
    let value = line.slice(eq + 1).trim();
    if (
      (value.startsWith('"') && value.endsWith('"')) ||
      (value.startsWith("'") && value.endsWith("'"))
    ) {
      value = value.slice(1, -1);
    }
    if (process.env[key] === undefined) {
      process.env[key] = value;
    }
  }
}

loadEnvFile(join(root, ".env"));

const apiUrl = (process.env.API_URL ?? `http://127.0.0.1:${process.env.API_PORT ?? "3000"}`).replace(
  /\/$/,
  "",
);
const meiliUrl = (process.env.MEILI_URL ?? "http://127.0.0.1:7700").replace(/\/$/, "");
const meiliKey = process.env.MEILI_MASTER_KEY;
if (!meiliKey) {
  throw new Error("MEILI_MASTER_KEY is required");
}

const CENTER = { lat: 45.255, lon: 19.845 };
const rows = [];
let failed = 0;

function record(id, metric, value, required, ok) {
  const status = ok ? "OK" : "FAIL";
  if (!ok) {
    failed += 1;
  }
  rows.push({ id, metric, value, required, status });
  console.log(`${status.padEnd(4)} ${id.padEnd(4)} ${metric}: ${value} (required ${required})`);
}

function percentile(values, p) {
  if (values.length === 0) {
    return NaN;
  }
  const sorted = [...values].sort((a, b) => a - b);
  const idx = Math.min(sorted.length - 1, Math.max(0, Math.ceil((p / 100) * sorted.length) - 1));
  return sorted[idx];
}

function haversineMeters(lat1, lon1, lat2, lon2) {
  const toRad = (d) => (d * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) ** 2;
  return 2 * 6371000 * Math.asin(Math.sqrt(a));
}

function docker() {
  return execFileSync("docker", [...arguments], {
    cwd: root,
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
  }).trim();
}

async function meili(method, path, body) {
  const response = await fetch(`${meiliUrl}${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${meiliKey}`,
      "Content-Type": "application/json",
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const json = await response.json().catch(() => null);
  return { status: response.status, json };
}

async function apiGet(path) {
  const started = performance.now();
  const response = await fetch(`${apiUrl}${path}`);
  const ms = performance.now() - started;
  const json = await response.json().catch(() => null);
  return { status: response.status, json, ms };
}

function searchPath(params) {
  const url = new URL("/v1/search", "http://local.invalid");
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null) {
      url.searchParams.set(key, String(value));
    }
  }
  return url.pathname + url.search;
}

async function scanDocuments() {
  const stats = await meili("GET", "/indexes/zylos/stats");
  const total = stats.json?.numberOfDocuments ?? 0;
  const ids = new Set();
  let duplicateIds = 0;
  let missingGeo = 0;
  let orgs = 0;
  let orgsMissingCategory = 0;
  let offset = 0;
  const limit = 1000;
  while (offset < total) {
    const { status, json } = await meili(
      "GET",
      `/indexes/zylos/documents?limit=${limit}&offset=${offset}&fields=id,kind,category_slug,_geo`,
    );
    if (status !== 200) {
      throw new Error(`documents scan failed: ${status} ${JSON.stringify(json)}`);
    }
    const results = json.results ?? json;
    const batch = Array.isArray(results) ? results : [];
    if (batch.length === 0) {
      break;
    }
    for (const doc of batch) {
      if (ids.has(doc.id)) {
        duplicateIds += 1;
      } else {
        ids.add(doc.id);
      }
      const geo = doc._geo;
      if (
        !geo ||
        !Number.isFinite(Number(geo.lat)) ||
        !Number.isFinite(Number(geo.lng))
      ) {
        missingGeo += 1;
      }
      if (doc.kind === "organization") {
        orgs += 1;
        if (!doc.category_slug) {
          orgsMissingCategory += 1;
        }
      }
    }
    offset += batch.length;
  }
  return { total, duplicateIds, missingGeo, orgs, orgsMissingCategory };
}

function parseMemToMb(raw) {
  const used = String(raw).split("/")[0].trim();
  const match = used.match(/^([\d.]+)\s*([KMGT])i?B$/i);
  if (!match) {
    return NaN;
  }
  const n = Number(match[1]);
  const unit = match[2].toUpperCase();
  const factor = { K: 1 / 1024, M: 1, G: 1024, T: 1024 * 1024 }[unit];
  return n * factor;
}

async function measureSearch(path, n, warmup) {
  for (let i = 0; i < warmup; i += 1) {
    await apiGet(path);
  }
  const wall = [];
  const cpu = [];
  for (let i = 0; i < n; i += 1) {
    const result = await apiGet(path);
    wall.push(result.ms);
    cpu.push(Number(result.json?.processingTimeMs ?? result.ms));
  }
  return {
    wallP95: percentile(wall, 95),
    cpuP95: percentile(cpu, 95),
    wall,
    cpu,
  };
}

async function main() {
  mkdirSync(join(root, "data", "tmp"), { recursive: true });

  let meiliHealth = "down";
  try {
    const health = await fetch(`${meiliUrl}/health`, { signal: AbortSignal.timeout(2000) });
    const body = await health.json();
    meiliHealth = health.ok && body.status === "available" ? "ok" : "down";
  } catch {
    meiliHealth = "down";
  }
  record("I1", "meilisearch_health", meiliHealth, "ok", meiliHealth === "ok");

  const apiHealth = await apiGet("/v1/health");
  record(
    "I3",
    "api_health.meilisearch",
    `${apiHealth.status} ${apiHealth.json?.meilisearch ?? "n/a"}`,
    "200 ok",
    apiHealth.status === 200 && apiHealth.json?.status === "ok" && apiHealth.json?.meilisearch === "ok",
  );

  let pgAddress = 0;
  let pgOrg = 0;
  let boundary = 0;
  try {
    const pg = docker(
      "compose",
      "exec",
      "-T",
      "postgis",
      "psql",
      "-U",
      "zylos",
      "-d",
      "zylos",
      "-t",
      "-A",
      "-c",
      "SELECT 'address='||count(*) FROM address UNION ALL SELECT 'organization='||count(*) FROM organization UNION ALL SELECT 'boundary='||osm_relation_id FROM city_boundary;",
    );
    for (const line of pg.split(/\r?\n/)) {
      if (line.startsWith("address=")) {
        pgAddress = Number(line.slice(8));
      }
      if (line.startsWith("organization=")) {
        pgOrg = Number(line.slice(13));
      }
      if (line.startsWith("boundary=")) {
        boundary = Number(line.slice(9));
      }
    }
  } catch (err) {
    console.error(err);
  }
  record("P2", "postgis address/org", `${pgAddress}/${pgOrg}`, ">=22000 / >=1500", pgAddress >= 22000 && pgOrg >= 1500);
  record("P3", "city_boundary osm_relation_id", boundary, "1649672", boundary === 1649672);
  record("R2", "postgis not emptied", `${pgAddress} addr, ${pgOrg} org`, "stage 2 volumes", pgAddress >= 22000 && pgOrg >= 1500);

  const facet = await meili("POST", "/indexes/zylos/search", {
    q: "",
    limit: 0,
    facets: ["kind", "category_slug", "source"],
  });
  const kinds = facet.json?.facetDistribution?.kind ?? {};
  const addressDocs = Number(kinds.address ?? 0);
  const orgDocs = Number(kinds.organization ?? 0);
  record("M1", "documents kind=address", addressDocs, ">= 22000", addressDocs >= 22000);
  record("M2", "documents kind=organization", orgDocs, ">= 1500", orgDocs >= 1500);

  const orgFacet = await meili("POST", "/indexes/zylos/search", {
    q: "",
    limit: 0,
    filter: "kind = organization",
    facets: ["category_slug"],
  });
  const catDist = orgFacet.json?.facetDistribution?.category_slug ?? {};
  const otherCount = Number(catDist.other ?? 0);
  const orgTotal = Number(orgFacet.json?.estimatedTotalHits ?? orgDocs);
  const otherShare = orgTotal === 0 ? 1 : otherCount / orgTotal;
  record(
    "M6",
    "category_slug=other share",
    `${(otherShare * 100).toFixed(2)}%`,
    "< 35%",
    otherShare < 0.35,
  );

  const scanned = await scanDocuments();
  record("M3", "documents without coordinates", scanned.missingGeo, "0", scanned.missingGeo === 0);
  record("M4", "duplicate ids", scanned.duplicateIds, "0", scanned.duplicateIds === 0);
  record("M5", "orgs without category_slug", scanned.orgsMissingCategory, "0", scanned.orgsMissingCategory === 0);

  const f1 = await apiGet(searchPath({ q: "apotek", limit: 15 }));
  const f1Hits = f1.json?.hits ?? [];
  const f1AllOrg = f1Hits.length > 0 && f1Hits.every((h) => h.kind === "organization");
  const f1Pharmacy = f1Hits.some((h) => h.category_slug === "pharmacy");
  record(
    "F1",
    "q=apotek",
    `${f1Hits.length} hits, pharmacy=${f1Pharmacy}`,
    ">=5 org, >=1 pharmacy",
    f1.status === 200 && f1Hits.length >= 5 && f1AllOrg && f1Pharmacy,
  );

  const f2 = await apiGet(searchPath({ q: "futo" }));
  record("F2", "q=futo", `${(f2.json?.hits ?? []).length} hits`, ">= 3", f2.status === 200 && (f2.json?.hits?.length ?? 0) >= 3);

  const f3a = await apiGet(searchPath({ q: "bulevar" }));
  const f3b = await apiGet(searchPath({ q: "булевар" }));
  const pickF3 = (f3a.json?.hits?.length ?? 0) >= (f3b.json?.hits?.length ?? 0) ? f3a : f3b;
  const f3Hits = pickF3.json?.hits ?? [];
  const f3Addr = f3Hits.filter((h) => h.kind === "address").length;
  record(
    "F3",
    "q=bulevar/булевар",
    `${f3Hits.length} hits, ${f3Addr} address`,
    ">=10 hits, >=5 address",
    pickF3.status === 200 && f3Hits.length >= 10 && f3Addr >= 5,
  );

  const f4 = await apiGet(
    searchPath({ q: "12", kind: "address", lat: CENTER.lat, lon: CENTER.lon }),
  );
  const f4Hits = f4.json?.hits ?? [];
  const f4Near = f4Hits.some(
    (h) =>
      h.kind === "address" &&
      Number.isFinite(h.lat) &&
      Number.isFinite(h.lon) &&
      haversineMeters(CENTER.lat, CENTER.lon, h.lat, h.lon) <= 2000,
  );
  record(
    "F4",
    "q=12 kind=address geo",
    `${f4Hits.length} hits, near=${f4Near}`,
    ">=1 address within ~2km",
    f4.status === 200 && f4Near,
  );

  const f5 = await apiGet(searchPath({ q: "a" }));
  record(
    "F5",
    "q=a short",
    `${f5.status} hits=${(f5.json?.hits ?? []).length}`,
    "200 empty hits",
    f5.status === 200 && Array.isArray(f5.json?.hits) && f5.json.hits.length === 0,
  );

  const f6 = await apiGet("/v1/search?q=");
  record(
    "F6",
    "q empty",
    `${f6.status} hits=${(f6.json?.hits ?? []).length} ${f6.ms.toFixed(1)}ms cpu=${f6.json?.processingTimeMs}`,
    "200 empty <=20ms",
    f6.status === 200 &&
      Array.isArray(f6.json?.hits) &&
      f6.json.hits.length === 0 &&
      Number(f6.json?.processingTimeMs ?? f6.ms) <= 20,
  );

  let fixtures = [];
  try {
    const raw = docker(
      "compose",
      "exec",
      "-T",
      "postgis",
      "psql",
      "-U",
      "zylos",
      "-d",
      "zylos",
      "-t",
      "-A",
      "-F",
      "\t",
      "-c",
      "SELECT f.source_id, o.source, o.name FROM stage02_poi_fixture f JOIN organization o ON o.source_id = f.source_id ORDER BY f.source_id;",
    );
    fixtures = raw
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter(Boolean)
      .map((line) => {
        const [source_id, source, ...nameParts] = line.split("\t");
        return { source_id, source, name: nameParts.join("\t") };
      });
  } catch (err) {
    console.error(err);
  }
  record("F7pre", "fixtures.sql POI rows", fixtures.length, ">= 3", fixtures.length >= 3);
  let f7Ok = fixtures.length >= 3;
  const f7Details = [];
  // Fixtures sit at Trg slobode; identical brand names (OTP banka) need geo-bias to surface the anchored POI in top 5.
  for (const fixture of fixtures) {
    const result = await apiGet(
      searchPath({ q: fixture.name, limit: 5, lat: CENTER.lat, lon: CENTER.lon }),
    );
    const expectedId = `org:${fixture.source}:${fixture.source_id}`;
    const hits = result.json?.hits ?? [];
    const found = hits.some((h) => h.id === expectedId);
    f7Details.push(`${fixture.name} → ${found ? "hit" : "miss"}`);
    if (!found) {
      f7Ok = false;
    }
  }
  record("F7", "fixture POI in top 5", f7Details.join("; ") || "none", "each in top 5", f7Ok);

  const s2a = await apiGet(searchPath({ q: "apotek", limit: 16 }));
  const s2b = await apiGet(searchPath({ q: "apotek", limit: "abc" }));
  record(
    "S2",
    "limit max 15",
    `${s2a.status}/${s2b.status}`,
    "400/400",
    s2a.status === 400 && s2b.status === 400,
  );

  let rssMb = NaN;
  try {
    const mem = docker("stats", "meilisearch", "--no-stream", "--format", "{{.MemUsage}}");
    rssMb = parseMemToMb(mem);
    record("S6", "meilisearch RSS MB", rssMb.toFixed(1), "<= 300", Number.isFinite(rssMb) && rssMb <= 300);
  } catch (err) {
    record("S6", "meilisearch RSS MB", String(err.message ?? err), "<= 300", false);
  }

  const s1 = await measureSearch(searchPath({ q: "apotek" }), 30, 5);
  record("S1", "p95 GET /v1/search q=apotek ms", s1.cpuP95.toFixed(1), "<= 120", s1.cpuP95 <= 120);

  const s4a = await measureSearch(searchPath({ q: "a" }), 30, 5);
  const s4b = await measureSearch("/v1/search?q=", 30, 5);
  const s4p95 = Math.max(s4a.cpuP95, s4b.cpuP95);
  record("S4", "p95 short/empty q ms", s4p95.toFixed(1), "<= 20", s4p95 <= 20);

  const settings = await meili("GET", "/indexes/zylos/settings");
  const searchable = settings.json?.searchableAttributes ?? [];
  record(
    "Iset",
    "searchable street_sr_cyrl",
    searchable.includes("street_sr_cyrl") ? "yes" : "no",
    "yes",
    searchable.includes("street_sr_cyrl"),
  );

  console.log("");
  console.log("| # | Metric | Value | Required | Status |");
  console.log("|---|--------|------:|----------|--------|");
  for (const row of rows) {
    console.log(`| ${row.id} | ${row.metric} | ${row.value} | ${row.required} | ${row.status} |`);
  }

  const report = {
    generatedAt: new Date().toISOString(),
    addressDocs,
    orgDocs,
    otherShare,
    rssMb,
    s1P95: s1.cpuP95,
    s4P95: s4p95,
    f7: f7Details,
    failed,
    rows,
  };
  writeFileSync(join(root, "data", "tmp", "stage03-verify.json"), JSON.stringify(report, null, 2) + "\n");

  if (failed > 0) {
    console.error(`\nstage03-verify failed: ${failed} check(s)`);
    process.exit(1);
  }
  console.log("\nstage03-verify passed.");
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
