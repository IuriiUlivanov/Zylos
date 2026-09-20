# POI на карте Android: ранжирование по zoom

Спецификация **прогрессивного отображения мест** на карте мобильного клиента. Продуктовый ориентир — 2ГИС: при увеличении zoom появляется больше мест, но **не все сразу**; в режиме поиска — отдельные правила.

Связанные документы:

- [MOBILE-DESIGN.md](MOBILE-DESIGN.md) — UI/UX Android, design tokens
- [MOBILE.md](../MOBILE.md) — общая спецификация Android-клиента
- [STAGE-10-android.md](../STAGE-10-android.md) — офлайн-карта, Style JSON
- [PERFORMANCE.md](../../PERFORMANCE.md) — SLO T4 (FPS), B5 (лимит org-пинов)
- [ARCHITECTURE.md](../../ARCHITECTURE.md) — `/v1/orgs?bbox=`, `/v1/search`

**Область:** `apps/android`, mobile-сборка Style JSON (на базе `infra/preview/style.json`). Веб (`apps/web`) не развиваем; таблицы ниже — **целевое поведение Android v1**.

---

## 1. Два слоя POI

| Слой | Источник | Назначение | Кликабельность |
|---|---|---|---|
| **Декоративный OSM POI** | MBTiles, source-layer `poi` | Фон карты, узнаваемость района | v1: только просмотр; карточка org — через API |
| **Справочник Zylos** | `GET /v1/orgs?bbox=` → PostGIS | Организации с категорией и именем | v1: тап → `/orgs/:id` или здание |

OSM POI из тайлов **не** заменяют справочник. Источник истины для «кто здесь сидит» — PostGIS ([MOBILE.md](../MOBILE.md) §2.3).

**Режимы карты:**

- **Browse** — пользователь двигает карту без активного поиска; org-пины по bbox + ослабленные OSM POI.
- **Search Single** — выбран один hit; один маркер + flyTo.
- **Search Multi** — категорийный/широкий запрос; пины всех hits + список в sheet.

В search-режимах browse org-пины **скрываются**; OSM POI из MBTiles — по таблице §3, но с пониженным приоритетом (opacity 0.5 или minzoom +1).

---

## 2. Доступные параметры

### 2.1. MBTiles, слой `poi` (OpenMapTiles / Planetiler)

| Параметр | Тип | Смысл | Для ранжирования |
|---|---|---|---|
| **`rank`** | int | Локальная важность POI в ячейке сетки; **меньше = важнее** | ★★★ основной фильтр |
| **`class`** | string | Обобщённая категория: `hospital`, `school`, `shop`, `cafe`, `bank`… | ★★★ whitelist/blacklist по zoom |
| **`subclass`** | string | Исходный OSM-тег: `pharmacy`, `fast_food`, `supermarket`… | ★★ уточнение |
| **`agg_stop`** | 0 / 1 | Главная платформа ОТ-остановки | ★★ убрать дубли остановок |
| **`name`**, `name:latin`, `name:sr-Latn`… | string | Подпись | ★ подписи только у named |
| **`indoor`**, **`level`**, **`layer`** | string / int | Этаж, вертикальный слой | ★ indoor — backlog |
| **`osm_id`** | int | ID OSM | идентификация, не rank |

OpenMapTiles дополнительно задаёт базовый приоритет через `class` (например hospital ≈ 20, shop ≈ 400, fast_food ≈ 600).

**Текущее состояние стиля** (`infra/preview/style.json`):

- `poi-dot`: minzoom **15**, **без** фильтра по `rank` → много точек.
- `poi-label`: minzoom **17**, `rank ≤ 12`.

Для Android — **отдельная mobile-копия стиля** с таблицей §3 (не ломать веб-референс без необходимости).

### 2.2. PostGIS, `GET /v1/orgs?bbox=`

Сейчас API отдаёт: `id`, `name`, `category_slug`, `lon`, `lat`. Сортировка: `ORDER BY name` — **не по важности**.

Поля в БД (`organization`), полезные для rank (v1.1 — в ответ API):

| Поле | Для rank |
|---|---|
| **`category_slug`** | ★★★ tier по типу места |
| **`source`** (`osm` / `editorial`) | ★★ editorial выше |
| **`phones`**, **`website`** | ★ «полнота» карточки |
| **`geom`** | ★★ расстояние до центра bbox |
| **`tags[]`** | ★ brand / cuisine — позже |

