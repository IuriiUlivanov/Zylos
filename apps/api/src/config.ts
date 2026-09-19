function required(name: string): string {
  const value = process.env[name];
  if (!value) {
    throw new Error(`Missing required environment variable ${name}`);
  }
  return value;
}

export const config = {
  port: Number(process.env.PORT ?? 3000),
  meiliUrl: (process.env.MEILI_URL ?? "http://meilisearch:7700").replace(/\/$/, ""),
  meiliKey: required("MEILI_MASTER_KEY"),
  databaseUrl: process.env.DATABASE_URL ?? "",
  corsOrigins: [
    "http://localhost:8080",
    "http://127.0.0.1:8080",
    "http://localhost:5173",
    "http://127.0.0.1:5173",
    "http://localhost:4173",
    "http://127.0.0.1:4173",
  ],
};
