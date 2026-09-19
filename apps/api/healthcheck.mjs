const port = process.env.PORT ?? "3000";
const response = await fetch(`http://127.0.0.1:${port}/v1/health`);
const body = await response.json();
if (!response.ok || body.meilisearch !== "ok") {
  process.exit(1);
}
