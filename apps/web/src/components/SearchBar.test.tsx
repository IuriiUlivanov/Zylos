import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { SearchBar } from "./SearchBar";
import { orgHit } from "../test/fixtures";

describe("SearchBar", () => {
  it("exposes #search / data-testid=search-input for F1", () => {
    render(
      <SearchBar
        query=""
        hits={[]}
        hitsQuery=""
        loading={false}
        error={null}
        errorMessage={null}
        dropdownOpen={false}
        onQueryChange={() => undefined}
        onSelect={() => undefined}
        onFocus={() => undefined}
        onBlur={() => undefined}
      />,
    );
    const input = screen.getByTestId("search-input");
    expect(input).toHaveAttribute("id", "search");
    expect(input).toHaveAttribute("autocomplete", "off");
    expect(input).toHaveAttribute("enterkeyhint", "search");
  });

  it("emits query changes and clears the field", async () => {
    const onQueryChange = vi.fn();
    const user = userEvent.setup();
    render(
      <SearchBar
        query="apotek"
        hits={[orgHit()]}
        hitsQuery="apotek"
        loading={false}
        error={null}
        errorMessage={null}
        dropdownOpen
        onQueryChange={onQueryChange}
        onSelect={() => undefined}
        onFocus={() => undefined}
        onBlur={() => undefined}
      />,
    );

    await user.click(screen.getByRole("button", { name: "Obriši" }));
    expect(onQueryChange).toHaveBeenCalledWith("");
  });

  it("selects the first hit on submit", () => {
    const onSelect = vi.fn();
    const first = orgHit();
    const { container } = render(
      <SearchBar
        query="apotek"
        hits={[first]}
        hitsQuery="apotek"
        loading={false}
        error={null}
        errorMessage={null}
        dropdownOpen
        onQueryChange={() => undefined}
        onSelect={onSelect}
        onFocus={() => undefined}
        onBlur={() => undefined}
      />,
    );

    fireEvent.submit(container.querySelector("form")!);
    expect(onSelect).toHaveBeenCalledWith(first);
  });
});
