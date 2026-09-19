const UUID_RE =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function orgPublicId(source: string, sourceId: string): string {
  return `org:${source}:${sourceId}`;
}

export function parseOrgPublicId(
  id: string,
): { source: "osm" | "editorial"; sourceId: string } | null {
  if (!id.startsWith("org:")) {
    return null;
  }
  const rest = id.slice(4);
  const sep = rest.indexOf(":");
  if (sep <= 0 || sep === rest.length - 1) {
    return null;
  }
  const source = rest.slice(0, sep);
  const sourceId = rest.slice(sep + 1);
  if (source !== "osm" && source !== "editorial") {
    return null;
  }
  if (!sourceId.trim()) {
    return null;
  }
  return { source, sourceId };
}

export function addressPublicId(source: string, sourceId: string): string {
  return `addr:${source}:${sourceId}`;
}

export function isUuid(value: string): boolean {
  return UUID_RE.test(value);
}

export function addressLabel(street: string | null, housenumber: string | null): string {
  return [street?.trim(), housenumber?.trim()].filter(Boolean).join(" ");
}
