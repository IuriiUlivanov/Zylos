import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { spawn, execFileSync } from "node:child_process";
import { gzipSync } from "node:zlib";

export const root = join(dirname(fileURLToPath(import.meta.url)), "..");
export const webDir = join(root, "apps", "web");

export function loadEnvFile(pathname) {
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

export const apiUrl = (process.env.API_URL ?? `http://127.0.0.1:${process.env.API_PORT ?? "3000"}`).replace(
  /\/$/,
  "",
);
export const tilesUrl = (process.env.VITE_TILES_URL ?? process.env.TILES_URL ?? "http://127.0.0.1:8080").replace(
  /\/$/,
  "",
);

export function findBrowser() {
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
    "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
  ];
  return candidates.find((path) => existsSync(path)) || null;
}

export async function waitForUrl(url, timeoutMs = 30000) {
  const started = Date.now();
  let lastError = "";
  while (Date.now() - started < timeoutMs) {
    try {
      const response = await fetch(url, { redirect: "manual" });
      if (response.status < 500) {
        return true;
      }
      lastError = `HTTP ${response.status}`;
    } catch (err) {
      lastError = err instanceof Error ? err.message : String(err);
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error(`timeout waiting for ${url}: ${lastError}`);
}

export async function isUp(url) {
  try {
    const response = await fetch(url, { redirect: "manual", signal: AbortSignal.timeout(1500) });
    return response.status < 500;
  } catch {
    return false;
  }
}

export async function resolveWebUrl() {
  if (process.env.WEB_URL) {
    const url = process.env.WEB_URL.replace(/\/$/, "");
    await waitForUrl(url, 5000).catch(() => {
      throw new Error(`WEB_URL is set but not reachable: ${url}`);
    });
    return { url, proc: null };
  }
  const dev = "http://127.0.0.1:5173";
  const preview = "http://127.0.0.1:4173";
  if (await isUp(dev)) {
    return { url: dev, proc: null };
  }
  if (await isUp(preview)) {
    return { url: preview, proc: null };
  }
  const proc = spawn(
    "npm",
    ["run", "preview", "--", "--host", "127.0.0.1", "--port", "4173", "--strictPort"],
    {
      cwd: webDir,
      stdio: "inherit",
      shell: true,
    },
  );
  try {
    await waitForUrl(preview, 45000);
  } catch (err) {
    proc.kill();
    throw err;
  }
  return { url: preview, proc };
}

export function gzipBundle(distDir) {
  const assets = join(distDir, "assets");
  const files = existsSync(assets)
    ? readdirSync(assets).filter((name) => /\.(js|css)$/.test(name))
    : [];
  let withMaplibre = 0;
  let withoutMaplibre = 0;
  const details = [];
  for (const name of files) {
    const bytes = gzipSync(readFileSync(join(assets, name))).length;
    details.push({ name, gzip: bytes });
    withMaplibre += bytes;
    if (!/maplibre/i.test(name)) {
      withoutMaplibre += bytes;
    }
  }
  return { withMaplibre, withoutMaplibre, details };
}

export function scanDistSecrets(distDir) {
  const found = [];
  const needles = ["MEILI_MASTER_KEY", "DATABASE_URL"];
  function walk(dir) {
    if (!existsSync(dir)) {
      return;
    }
    for (const name of readdirSync(dir)) {
      const pathname = join(dir, name);
      const stat = statSync(pathname);
      if (stat.isDirectory()) {
        walk(pathname);
        continue;
      }
      if (!/\.(js|css|html|map|json)$/.test(name)) {
        continue;
      }
      const text = readFileSync(pathname, "utf8");
      for (const needle of needles) {
        if (text.includes(needle)) {
          found.push(`${name}:${needle}`);
        }
      }
    }
  }
  walk(distDir);
  return found;
}

export function docker(...args) {
  return execFileSync("docker", args, {
    cwd: root,
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
  }).trim();
}

export function npmRun(args, cwd) {
  execFileSync("npm", args, {
    cwd,
    stdio: "inherit",
    shell: true,
  });
}

export function percentile(values, p) {
  if (values.length === 0) {
    return NaN;
  }
  const sorted = [...values].sort((a, b) => a - b);
  const idx = Math.min(sorted.length - 1, Math.max(0, Math.ceil((p / 100) * sorted.length) - 1));
  return sorted[idx];
}
