import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { SearchDropdown } from "./SearchDropdown";
import { addressHit, orgHit } from "../test/fixtures";

const hits = [orgHit(), addressHit(), orgHit({ id: "org:osm:n2", label: "Apoteka Benu" })];

describe("SearchDropdown", () => {
  it("renders nothing when hidden", () => {
    const { container } = render(
      <SearchDropdown
        hits={hits}
        hitsQuery="apotek"
        loading={false}
        error={null}
        errorMessage={null}
        visible={false}
        onSelect={() => undefined}
      />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it("lists hits with data-testid and data-query for verify S3/F2", async () => {
    const onSelect = vi.fn();
    const user = userEvent.setup();
    render(
      <SearchDropdown
        hits={hits}
        hitsQuery="apotek"
        loading={false}
        error={null}
        errorMessage={null}
        visible
        onSelect={onSelect}
      />,
    );

    const dropdown = screen.getByTestId("search-dropdown");
    expect(dropdown).toHaveAttribute("data-query", "apotek");
    const rows = screen.getAllByTestId("search-hit");
    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveAttribute("data-id", "org:osm:n1");
    expect(rows[0]).toHaveAttribute("data-kind", "organization");
    expect(rows[0]).toHaveAttribute("data-lat", "45.2493968");
    expect(rows[0]).toHaveAttribute("data-lon", "19.8409398");
    expect(screen.getByText("Zelena apoteka")).toBeInTheDocument();
    expect(rows[0]).toHaveTextContent("Apoteka");

    await user.click(rows[0]!);
    expect(onSelect).toHaveBeenCalledWith(hits[0]);
  });

  it("shows the unavailable search message", () => {
    render(
      <SearchDropdown
        hits={[]}
        hitsQuery="apotek"
        loading={false}
        error="unavailable"
        errorMessage="Поиск временно недоступен"
        visible
        onSelect={() => undefined}
      />,
    );
    expect(screen.getByRole("alert")).toHaveTextContent("Поиск временно недоступен");
  });

  it("shows the empty-results status", () => {
    render(
      <SearchDropdown
        hits={[]}
        hitsQuery="zzzz"
        loading={false}
        error="empty"
        errorMessage="Ništa nije pronađeno"
        visible
        onSelect={() => undefined}
      />,
    );
    expect(screen.getByText("Ništa nije pronađeno")).toBeInTheDocument();
  });
});