**Целевое поле API:** `display_rank: int` (меньше = показывать раньше).

### 2.3. Поиск, `GET /v1/search`

| Поле | Для отображения на карте |
|---|---|
| Порядок hits (Meilisearch relevance) | ★★★ |
| **`_geo`** / lat, lon | ★★★ flyTo, fitBounds |
| **`category_slug`** | ★★ режим Multi |
| **`kind`** (`address` / `organization`) | ★★ |
| **`building_id`** | ★ flyTo к зданию |

Лимит API: **15** hits ([PERFORMANCE.md](../../PERFORMANCE.md) S2). Zoom-рейтинг в search не нужен — показываются только результаты запроса.

---

## 3. MBTiles `poi` — фильтры Style JSON (mobile)

| Zoom | `poi-dot` | `poi-label` | `rank` (точки) | `rank` (подписи) | `class` — показывать | `class` — скрыть | `subclass` / прочее |
|:---:|:---|:---|:---:|:---:|:---|:---|:---|
| **< 15** | выкл | выкл | — | — | — | все | — |
| **15** | вкл | выкл | ≤ **25** | — | `hospital`, `pharmacy`, `clinic`, `doctors`, `bank`, `post`, `school`, `college`, `university`, `fuel`, `police`, `town_hall`, `library`, `stadium`, `attraction`, `place_of_worship` | `shop`, `fast_food`, `cafe`, `bar`, `restaurant`, `clothing_store`, `grocery` | для `bus` / `tram` / `subway`: `agg_stop = 1` |
| **16** | вкл | выкл | ≤ **18** | — | z15 + `restaurant`, `cafe`, `supermarket`, `park`, `lodging` | `shop` (общий), `fast_food`, `bar`, `clothing_store` | `pharmacy`, `bank`, `fuel`, `hospital`, `school` — без доп. cut |
| **17** | вкл | вкл | ≤ **12** | ≤ **8** | все, кроме blacklist | — | подпись только при наличии `name` / `name:latin` / `name:sr-Latn` |
| **18** | вкл | вкл | ≤ **12** | ≤ **10** | все named | unnamed `shop`, `fast_food` | — |
| **19+** | вкл | вкл | ≤ **20** | ≤ **12** | все named POI | `rank > 20` без имени | overzoom с maxzoom 14 |

**Отрисовка:** `symbol-sort-key` / приоритет — по `["get", "rank"]` (меньший rank выше).

**Пример фильтра точек (z15–16):**

```json
["all",
  ["<=", ["coalesce", ["get", "rank"], 99], 25],
  ["match", ["get", "class"],
    ["shop", "fast_food", "cafe", "bar", "restaurant", "clothing_store", "grocery"], false,
    true
  ]
]
```

---

## 4. Org-пины PostGIS — `display_rank`

### 4.1. Формула

```
display_rank = category_tier
             + (source == editorial ? -5 : 0)
             + (phones OR website ? -2 : 0)
             + floor(distance_to_bbox_center_km * 3)   // 0…~10
```

Меньше = важнее. Сортировка API: `ORDER BY display_rank ASC, ST_Distance(geom, center) ASC`.

### 4.2. `category_tier` по `category_slug`

| Tier | Примеры `category_slug` | Балл |
|:---:|:---|:---:|
| 10 | `hospital`, `clinic`, `doctors`, `dentist`, `veterinary` | 10 |
| 15 | `pharmacy`, `police`, `fire_station`, `post_office`, `townhall`, `embassy`, `courthouse` | 15 |
| 20 | `bank`, `fuel`, `school`, `kindergarten`, `university`, `college`, `library`, `theatre`, `cinema`, `place_of_worship` | 20 |
| 30 | `restaurant`, `cafe`, `fast_food`, `bar`, `pub`, `marketplace`, `food_court` | 30 |
| 40 | `supermarket`, `convenience`, `bakery`, `butcher`, `mall` | 40 |
| 50 | `shop`, `clothes`, `electronics`, `hardware`, `beauty`, `hairdresser` | 50 |
| 60 | `parking`, `car_wash`, `car_rental`, `driving_school` | 60 |
| 70 | `office`, `coworking_space`, `lawyer`, `accountant` | 70 |
| 90 | `other`, NULL | 90 |

Полный список slug — `infra/postgis/seed_categories.sql`. Неразмеченные POI → tier **90**.

### 4.3. Пороги по zoom (browse)

