# Дизайн Zylos

UI/UX-документация проекта. Продуктовый ориентир Android-клиента — **2GIS (Android)**: карта на весь экран, поиск снизу, карточка объекта над поиском.

![Экран карты — browse idle](map-screen.svg)

Обзор: [map-screen.svg](map-screen.svg). Все пользовательские кейсы (включая промежуточные шаги) — **[screens/](screens/README.md)**.

## Документы

| Документ | Содержание |
|---|---|
| [MOBILE-DESIGN.md](MOBILE-DESIGN.md) | UI/UX Android: компоновка, design tokens, компоненты, чеклист приёмки |
| [MOBILE-POI-ZOOM.md](MOBILE-POI-ZOOM.md) | Плотность POI и org-пинов на карте по zoom (browse / search) |
| [screens/README.md](screens/README.md) | SVG-мокапы экранов по кейсам |
| [object-card/README.md](object-card/README.md) | Карточка объекта: 3 шага, жесты, макеты |
| [route/README.md](route/README.md) | Маршрут transit v2: tab bar, route panel, SVG |
| [object-card/versions/](object-card/versions/README.md) | Changelog макетов (v2 bottom tabs) |

## Связанные документы

- [MOBILE.md](../MOBILE.md) — функциональная спека Android
- [PERFORMANCE.md](../../PERFORMANCE.md) — пороги отзывчивости (U1–U5, T4, B5)
- [ARCHITECTURE.md](../../ARCHITECTURE.md) — общая архитектура
- `apps/android/app/src/main/res/values/colors.xml`, `dimens.xml` — канонические токены Android v1
- `apps/web/src/theme/tokens.css` — референс палитры (веб не развиваем)

При расхождении этаповой спеки и [MOBILE-DESIGN.md](MOBILE-DESIGN.md) — **MOBILE-DESIGN** задаёт визуал; этап — поведение и API.
