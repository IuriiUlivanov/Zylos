import type { SearchKind } from "../types/search";

export function KindBadge({ kind }: { kind: SearchKind }) {
  const label = kind === "organization" ? "Org" : "Adr";
  return (
    <span className="kind-badge" data-kind={kind} aria-hidden="true">
      {label}
    </span>
  );
}
