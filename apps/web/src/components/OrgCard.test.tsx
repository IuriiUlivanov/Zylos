import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { OrgCard } from "./OrgCard";
import type { OrgDetail } from "../types/org";

const org: OrgDetail = {
  id: "org:osm:n13868149517",
  name: "Galen pharm",
  source: "osm",
  category_slug: "pharmacy",
  category_name: "Apoteka",
  phones: ["+381 21 2701042"],
  website: "https://www.galenpharm.com",
  hours: "Mo-Su 09:00-22:00",
  floor: null,
  tags: [],
  address: { label: "Bulevar oslobođenja 119", street: "Bulevar oslobođenja", housenumber: "119" },
  building_id: "bldg-1",
  location: { lon: 19.8435, lat: 45.2458 },
};

describe("OrgCard", () => {
  it("shows name, phone, hours and website from the directory", () => {
    render(<OrgCard org={org} onBack={() => undefined} onClose={() => undefined} />);
    expect(screen.getByTestId("org-card")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Galen pharm" })).toBeInTheDocument();
    expect(screen.getByTestId("org-phone")).toHaveAttribute("href", "tel:+381 21 2701042");
    expect(screen.getByTestId("org-hours")).toHaveTextContent("Mo-Su 09:00-22:00");
    expect(screen.getByText("www.galenpharm.com")).toBeInTheDocument();
  });
});
