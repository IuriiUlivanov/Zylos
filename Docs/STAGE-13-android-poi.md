# Этап 13. Android: org-пины и Search Multi (черновик)

> **Статус:** черновик навигации. Детальная спека — после закрытия [STAGE-12-android-search.md](STAGE-12-android-search.md).

Связан с [design/MOBILE-POI-ZOOM.md](design/MOBILE-POI-ZOOM.md), [MOBILE.md](MOBILE.md) §6 этап 5, [STAGE-12-android-search.md](STAGE-12-android-search.md).

**Цель (кратко):**

- `GET /v1/orgs?bbox=` → GeoJSON source `org-pins`, debounce 300 ms, limit по zoom (§4.3 MOBILE-POI-ZOOM)
- Mobile Style JSON: rank/class фильтры на `poi-dot` / `poi-label`
- Search Multi: ≤ 15 search-пинов, browse-пины скрыты
- Пороги **B5**, **T4** (FPS при org-пинах)

**Не входит:** transit UI, API `display_rank` (желательно v1.1, не блокер z15–z16).

Полная спека будет добавлена после `stage12-verify` → exit 0.
