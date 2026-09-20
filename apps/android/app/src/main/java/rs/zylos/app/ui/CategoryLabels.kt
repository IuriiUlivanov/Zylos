package rs.zylos.app.ui

/** sr-Latn labels for OSM / Meilisearch category slugs — mirrors apps/web/src/lib/categories.ts. */
object CategoryLabels {
    private val srLatn = mapOf(
        "pharmacy" to "Apoteka",
        "bank" to "Banka",
        "cafe" to "Kafić",
        "restaurant" to "Restoran",
        "fast_food" to "Brza hrana",
        "bar" to "Bar",
        "shop" to "Prodavnica",
        "supermarket" to "Supermarket",
        "hospital" to "Bolnica",
        "clinic" to "Klinika",
        "school" to "Škola",
        "fuel" to "Benzinska stanica",
        "parking" to "Parking",
        "other" to "Ostalo",
    )

    fun label(slug: String?, name: String? = null): String? {
        val named = name?.trim()?.takeIf { it.isNotEmpty() }
        if (named != null) {
            return named
        }
        val key = slug?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return srLatn[key] ?: key.replace('_', ' ')
    }
}
