export interface UrlState {
  q: string;
  sel: string | null;
  bldg: string | null;
  org: string | null;
}

export function readUrlState(search = window.location.search): UrlState {
  const params = new URLSearchParams(search);
  const q = params.get("q") ?? "";
  const sel = params.get("sel");
  const bldg = params.get("bldg");
  const org = params.get("org");
  return {
    q,
    sel: sel && sel.length > 0 ? sel : null,
    bldg: bldg && bldg.length > 0 ? bldg : null,
    org: org && org.length > 0 ? org : null,
  };
}

export function writeUrlState(state: UrlState): void {
  const params = new URLSearchParams();
  if (state.q) {
    params.set("q", state.q);
  }
  if (state.sel) {
    params.set("sel", state.sel);
  }
  if (state.bldg) {
    params.set("bldg", state.bldg);
  }
  if (state.org) {
    params.set("org", state.org);
  }
  const query = params.toString();
  const next = `${window.location.pathname}${query ? `?${query}` : ""}`;
  const current = `${window.location.pathname}${window.location.search}`;
  if (next !== current) {
    history.replaceState(null, "", next);
  }
}
