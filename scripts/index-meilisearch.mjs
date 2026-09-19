import { readFileSync, existsSync, mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import pg from "pg";

const { Client } = pg;
const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const INDEX = "zylos";
const TMP_INDEX = "zylos_tmp";
const BATCH_SIZE = 2000;
const SETTINGS_PATH = join(root, "infra", "meilisearch", "index-settings.json");

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

const databaseUrl =
  process.env.DATABASE_URL ??
  "postgres://zylos:zylos@127.0.0.1:5432/zylos";
const meiliUrl = (process.env.MEILI_URL ?? "http://127.0.0.1:7700").replace(/\/$/, "");
const meiliKey = process.env.MEILI_MASTER_KEY;
if (!meiliKey) {
  throw new Error("MEILI_MASTER_KEY is required");
}

const ADDRESS_SQL = `
SELECT
  'addr:' || source || ':' || source_id AS id,
  'address' AS kind,
  source,
  street,
  street_sr_cyrl,
  housenumber,
  postcode,
  building_id::text,
  ST_Y(geom) AS lat,
  ST_X(geom) AS lon,
  trim(street || ' ' || housenumber) AS label
FROM address
WHERE street IS NOT NULL AND trim(street) <> ''
  AND housenumber IS NOT NULL AND trim(housenumber::text) <> ''
ORDER BY source, source_id
`;

const ORG_SQL = `
SELECT
  'org:' || o.source || ':' || o.source_id AS id,
  'organization' AS kind,
  o.source,
  o.name,
  c.slug AS category_slug,
  c.name_sr AS category_name,
  o.tags,
  o.building_id::text,
  ST_Y(o.geom) AS lat,
  ST_X(o.geom) AS lon,
  o.name AS label
FROM organization o
JOIN category c ON c.id = o.category_id
WHERE o.name IS NOT NULL AND trim(o.name) <> ''
ORDER BY o.source, o.source_id
`;

async function meili(method, path, body) {
  const response = await fetch(`${meiliUrl}${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${meiliKey}`,
      "Content-Type": "application/json",
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  let json = null;
  if (text) {
    try {
      json = JSON.parse(text);
    } catch {
      json = { raw: text };
    }
  }
  return { status: response.status, json };
}

async function waitForTask(taskUid, timeoutMs = 10 * 60 * 1000) {
  if (taskUid == null) {
    throw new Error("Meilisearch task uid is missing");
  }
  const started = Date.now();
  let delay = 50;
  while (Date.now() - started < timeoutMs) {
    const { status, json } = await meili("GET", `/tasks/${taskUid}`);
    if (status !== 200) {
      throw new Error(`task ${taskUid} lookup failed: ${status} ${JSON.stringify(json)}`);
    }
    if (json.status === "succeeded") {
      return json;
    }
    if (json.status === "failed" || json.status === "canceled") {
      throw new Error(`task ${taskUid} ${json.status}: ${JSON.stringify(json.error ?? json)}`);
    }
    await new Promise((resolve) => setTimeout(resolve, delay));
    delay = Math.min(500, delay * 1.5);
  }
  throw new Error(`task ${taskUid} timed out after ${timeoutMs}ms`);
}

async function enqueue(method, path, body) {
  const { status, json } = await meili(method, path, body);
  if (status === 404 && method === "DELETE") {
    return null;
  }
  if (status !== 202 && status !== 200) {
    throw new Error(`${method} ${path} failed: ${status} ${JSON.stringify(json)}`);
  }
  if (json?.taskUid != null) {
    await waitForTask(json.taskUid);
  }
  return json;
}

function normalizeTags(value) {
  if (Array.isArray(value)) {
    return value.map((item) => String(item)).filter(Boolean);
  }
  if (typeof value === "string" && value.length > 0) {
    return [value];
  }
  return [];
}

function finiteCoord(value) {
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

function withUid(doc) {
  // Meilisearch primary keys cannot contain ':'; API still returns spec ids like addr:rgz:123.
  return { uid: String(doc.id).replaceAll(":", "_"), ...doc };
}

function toAddressDoc(row) {
  const lat = finiteCoord(row.lat);
  const lon = finiteCoord(row.lon);
  if (lat == null || lon == null) {
    return null;
  }
  const doc = {
    id: row.id,
    kind: "address",
    source: row.source,
    street: row.street,
    housenumber: String(row.housenumber),
    label: row.label,
    _geo: { lat, lng: lon },
  };
  if (row.street_sr_cyrl) {
    doc.street_sr_cyrl = row.street_sr_cyrl;
  }
  if (row.postcode) {
    doc.postcode = row.postcode;
  }
  if (row.building_id) {
    doc.building_id = row.building_id;
  }
  return withUid(doc);
}

function toOrgDoc(row) {
  const lat = finiteCoord(row.lat);
  const lon = finiteCoord(row.lon);
  if (lat == null || lon == null || !row.category_slug) {
    return null;
  }
  const doc = {
    id: row.id,
    kind: "organization",
    source: row.source,
    name: row.name,
    category_slug: row.category_slug,
    category_name: row.category_name,
    label: row.label ?? row.name,
    _geo: { lat, lng: lon },
  };
  if (row.building_id) {
    doc.building_id = row.building_id;
  }
  const tags = normalizeTags(row.tags);
  if (tags.length > 0) {
    doc.tags = tags;
  }
  return withUid(doc);
}

async function uploadDocs(uid, docs) {
  if (docs.length === 0) {
    return;
  }
  await enqueue("POST", `/indexes/${uid}/documents`, docs);
}

async function indexQuery(client, sql, uid, mapRow) {
  let uploaded = 0;
  let skipped = 0;
  await client.query("BEGIN");
  try {
    await client.query(`DECLARE idx_cur NO SCROLL CURSOR FOR ${sql}`);
    for (;;) {
      const batch = await client.query(`FETCH ${BATCH_SIZE} FROM idx_cur`);
      if (batch.rows.length === 0) {
        break;
      }
      const docs = [];
      for (const row of batch.rows) {
        const doc = mapRow(row);
        if (doc) {
          docs.push(doc);
        } else {
          skipped += 1;
        }
      }
      await uploadDocs(uid, docs);
      uploaded += docs.length;
      process.stdout.write(`  ${uploaded} documents (skipped ${skipped})\r`);
    }
    await client.query("CLOSE idx_cur");
    await client.query("COMMIT");
  } catch (err) {
    await client.query("ROLLBACK").catch(() => {});
    throw err;
  }
  process.stdout.write("\n");
  return { uploaded, skipped };
}

async function indexExists(uid) {
  const { status } = await meili("GET", `/indexes/${uid}`);
  return status === 200;
}

async function ensureIndex(uid) {
  if (await indexExists(uid)) {
    return;
  }
  await enqueue("POST", "/indexes", { uid, primaryKey: "uid" });
}

async function main() {
  const settings = JSON.parse(readFileSync(SETTINGS_PATH, "utf8"));
  const client = new Client({ connectionString: databaseUrl });
  await client.connect();

  try {
    console.log(`Indexing PostGIS → Meilisearch (${meiliUrl}), swap via ${TMP_INDEX}`);
    if (await indexExists(TMP_INDEX)) {
      console.log(`Deleting leftover ${TMP_INDEX}...`);
      await enqueue("DELETE", `/indexes/${TMP_INDEX}`);
    }

    await enqueue("POST", "/indexes", { uid: TMP_INDEX, primaryKey: "uid" });
    await enqueue("PATCH", `/indexes/${TMP_INDEX}/settings`, settings);

    console.log("Indexing addresses...");
    const addresses = await indexQuery(client, ADDRESS_SQL, TMP_INDEX, toAddressDoc);
    console.log("Indexing organizations...");
    const orgs = await indexQuery(client, ORG_SQL, TMP_INDEX, toOrgDoc);

    await ensureIndex(INDEX);
    console.log(`Swapping ${TMP_INDEX} ↔ ${INDEX}...`);
    await enqueue("POST", "/swap-indexes", [{ indexes: [TMP_INDEX, INDEX] }]);
    if (await indexExists(TMP_INDEX)) {
      await enqueue("DELETE", `/indexes/${TMP_INDEX}`);
    }

    const report = {
      indexedAt: new Date().toISOString(),
      index: INDEX,
      addresses: addresses.uploaded,
      organizations: orgs.uploaded,
      skipped: addresses.skipped + orgs.skipped,
    };
    const tmpDir = join(root, "data", "tmp");
    mkdirSync(tmpDir, { recursive: true });
    writeFileSync(join(tmpDir, "stage03-index.json"), JSON.stringify(report, null, 2) + "\n");
    console.log(
      `Indexed ${report.addresses} addresses and ${report.organizations} organizations (skipped ${report.skipped}).`,
    );
  } finally {
    await client.end();
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
