import { SEARCH_MIN_LENGTH } from "../lib/constants";
import { SearchDropdown } from "./SearchDropdown";
import type { SearchHit, SearchUiError } from "../types/search";

interface SearchBarProps {
  query: string;
  hits: SearchHit[];
  hitsQuery: string;
  loading: boolean;
  error: SearchUiError;
  errorMessage: string | null;
  dropdownOpen: boolean;
  onQueryChange: (value: string) => void;
  onSelect: (hit: SearchHit) => void;
  onFocus: () => void;
  onBlur: () => void;
}

export function SearchBar({
  query,
  hits,
  hitsQuery,
  loading,
  error,
  errorMessage,
  dropdownOpen,
  onQueryChange,
  onSelect,
  onFocus,
  onBlur,
}: SearchBarProps) {
  return (
    <div className="search-bar">
      <form
        className="search-bar__field"
        onSubmit={(event) => {
          event.preventDefault();
          if (hits[0]) {
            onSelect(hits[0]);
          }
        }}
      >
        <span className="search-bar__icon" aria-hidden="true">
          ⌕
        </span>
        <input
          id="search"
          data-testid="search-input"
          className="search-bar__input"
          type="search"
          value={query}
          placeholder="Pretraga…"
          autoComplete="off"
          autoCorrect="off"
          autoCapitalize="off"
          spellCheck={false}
          enterKeyHint="search"
          aria-label="Pretraga"
          onChange={(event) => {
            const t0 = performance.now();
            onQueryChange(event.target.value);
            if (window.__zylosPerf) {
              window.__zylosPerf.lastInputMs = performance.now() - t0;
            }
          }}
          onFocus={onFocus}
          onBlur={onBlur}
        />
        {query.length > 0 ? (
          <button
            type="button"
            className="search-bar__clear"
            aria-label="Obriši"
            onMouseDown={(event) => event.preventDefault()}
            onClick={() => onQueryChange("")}
          >
            ×
          </button>
        ) : null}
        {loading && query.length >= SEARCH_MIN_LENGTH ? (
          <span className="search-bar__spinner" aria-hidden="true" />
        ) : null}
      </form>
      {loading && query.length >= SEARCH_MIN_LENGTH ? (
        <div className="search-bar__progress" aria-hidden="true" />
      ) : null}
      <SearchDropdown
        hits={hits}
        hitsQuery={hitsQuery}
        loading={loading}
        error={error}
        errorMessage={errorMessage}
        visible={dropdownOpen}
        onSelect={onSelect}
      />
    </div>
  );
}
