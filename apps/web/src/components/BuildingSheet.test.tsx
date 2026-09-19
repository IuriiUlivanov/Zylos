import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { BuildingSheet } from "./BuildingSheet";
import type { BuildingDetail } from "../types/building";

const building: BuildingDetail = {
  id: "bldg-1",
  name: "Big Fashion",
  centroid: { lon: 19.8435, lat: 45.2458 },
  geometry: { type: "Polygon", coordinates: [] },
  addresses: [
    {
      id: "addr:osm:n1",
      label: "Bulevar oslobođenja 119",
      street: "Bulevar oslobođenja",
      housenumber: "119",
      source: "osm",
    },
  ],
  organizations: [
    { id: "org:osm:n1", name: "A1", category_slug: "shop", category_name: "Prodavnica", floor: null },
    { id: "org:osm:n2", name: "Adidas", category_slug: "shop", category_name: "Prodavnica", floor: null },
  ],
};

describe("BuildingSheet", () => {
  it("renders the address title and organization rows", () => {
    render(<BuildingSheet building={building} onSelectOrg={() => undefined} onClose={() => undefined} />);
    expect(screen.getByTestId("building-sheet")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Bulevar oslobođenja 119" })).toBeInTheDocument();
    expect(screen.getAllByTestId("building-org")).toHaveLength(2);
    expect(screen.getByText("A1")).toBeInTheDocument();
    expect(screen.getByText("Adidas")).toBeInTheDocument();
  });

  it("selects an organization from the list", async () => {
    const onSelectOrg = vi.fn();
    const user = userEvent.setup();
    render(<BuildingSheet building={building} onSelectOrg={onSelectOrg} onClose={() => undefined} />);
    await user.click(screen.getByText("Adidas"));
    expect(onSelectOrg).toHaveBeenCalledWith("org:osm:n2");
  });
});
