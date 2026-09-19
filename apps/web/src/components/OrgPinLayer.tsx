import { memo } from "react";
import type { OrgPin } from "../types/org";

interface OrgPinLayerProps {
  pins: OrgPin[];
}

export const OrgPinLayer = memo(function OrgPinLayer({ pins }: OrgPinLayerProps) {
  if (pins.length === 0) {
    return null;
  }
  return (
    <div className="org-pin-hits" aria-hidden="true">
      {pins.map((pin) => (
        <span
          key={pin.id}
          data-testid="org-pin"
          data-id={pin.id}
          data-lon={String(pin.lon)}
          data-lat={String(pin.lat)}
        />
      ))}
    </div>
  );
});
