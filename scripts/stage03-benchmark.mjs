import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

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

function percentile(values, p) {
  const sorted = [...values].sort((a, b) => a - b);
  const idx = Math.min(sorted.length - 1, Math.max(0, Math.ceil((p / 100) * sorted.length) - 1));
  return sorted[idx];
}

async function timedGet(path) {
  const started = performance.now();
  const response = await fetch(`${apiUrl}${path}`);
  const wallMs = performance.now() - started;
  const json = await response.json();
  return {
    status: response.status,
    wallMs,
    cpuMs: Number(json.processingTimeMs ?? wallMs),
    json,
  };
}

async function measure(path, samples, warmup) {
  for (let i = 0; i < warmup; i += 1) {
    await timedGet(path);
  }
  const wall = [];
  const cpu = [];
  for (let i = 0; i < samples; i += 1) {
    const result = await timedGet(path);
    if (result.status !== 200) {
      throw new Error(`${path} returned ${result.status}`);
    }
    wall.push(result.wallMs);
    cpu.push(result.cpuMs);
  }
  return {
    wallP50: percentile(wall, 50),
    wallP95: percentile(wall, 95),
    cpuP50: percentile(cpu, 50),
    cpuP95: percentile(cpu, 95),
    n: samples,
    warmup,
  };
}

function updateReadme(report) {
  const readmePath = join(root, "data", "README.md");
  const existing = existsSync(readmePath) ? readFileSync(readmePath, "utf8") : "";
  const withoutStage3 = existing.split(/\r?\n## Stage 3(?:\b|$)/)[0].trimEnd();
  const verifyPath = join(root, "data", "tmp", "stage03-verify.json");
  const indexPath = join(root, "data", "tmp", "stage03-index.json");
  const verify = existsSync(verifyPath) ? JSON.parse(readFileSync(verifyPath, "utf8")) : {};
  const index = existsSync(indexPath) ? JSON.parse(readFileSync(indexPath, "utf8")) : {};
  const indexedAt = index.indexedAt ?? report.generatedAt;
  const addresses = index.addresses ?? verify.addressDocs ?? "n/a";
  const orgs = index.organizations ?? verify.orgDocs ?? "n/a";
  const rss = Number.isFinite(verify.rssMb) ? verify.rssMb.toFixed(1) : "n/a";
  const section = `

## Stage 3 Meilisearch

- Index: \`zylos\`
- Indexed at: ${indexedAt}
- Address documents: ${addresses}
- Organization documents: ${orgs}
- p95 GET /v1/search \`q=apotek\`: ${report.apotek.cpuP95.toFixed(1)} ms (S1, n=30, warmup 5)
- p95 short/empty \`q\`: ${Math.max(report.short.cpuP95, report.empty.cpuP95).toFixed(1)} ms (S4)
- Meilisearch RSS: ${rss} MB (S6)
`;
  writeFileSync(readmePath, `${withoutStage3}${section}`.trimEnd() + "\n");
}

async function main() {
  mkdirSync(join(root, "data", "tmp"), { recursive: true });
  console.log(`Benchmarking ${apiUrl} ...`);
  const apotek = await measure("/v1/search?q=apotek", 30, 5);
  const short = await measure("/v1/search?q=a", 30, 5);
  const empty = await measure("/v1/search?q=", 30, 5);
  const report = {
    generatedAt: new Date().toISOString(),
    apotek,
    short,
    empty,
  };
  writeFileSync(join(root, "data", "tmp", "stage03-benchmark.json"), JSON.stringify(report, null, 2) + "\n");

  console.log(
    `S1 q=apotek  p50=${apotek.cpuP50.toFixed(1)}ms p95=${apotek.cpuP95.toFixed(1)}ms (wall p95 ${apotek.wallP95.toFixed(1)}ms)`,
  );
  console.log(
    `S4 q=a       p50=${short.cpuP50.toFixed(1)}ms p95=${short.cpuP95.toFixed(1)}ms`,
  );
  console.log(
    `S4 q=        p50=${empty.cpuP50.toFixed(1)}ms p95=${empty.cpuP95.toFixed(1)}ms`,
  );

  updateReadme(report);

  const s1Ok = apotek.cpuP95 <= 120;
  const s4Ok = short.cpuP95 <= 20 && empty.cpuP95 <= 20;
  if (!s1Ok || !s4Ok) {
    console.error("stage03-benchmark failed SLO");
    process.exit(1);
  }
  console.log("stage03-benchmark passed.");
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
