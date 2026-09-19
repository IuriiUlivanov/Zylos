package rs.zylos.novisad.map

object MapDefaults {
    const val LON = 19.845
    const val LAT = 45.255
    const val ZOOM = 14.0
    const val MAX_PITCH = 60.0
    const val MBTILES_ASSET = "novi-sad.mbtiles"
    const val STYLE_ASSET = "style-mobile.json"
    const val WATER_ASSET = "water-fill.geojson"
    /** U2: bottom sheet animation budget. Docs/design/object-card/README.md */
    const val SHEET_ANIMATION_MS = 250
    const val SHEET_STEP1_DP = 48
    const val SHEET_STEP2_RATIO = 0.5f
    const val SHEET_STEP3_RATIO = 1.0f
    const val SHEET_SEARCH_GAP_DP = 8
    const val SHEET_CLOSE_DP = 36

    const val SEARCH_DEBOUNCE_MS = 150L
    const val SEARCH_MIN_LENGTH = 2
    const val SEARCH_LIMIT = 10
    const val FLY_DURATION_MS = 800
    const val FLY_MIN_ZOOM = 16.0
    /** Vertical object position on search flyTo, fraction of map height from the bottom. */
    const val SEARCH_FLYTO_ANCHOR_Y = 0.75f
    const val SEARCH_HISTORY_LIMIT = 10
    const val ORG_PINS_DEBOUNCE_MS = 300L
    const val ORG_PINS_DEBOUNCE_MS_Z18 = 250L
    const val ORG_PINS_DEBOUNCE_MS_Z19 = 200L
    const val ORG_PINS_MIN_ZOOM = 15
    const val ORG_PINS_LIMIT_MAX = 200
    const val SEARCH_MULTI_MAX_PINS = 15
    const val SEARCH_MULTI_MIN_HITS = 3
    const val TOAST_DURATION_MS = 2500
    const val ZOOM_STEP = 1.0
    const val CONTROL_GAP_DP = 8
}
