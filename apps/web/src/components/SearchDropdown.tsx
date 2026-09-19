import { KindBadge } from "./KindBadge";
import { categoryLabel } from "../lib/categories";
import type { SearchHit, SearchUiError } from "../types/search";

interface SearchDropdownProps {
  hits: SearchHit[];
  hitsQuery: string;
  loading: boolean;
  error: SearchUiError;
  errorMessage: string | null;
  visible: boolean;
  onSelect: (hit: SearchHit) => void;
}

export function SearchDropdown({
  hits,
  hitsQuery,
  loading,
  error,
  errorMessage,
  visible,
  onSelect,
}: SearchDropdownProps) {
  if (!visible) {
    return null;
  }

  return (
    <div
      className="search-dropdown"
      data-testid="search-dropdown"
      data-query={hitsQuery}
      role="listbox"
    >
      {loading ? <div className="search-dropdown__status">Tražim…</div> : null}
      {error && error !== "empty" ? (
        <div className="search-dropdown__status search-dropdown__status--error" role="alert">
          {errorMessage}
        </div>
      ) : null}
      {error === "empty" && !loading ? (
        <div className="search-dropdown__status">{errorMessage}</div>
      ) : null}
      {hits.map((hit) => {
        const subtitle =
          hit.kind === "organization"
            ? categoryLabel(hit.category_slug, hit.category_name)
            : "Adresa";
        return (
          <button
            key={hit.id}
            type="button"
            className="search-hit"
            data-testid="search-hit"
            data-id={hit.id}
            data-kind={hit.kind}
            data-lat={String(hit.lat)}
            data-lon={String(hit.lon)}
            role="option"
            onClick={() => onSelect(hit)}
          >
            <KindBadge kind={hit.kind} />
            <span className="search-hit__text">
              <span className="search-hit__label">{hit.label}</span>
              {subtitle ? <span className="search-hit__sub">{subtitle}</span> : null}
            </span>
          </button>
        );
      })}
    </div>
  );
}
