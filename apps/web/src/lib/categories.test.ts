import { describe, expect, it } from "vitest";
import { categoryLabel } from "./categories";

describe("categoryLabel", () => {
  it("prefers the API category_name when present", () => {
    expect(categoryLabel("pharmacy", "Аптека")).toBe("Аптека");
  });

  it("falls back to sr-Latn labels for known slugs", () => {
    expect(categoryLabel("pharmacy")).toBe("Apoteka");
    expect(categoryLabel("bank")).toBe("Banka");
  });

  it("humanizes unknown slugs", () => {
    expect(categoryLabel("fast_food_kiosk")).toBe("fast food kiosk");
  });

  it("returns undefined when both slug and name are missing", () => {
    expect(categoryLabel()).toBeUndefined();
  });
});
