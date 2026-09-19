import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "maplibre-gl/dist/maplibre-gl.css";
import "./theme/tokens.css";
import { App } from "./App";

window.__zylosPerf = {
  navigationStart: performance.now(),
  mapCreatedAt: null,
  roadsAt: null,
  buildingsAt: null,
  ttiAt: null,
  lastInputMs: null,
  sheetOpenedMs: null,
  glyphsWaitMs: null,
  clickStartedAt: null,
  buildingListAt: null,
};

const root = document.getElementById("root");
if (!root) {
  throw new Error("root element missing");
}

createRoot(root).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
