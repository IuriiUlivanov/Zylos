import { afterEach, describe, expect, it } from "vitest";
import { readUrlState, writeUrlState } from "./urlState";

describe("urlState", () => {
  afterEach(() => {
    window.history.replaceState(null, "", "/");
  });

  it("reads q and sel from the query string", () => {
    expect(readUrlState("?q=apotek&sel=org:osm:n1")).toEqual({
      q: "apotek",
      sel: "org:osm:n1",
      bldg: null,
      org: null,
    });
  });

  it("treats a missing or empty sel as null", () => {
    expect(readUrlState("?q=futo")).toEqual({ q: "futo", sel: null, bldg: null, org: null });
    expect(readUrlState("?q=futo&sel=")).toEqual({ q: "futo", sel: null, bldg: null, org: null });
    expect(readUrlState("")).toEqual({ q: "", sel: null, bldg: null, org: null });
  });

  it("writes shareable ?q=&sel= into the URL without adding a history entry", () => {
    window.history.replaceState(null, "", "/");
    writeUrlState({ q: "apotek", sel: "org:osm:n1", bldg: null, org: null });
    expect(`${window.location.pathname}${window.location.search}`).toBe("/?q=apotek&sel=org%3Aosm%3An1");
  });

  it("keeps q, sel, bldg and org together", () => {
    window.history.replaceState(null, "", "/");
    writeUrlState({
      q: "apotek",
      sel: "org:osm:n1",
      bldg: "bldg-1",
      org: "org:osm:n1",
    });
    expect(readUrlState(window.location.search)).toEqual({
      q: "apotek",
      sel: "org:osm:n1",
      bldg: "bldg-1",
      org: "org:osm:n1",
    });
  });

  it("clears the query string when both q and sel are empty", () => {
    window.history.replaceState(null, "", "/?q=apotek&sel=org:1");
    writeUrlState({ q: "", sel: null, bldg: null, org: null });
    expect(window.location.search).toBe("");
  });
});
