#!/usr/bin/env node
/**
 * Stage 4 performance gates: L1, L2, T1, T2, S1 e2e.
 *
 *   node scripts/stage04-benchmark.mjs
 */

import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import puppeteer from "puppeteer-core";
import {
  apiUrl,
  findBrowser,
  gzipBundle,
  percentile,
  resolveWebUrl,
  root,
  webDir,
} from "./stage04-lib.mjs";

const distDir = join(webDir, "dist");

function updateReadme(report) {
  const readmePath = join(root, "data", "README.md");
  const existing = existsSync(readmePath) ? readFileSync(readmePath, "utf8") : "";
  const withoutStage4 = existing.split(/\r?\n## Stage 4(?:\b|$)/)[0].trimEnd();
  const l1 = Math.round(report.l1With / 1024);
  const t1 = report.t1Ms == null ? "n/a" : report.t1Ms.toFixed(0);
  const t2 = report.t2Ms == null ? "n/a" : report.t2Ms.toFixed(0);
  const l2 = report.l2Ms == null ? "n/a" : report.l2Ms.toFixed(0);
  const s1 = report.s1P95 == null ? "n/a" : report.s1P95.toFixed(1);
  const section = `

## Stage 4 web map

- Measured at: ${report.generatedAt}
- gzip JS+CSS with MapLibre: ${l1} KB (L1, limit 900)
- T1 streets visible: ${t1} ms (limit 1500)
- T2 buildings z14: ${t2} ms (limit 2500)
- L2 TTI: ${l2} ms (limit 3500)
- S1 e2e p95 GET /v1/search q=apotek: ${s1} ms (limit 200, n=30)
`;
  writeFileSync(readmePath, `${withoutStage4}${section}`.trimEnd() + "\n");
}

async function emulateSearchRtt(page) {
  const client = await page.createCDPSession();
  await client.send("Network.emulateNetworkConditions", {
    offline: false,
    latency: 50,
    downloadThroughput: -1,
    uploadThroughput: -1,
  });
}

async function waitForMap(page) {
  await page.waitForFunction(() => Boolean(window.__zylosMap), { timeout: 45000 });
  await page.waitForFunction(
    () => {
      const perf = window.__zylosPerf;
      return Boolean(perf?.roadsAt && perf?.buildingsAt);
    },
    { timeout: 15000 },
  ).catch(() => null);
}

async function main() {
  mkdirSync(join(root, "data", "tmp"), { recursive: true });
  const bundle = gzipBundle(distDir);
  console.log(
    `L1 gzip with MapLibre ${(bundle.withMaplibre / 1024).toFixed(1)} KB, without ${(bundle.withoutMaplibre / 1024).toFixed(1)} KB`,
  );

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
      "--ignore-gpu-blocklist",
      "--enable-gpu",
      "--use-gl=angle",
    ],
  });

  let t1Ms = null;
  let t2Ms = null;
  let l2Ms = null;
  let s1P95 = null;
  let u2Ms = null;
  let fps = null;
  let longFrame = null;
  const notices = [];

  try {
    const page = await browser.newPage();
    await page.setViewport({ width: 1400, height: 900, deviceScaleFactor: 1 });
    const started = Date.now();
    await page.goto(preview.url, { waitUntil: "domcontentloaded", timeout: 60000 });
    await waitForMap(page);
    const perf = await page.evaluate(() => window.__zylosPerf || null);
    t1Ms = perf?.roadsAt ?? null;
    t2Ms = perf?.buildingsAt ?? null;
    l2Ms = perf?.ttiAt ?? Date.now() - started;
    console.log(`T1 roads ${t1Ms?.toFixed?.(0) ?? "n/a"} ms`);
    console.log(`T2 buildings ${t2Ms?.toFixed?.(0) ?? "n/a"} ms`);
    console.log(`L2 TTI ${l2Ms?.toFixed?.(0) ?? "n/a"} ms`);

    await emulateSearchRtt(page);
    const samples = [];
    await page.evaluate(async (url) => {
      await fetch(`${url}/v1/search?q=apotek`);
    }, apiUrl);
    for (let i = 0; i < 30; i += 1) {
      const ms = await page.evaluate(async (url) => {
        const t0 = performance.now();
        const response = await fetch(`${url}/v1/search?q=apotek`);
        await response.json();
        return performance.now() - t0;
      }, apiUrl);
      samples.push(ms);
    }
    s1P95 = percentile(samples, 95);
    console.log(`S1 e2e p95 ${s1P95.toFixed(1)} ms (n=30)`);

    await page.evaluate(() => {
      document.querySelector("[data-testid=search-input]")?.focus();
    });
    await page.type("[data-testid=search-input]", "apotek");
    await page.waitForSelector("[data-testid=search-hit]", { timeout: 8000 });
    const sheetStart = await page.evaluate(() => performance.now());
    await page.evaluate(() => {
      document.querySelector("[data-testid=search-hit]")?.click();
    });
    await page.waitForSelector('[data-testid=bottom-sheet].is-open', { timeout: 4000 });
    const sheetEnd = await page.evaluate(() => performance.now());
    u2Ms = sheetEnd - sheetStart;
    console.log(`U2 sheet open ${u2Ms.toFixed(0)} ms`);

    const gesture = await page.evaluate(async () => {
      const map = window.__zylosMap;
      const frames = [];
      let last = performance.now();
      let running = true;
      const loop = (now) => {
        if (!running) {
          return;
        }
        frames.push(now - last);
        last = now;
        requestAnimationFrame(loop);
      };
      requestAnimationFrame(loop);
      map.panBy([240, 0], { duration: 1500 });
      await new Promise((resolve) => setTimeout(resolve, 1800));
      running = false;
      const avg = frames.reduce((a, b) => a + b, 0) / Math.max(frames.length, 1);
      return {
        fps: 1000 / avg,
        longFrame: Math.max(0, ...frames),
      };
    });
    fps = gesture.fps;
    longFrame = gesture.longFrame;
    console.log(`T4 pan ~${fps.toFixed(1)} FPS, longest frame ${longFrame.toFixed(0)} ms`);
    if (fps < 30 || longFrame > 100) {
      notices.push(`T4 headless pan fps=${fps.toFixed(1)} long=${longFrame.toFixed(0)}ms (GPU-less notice)`);
    }
  } finally {
    await browser.close();
    if (preview.proc) {
      preview.proc.kill();
    }
  }

  const report = {
    generatedAt: new Date().toISOString(),
    l1With: bundle.withMaplibre,
    l1Without: bundle.withoutMaplibre,
    t1Ms,
    t2Ms,
    l2Ms,
    s1P95,
    u2Ms,
    fps,
    longFrame,
    notices,
  };
  writeFileSync(join(root, "data", "tmp", "stage04-benchmark.json"), JSON.stringify(report, null, 2) + "\n");
  updateReadme(report);

  const l1Ok = bundle.withMaplibre <= 900 * 1024;
  const t1Ok = t1Ms != null && t1Ms <= 1500;
  const t2Ok = t2Ms != null && t2Ms <= 2500;
  const l2Ok = l2Ms != null && l2Ms <= 3500;
  const s1Ok = s1P95 != null && s1P95 <= 200;
  const u2Ok = u2Ms != null && u2Ms <= 250;

  if (notices.length) {
    console.log("\nNotices:");
    for (const notice of notices) {
      console.log(`  - ${notice}`);
    }
  }

  if (!l1Ok || !t1Ok || !t2Ok || !l2Ok || !s1Ok || !u2Ok) {
    console.error("stage04-benchmark failed SLO", { l1Ok, t1Ok, t2Ok, l2Ok, s1Ok, u2Ok });
    process.exit(1);
  }
  console.log("stage04-benchmark passed.");
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
