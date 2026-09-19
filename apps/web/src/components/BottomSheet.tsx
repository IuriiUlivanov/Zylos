import { useRef } from "react";
import { SHEET_ANIMATION_MS } from "../lib/constants";
import type { BuildingDetail } from "../types/building";
import type { OrgDetail } from "../types/org";
import type { Selection, SheetState } from "../types/app";
import { BuildingSheet } from "./BuildingSheet";
import { KindBadge } from "./KindBadge";
import { OrgCard } from "./OrgCard";

interface BottomSheetProps {
  sheet: SheetState;
  selection: Selection | null;
  building: BuildingDetail | null;
  org: OrgDetail | null;
  loadingBuilding?: boolean;
  onClose: () => void;
  onBack: () => void;
  onSelectOrg: (id: string) => void;
}

export function BottomSheet({
  sheet,
  selection,
  building,
  org,
  loadingBuilding = false,
  onClose,
  onBack,
  onSelectOrg,
}: BottomSheetProps) {
  const touchStartY = useRef<number | null>(null);
  const open = sheet !== "closed" && (selection !== null || building !== null || org !== null || loadingBuilding);

  const onSwipeDown = (): void => {
    if (sheet === "organization") {
      onBack();
      return;
    }
    onClose();
  };

  return (
    <section
      className={`bottom-sheet${open ? " is-open" : ""}`}
      data-testid="bottom-sheet"
      data-state={open ? sheet : "closed"}
      aria-hidden={!open}
      onTransitionEnd={(event) => {
        if (event.propertyName !== "transform") {
          return;
        }
        if (window.__zylosPerf && open) {
          window.__zylosPerf.sheetOpenedMs = SHEET_ANIMATION_MS;
        }
      }}
    >
      <div
        className="bottom-sheet__handle-wrap"
        onTouchStart={(event) => {
          touchStartY.current = event.touches[0]?.clientY ?? null;
        }}
        onTouchEnd={(event) => {
          const start = touchStartY.current;
          touchStartY.current = null;
          const end = event.changedTouches[0]?.clientY;
          if (start != null && end != null && end - start > 40) {
            onSwipeDown();
          }
        }}
      >
        <div className="bottom-sheet__handle" />
      </div>
      {sheet === "organization" && org ? (
        <OrgCard org={org} onBack={onBack} onClose={onClose} />
      ) : sheet === "building" ? (
        <BuildingSheet
          building={building}
          loading={loadingBuilding}
          onSelectOrg={onSelectOrg}
          onClose={onClose}
        />
      ) : selection ? (
        <div className="bottom-sheet__body">
          <div className="bottom-sheet__copy">
            {selection.type === "hit" ? <KindBadge kind={selection.kind} /> : null}
            <div>
              <h2 className="bottom-sheet__title">{selection.title}</h2>
              <p className="bottom-sheet__sub">{selection.subtitle}</p>
            </div>
          </div>
          <button type="button" className="bottom-sheet__close" aria-label="Zatvori" onClick={onClose}>
            ×
          </button>
        </div>
      ) : loadingBuilding ? (
        <BuildingSheet building={null} loading onSelectOrg={onSelectOrg} onClose={onClose} />
      ) : null}
    </section>
  );
}