| Zoom | `limit` запроса | Показать если `display_rank ≤` | Подпись имени | Кластеризация |
|:---:|:---:|:---:|:---:|:---:|
| **< 15** | 0 (не запрашивать) | — | — | — |
| **15** | **40** | **20** | нет, только иконка | да, radius 50 px |
| **16** | **80** | **35** | нет | да, radius 40 px |
| **17** | **120** | **50** | top-20 по rank | да, radius 35 px |
| **18** | **160** | **70** | top-40 | опционально |
| **19+** | **200** (max API, B5) | **90** | top-60 | выкл |

Debounce смены bbox: **300 ms** (z15–17), **250 ms** (z18), **200 ms** (z19+). Отмена устаревших запросов — как S3 в поиске.

---

## 5. GeoJSON-слой `org-pins` (MapLibre Native)

Клиент добавляет источник поверх MBTiles (не из тайлов).

| Zoom | `icon-size` | `text-field` | Фильтр features |
|:---:|:---:|:---:|:---|
| 15 | 0.7 | `""` | `display_rank ≤ 20` |
| 16 | 0.8 | `""` | `display_rank ≤ 35` |
| 17 | 0.85 | step: с z17 — `name` | `display_rank ≤ 50`; подписи только top-20 |
| 18 | 0.9 | `name` | `display_rank ≤ 70`; подписи top-40 |
| 19+ | 0.9 | `name` | `display_rank ≤ 90`; подписи top-60 |

Иконка — по `category_slug` (maki / assets), цвета согласовать с `poi-dot` в стиле.

---

## 6. Режим поиска

| Режим | Триггер | Карта | Ранжирование |
|:---|:---|:---|:---|
| **Single** | выбор конкретного hit в dropdown / Enter | 1 маркер, flyTo z ≥ 16 | порядок Meilisearch |
| **Multi** | категория («apoteka», «kafić»), ≥3 hits одной категории | все hits (≤15) + fitBounds | relevance, затем `_geo` |
| **Multi + «Na karti»** | фильтр «только в viewport» | hits ∩ bbox | relevance → distance |

Browse org-пины в search-режиме **не рисовать**. Кнопка «Prikaži sve na karti» — все hits текущего запроса пинами.

---

## 7. Сводная матрица zoom × параметр

| Параметр | z15 | z16 | z17 | z18 | z19+ |
|:---|:---:|:---:|:---:|:---:|:---:|
| MBTiles `rank` (точки) | ≤25 | ≤18 | ≤12 | ≤12 | ≤20 |
| MBTiles `rank` (подписи) | — | — | ≤8 | ≤10 | ≤12 |
| MBTiles `class` min tier | landmark | + food, park | all named | all named | all |
| Org `display_rank` max | 20 | 35 | 50 | 70 | 90 |
| Org `limit` bbox | 40 | 80 | 120 | 160 | 200 |
| Org подписи | нет | нет | top-20 | top-40 | top-60 |
| Debounce bbox (ms) | 300 | 300 | 300 | 250 | 200 |

---

## 8. Этап внедрения

| Шаг | Где | Блокер |
|---|---|---|
| 1 | Mobile Style JSON: фильтры §3 на `poi-dot` / `poi-label` | STAGE-10 |
| 2 | Клиент: `org-pins` GeoJSON, bbox + zoom → `limit` §4.3 | этап 5 MOBILE (поиск) или параллельно после STAGE-10 |
| 3 | API: `display_rank` в `/orgs?bbox=`, сортировка §4.1 | желательно до плотного z17+ |
| 4 | Search Single / Multi §6 | этап 5 MOBILE |
| 5 | Кластеризация org-pins | v1.1, если T4 проседает |

**Verify (черновик):** на z15 в центре города — org-пинов ≤ 40, FPS ≥ 30 (T4); на z17 — ≤ 120; search «apoteka» — ≤ 15 search-пинов, browse-пины скрыты.

---

## 9. Отличия от текущего кода

| Сейчас | Целевое (этот документ) |
|---|---|
| `poi-dot` z15 без rank-фильтра | rank + class по §3 |
| `/orgs?bbox=` ORDER BY name, limit 200 | `display_rank`, limit по zoom §4.3 |
| Поиск: только один маркер (web) | Single + Multi §6 |
| POI из тайлов = много точек | OSM POI ослаблены; org — PostGIS |

---

*Документ v1. Android POI zoom ranking. Обновлять после первой реализации org-pins в `apps/android`.*
