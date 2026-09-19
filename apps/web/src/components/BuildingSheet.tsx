import { categoryLabel } from "../lib/categories";
import type { BuildingDetail } from "../types/building";

interface BuildingSheetProps {
  building: BuildingDetail | null;
  loading?: boolean;
  onSelectOrg: (id: string) => void;
  onClose: () => void;
}

export function BuildingSheet({ building, loading = false, onSelectOrg, onClose }: BuildingSheetProps) {
  const title = building?.addresses[0]?.label || building?.name || "Zgrada";
  const extraAddresses = building?.addresses.slice(1) ?? [];

  if (
    building &&
    building.organizations.length > 0 &&
    window.__zylosPerf?.clickStartedAt != null &&
    window.__zylosPerf.buildingListAt == null
  ) {
    window.__zylosPerf.buildingListAt = performance.now();
  }

  return (
    <div className="building-sheet" data-testid="building-sheet">
      <div className="bottom-sheet__body">
        <div className="bottom-sheet__copy">
          <div>
            <h2 className="bottom-sheet__title">{title}</h2>
            <p className="bottom-sheet__sub">
              {loading && !building
                ? "Učitavanje…"
                : extraAddresses.length > 0
                  ? extraAddresses.map((item) => item.label).join(" · ")
                  : building?.name && building.addresses[0]
                    ? building.name
                    : "Zgrada"}
            </p>
          </div>
        </div>
        <button type="button" className="bottom-sheet__close" aria-label="Zatvori" onClick={onClose}>
          ×
        </button>
      </div>
      {extraAddresses.length > 0 ? (
        <ul className="building-sheet__addresses">
          {building?.addresses.map((address) => (
            <li key={address.id}>{address.label}</li>
          ))}
        </ul>
      ) : null}
      <ul className="building-sheet__orgs">
        {(building?.organizations ?? []).map((org) => (
          <li key={org.id}>
            <button
              type="button"
              className="building-org"
              data-testid="building-org"
              data-id={org.id}
              onClick={() => onSelectOrg(org.id)}
            >
              <span className="building-org__name">{org.name}</span>
              <span className="building-org__cat">
                {categoryLabel(org.category_slug ?? undefined, org.category_name ?? undefined) ??
                  (org.floor ? `Sprat ${org.floor}` : "Organizacija")}
              </span>
            </button>
          </li>
        ))}
      </ul>
      {building && building.organizations.length === 0 && !loading ? (
        <p className="building-sheet__empty">Nema organizacija u zgradi</p>
      ) : null}
    </div>
  );
}
