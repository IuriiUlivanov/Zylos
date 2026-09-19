import { describe, expect, it } from "vitest";
import {
  DEFAULT_CENTER,
  DEFAULT_ZOOM,
  SEARCH_DEBOUNCE_MS,
  SEARCH_LIMIT,
  SEARCH_MIN_LENGTH,
  SHEET_ANIMATION_MS,
  SHEET_BUILDING_PX,
  SHEET_PEEK_PX,
  ORG_PINS_DEBOUNCE_MS,
  ORG_PINS_LIMIT,
  ORG_PINS_MIN_ZOOM,
} from "./constants";

describe("stage 4 constants", () => {
  it("keeps search debounce at 150 ms and min query length 2", () => {
    expect(SEARCH_DEBOUNCE_MS).toBe(150);
    expect(SEARCH_MIN_LENGTH).toBe(2);
    expect(SEARCH_LIMIT).toBe(15);
  });

  it("centers the map on Trg slobode at zoom 14", () => {
    expect(DEFAULT_CENTER).toEqual({ lon: 19.845, lat: 45.255 });
    expect(DEFAULT_ZOOM).toBe(14);
  });

  it("uses a 88 px peek sheet that animates within 250 ms", () => {
    expect(SHEET_PEEK_PX).toBe(88);
    expect(SHEET_ANIMATION_MS).toBeLessThanOrEqual(250);
  });

  it("keeps building sheet, org pin debounce and bbox cap from stage 5", () => {
    expect(SHEET_BUILDING_PX).toBe(120);
    expect(ORG_PINS_DEBOUNCE_MS).toBe(300);
    expect(ORG_PINS_MIN_ZOOM).toBe(15);
    expect(ORG_PINS_LIMIT).toBe(200);
  });
});
