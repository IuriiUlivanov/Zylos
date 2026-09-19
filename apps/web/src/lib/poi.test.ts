import { describe, expect, it } from "vitest";
import { poiName } from "./poi";

describe("poiName", () => {
  it("prefers sr-Latn, then latin, then name", () => {
    expect(
      poiName({
        "name:sr-Latn": "Trg slobode",
        "name:latin": "Trg slobode latin",
        name: "Трг слободе",
      }),
    ).toBe("Trg slobode");
    expect(poiName({ "name:latin": "Dunav", name: "Дунав" })).toBe("Dunav");
    expect(poiName({ name: "Beosport" })).toBe("Beosport");
  });

  it("returns POI when properties are empty", () => {
    expect(poiName(null)).toBe("POI");
    expect(poiName({})).toBe("POI");
    expect(poiName({ name: "  " })).toBe("POI");
  });
});
