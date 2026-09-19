import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

const here = dirname(fileURLToPath(import.meta.url));

export default defineConfig({
  plugins: [react()],
  envDir: resolve(here, "../.."),
  server: {
    port: 5173,
    host: true,
    strictPort: true,
    fs: {
      allow: [resolve(here, "../..")],
    },
  },
  preview: {
    port: 4173,
    host: true,
    strictPort: true,
  },
  build: {
    target: "es2022",
    sourcemap: false,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes("maplibre-gl")) {
            return "maplibre";
          }
        },
      },
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: "./src/test/setup.ts",
    restoreMocks: true,
    isolate: false,
  },
});
