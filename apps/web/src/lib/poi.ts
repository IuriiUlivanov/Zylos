export function poiName(properties: Record<string, unknown> | null | undefined): string {
  if (!properties) {
    return "POI";
  }
  for (const key of ["name:sr-Latn", "name:latin", "name", "name:en"]) {
    const value = properties[key];
    if (typeof value === "string" && value.trim()) {
      return value;
    }
  }
  return "POI";
}
