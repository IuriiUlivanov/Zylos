const CATEGORY_SR: Record<string, string> = {
  pharmacy: "Apoteka",
  bank: "Banka",
  cafe: "Kafić",
  restaurant: "Restoran",
  fast_food: "Brza hrana",
  bar: "Bar",
  shop: "Prodavnica",
  supermarket: "Supermarket",
  hospital: "Bolnica",
  clinic: "Klinika",
  school: "Škola",
  fuel: "Benzinska stanica",
  parking: "Parking",
  other: "Ostalo",
};

export function categoryLabel(slug?: string, name?: string): string | undefined {
  if (name && name.trim()) {
    return name;
  }
  if (!slug) {
    return undefined;
  }
  return CATEGORY_SR[slug] ?? slug.replace(/_/g, " ");
}
