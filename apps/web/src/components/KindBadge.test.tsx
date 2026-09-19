import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { KindBadge } from "./KindBadge";

describe("KindBadge", () => {
  it("marks organization vs address hits", () => {
    const { rerender, container } = render(<KindBadge kind="organization" />);
    expect(container.firstChild).toHaveAttribute("data-kind", "organization");
    expect(container.textContent).toBe("Org");

    rerender(<KindBadge kind="address" />);
    expect(container.firstChild).toHaveAttribute("data-kind", "address");
    expect(container.textContent).toBe("Adr");
  });
});
