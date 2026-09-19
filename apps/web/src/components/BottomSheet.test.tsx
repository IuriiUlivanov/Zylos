import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { BottomSheet } from "./BottomSheet";
import { orgHit } from "../test/fixtures";
import { hitSelection } from "../lib/selection";
import type { Selection } from "../types/app";

const hit = hitSelection(orgHit());

const poi: Selection = {
  type: "poi",
  id: "poi:19.84500,45.25500",
  title: "Beosport",
  subtitle: "Iz mape OpenStreetMap",
  lat: 45.255,
  lon: 19.845,
};

const extra = {
  building: null,
  org: null,
  onBack: () => undefined,
  onSelectOrg: () => undefined,
};

describe("BottomSheet", () => {
  it("is closed and aria-hidden when sheet state is closed", () => {
    render(<BottomSheet sheet="closed" selection={hit} onClose={() => undefined} {...extra} />);
    const sheet = screen.getByTestId("bottom-sheet");
    expect(sheet).toHaveAttribute("data-state", "closed");
    expect(sheet).toHaveAttribute("aria-hidden", "true");
    expect(sheet).not.toHaveClass("is-open");
  });

  it("opens peek with the selected hit label and category", () => {
    render(<BottomSheet sheet="peek" selection={hit} onClose={() => undefined} {...extra} />);
    const sheet = screen.getByTestId("bottom-sheet");
    expect(sheet).toHaveAttribute("data-state", "peek");
    expect(sheet).toHaveClass("is-open");
    expect(screen.getByRole("heading", { name: "Zelena apoteka" })).toBeInTheDocument();
    expect(screen.getByText("Apoteka")).toBeInTheDocument();
  });

  it("shows tile POI attribution and no kind badge", () => {
    const { container } = render(<BottomSheet sheet="peek" selection={poi} onClose={() => undefined} {...extra} />);
    expect(screen.getByText("Beosport")).toBeInTheDocument();
    expect(screen.getByText("Iz mape OpenStreetMap")).toBeInTheDocument();
    expect(container.querySelector(".kind-badge")).toBeNull();
  });

  it("closes from the × button", async () => {
    const onClose = vi.fn();
    const user = userEvent.setup();
    render(<BottomSheet sheet="peek" selection={hit} onClose={onClose} {...extra} />);
    await user.click(screen.getByRole("button", { name: "Zatvori" }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
