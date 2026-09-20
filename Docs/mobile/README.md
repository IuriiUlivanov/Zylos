# Мобильный клиент Zylos (Android)

Документация **нативного** Android-клиента для **Grad Novi Sad** (OSM relation `1649672`). Продуктовый ориентир — 2GIS Android: карта на весь экран, поиск снизу, карточка объекта над поиском.

Код: [`apps/android`](../../apps/android/README.md).

## Документы

| Документ | Содержание |
|---|---|
| [MOBILE.md](MOBILE.md) | Функциональная спецификация Android-клиента |
| [mobile_plan.md](mobile_plan.md) | Продуктовый план (исходный ориентир) |
| [design/](design/README.md) | UI/UX, design tokens, SVG-мокапы |

## Этапы реализации

| Этап | Документ |
|---|---|
| 10 — карта, MBTiles | [STAGE-10-android.md](STAGE-10-android.md) |
| 11 — здание + object sheet | [STAGE-11-android-building.md](STAGE-11-android-building.md) |
| 12 — autocomplete поиск | [STAGE-12-android-search.md](STAGE-12-android-search.md) |
| 13 — org-пины, Search Multi | [STAGE-13-android-poi.md](STAGE-13-android-poi.md) |
| 14 — transit маршрут | [STAGE-14-android-transit.md](STAGE-14-android-transit.md) |

## Связанные документы

- [ARCHITECTURE.md](../ARCHITECTURE.md) — контейнеры, API, потоки данных
- [PLAN.md](../PLAN.md) — общий план реализации
- [PERFORMANCE.md](../PERFORMANCE.md) — обязательные SLO (фича не done без порога)

При расхождении этаповой спеки и [design/MOBILE-DESIGN.md](design/MOBILE-DESIGN.md) — **MOBILE-DESIGN** задаёт визуал; этап — поведение и API.
