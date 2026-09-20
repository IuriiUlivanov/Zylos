# Этап 13. Android: org-пины и Search Multi

Связан с [MOBILE.md](MOBILE.md) (§6 этап 5), [design/MOBILE-POI-ZOOM.md](design/MOBILE-POI-ZOOM.md), [design/MOBILE-DESIGN.md](design/MOBILE-DESIGN.md) (§5 карта), [STAGE-12-android-search.md](STAGE-12-android-search.md), [STAGE-11-android-building.md](STAGE-11-android-building.md), [ARCHITECTURE.md](../ARCHITECTURE.md), [PERFORMANCE.md](../PERFORMANCE.md).

Цель: **`apps/android`** получает **browse org-пины** по viewport (`GET /v1/orgs?bbox=`), **прогрессивную плотность OSM POI** в mobile Style JSON и режим **Search Multi** — до **15** маркеров результатов поиска на карте с `fitBounds`, browse-пины в search-режиме **скрыты**. Офлайн-карта MBTiles **остаётся**; bbox-запросы и live search требуют сеть.

Вход, без которого не начинать:

- [STAGE-12-android-search.md](STAGE-12-android-search.md) закрыт: `scripts/stage12-verify.ps1` → exit **0**
- `docker compose` → `postgis`, `api` running; `GET /v1/orgs?bbox=` отвечает (регрессия **B5** — `scripts/stage05-verify.mjs`)
- Kotlin DTO `OrgPin` в `OrgDto.kt` зеркалит `apps/api/src/types/org.ts` — **форму JSON не менять**
- Autocomplete, `selected-marker`, building/org sheet из STAGE-11–12 **переиспользовать**, не дублировать

Этап **не** добавляет transit UI, `POST /v1/route`, правки `apps/web`, пересборку MBTiles, кластеризацию org-pins (v1.1). Поле API `display_rank` — **желательно v1.1**, для z15–z16 **не блокер** (клиент считает rank локально, см. §4.4).

Модели: **Grok 4.6** — org-pins слой, Search Multi UI, hit-test. **Composer 2.5** — Retrofit bbox, debounce, mobile style, verify, unit-тесты. **Opus 5** — если путается контракт `/v1/orgs` или режимы Browse / Search.

---

## 1. Зачем этот этап

STAGE-12 доказал autocomplete и один маркер выбранного hit. Пользователь 2ГИС ожидает: **приблизил карту — увидел аптеки и кафе**; **набрал «apoteka» — увидел все результаты на карте**, а не только один.

Без этого этапа PostGIS bbox и прогрессивный POI из [design/MOBILE-POI-ZOOM.md](design/MOBILE-POI-ZOOM.md) не проверяются end-to-end на Android. Пороги **B5**, **T4** из [PERFORMANCE.md](../PERFORMANCE.md) становятся обязательными для сценария «browse + search multi».

После STAGE-13 можно переходить к transit — [STAGE-14-android-transit.md](STAGE-14-android-transit.md).

---

## 2. Что входит и что нет

### Входит

- **`GET /v1/orgs?bbox=minLon,minLat,maxLon,maxLat&limit=`** в `ZylosApi` + `OrgRepository.inBbox()`:
  - debounce **300 ms** (z15–17), **250 ms** (z18), **200 ms** (z19+) — [MOBILE-POI-ZOOM.md](design/MOBILE-POI-ZOOM.md) §4.3, §7
  - `limit` по zoom (40 / 80 / 120 / 160 / 200); zoom **< 15** — без HTTP, слой пустой
  - отмена устаревших запросов (новый bbox / zoom отменяет предыдущий — как S3)
- **GeoJSON source `org-pins`** + `SymbolLayer` / `CircleLayer` поверх MBTiles:
  - фильтр отрисовки по `display_rank` (клиент) и zoom — §5 MOBILE-POI-ZOOM
  - тап по пину → `GET /v1/orgs/:id` → sheet **organization** (STAGE-11)
- **Mobile Style JSON** — копия `infra/preview/style.json` → `infra/preview/style-mobile.json`:
  - rank + class фильтры на `poi-dot` / `poi-label` по таблице §3 MOBILE-POI-ZOOM
  - `MapStyleFactory` патчит MBTiles URI + water как сейчас; **веб-стиль не ломать**
- **Режимы карты** (`MapPinMode`):
  - **Browse** — org-pins по bbox; OSM POI по mobile-стилю
  - **Search Single** — один `selected-marker` (STAGE-12); browse org-pins **скрыты**; OSM POI opacity **0.5** или minzoom +1 (достаточно opacity v1)
  - **Search Multi** — до **15** search-пинов (`search-pins` source), `fitBounds`, browse org-pins **скрыты**
- **Search Multi** (логика + UI):
  - авто-триггер: ≥ **3** hits с `kind=organization` и **один** `category_slug` (категорийный запрос)
  - ручной триггер: кнопка **«Prikaži sve na karti»** в footer dropdown при `hits.isNotEmpty()`
  - `fitBounds` по координатам hits с padding под search dock + object sheet
  - sheet **список** hits (reuse dropdown adapter или compact list в sheet step 1)
  - тап по search-пину или строке списка → Single-поведение для этого hit
- **Приоритет hit-test** (обновить STAGE-11/12):
  1. Search chrome / dropdown
  2. Search-пины (`selected-marker`, `search-pins`)
  3. Org browse-пины (`org-pins`)
  4. Тап по карте → здание через API (STAGE-11)
- **Unit-тесты:** zoom→limit, debounce bbox, display_rank (клиент), Multi eligibility, DTO bbox JSON
- **`scripts/stage13-verify.ps1` / `.sh`** — статика + curl B5 + регрессия STAGE-12

### Не входит

- `display_rank` в ответе API — v1.1; v1 клиент считает из `category_slug` + расстояние до центра bbox
- Кластеризация org-pins — v1.1, если **T4** проседает
- `POST /v1/route`, OTP, Valhalla — **этап 5.2**
- POI hit-test из MVT (`queryRenderedFeatures` по `poi-dot`) — опционально v1.1
- Правки `apps/api`, PostGIS schema, `apps/web`
- iOS, Capacitor

---

## 3. Критерии выполнения (Definition of Done)

Этап закрыт, только если `scripts/stage13-verify.ps1` (или `.sh`) → exit **0** **и** `./gradlew :app:testDebugUnitTest` → exit **0**.

### 3.1. Предусловия

| # | Критерий | Как проверить |
|---|---|---|
| P1 | STAGE-12 закрыт | `stage12-verify` → exit 0 |
| P2 | API up | `curl …/v1/health` → 200 |
| P3 | Bbox endpoint | `curl "http://127.0.0.1:3000/v1/orgs?bbox=19.83,45.24,19.86,45.26&limit=40"` → 200, `length ≤ 40` |
| P4 | B5 регрессия | `node scripts/stage05-verify.mjs` → B5a/B5c pass |

### 3.2. API (регрессия — клиент не меняет контракт)

| # | Критерий | Порог | ID |
|---|---|---|---|
| A1 | bbox в центре города | **200**, массив pins, каждый с `id`, `name`, `lon`, `lat` | B5 |
| A2 | `limit=40` | **≤ 40** объектов | B5 |
| A3 | `limit` max | сервер обрезает ≤ **200** | B5 |
| A4 | invalid bbox | **400** `{ error: "invalid_bbox" }` | — |
| A5 | p95 bbox (localhost) | **≤ 150 ms** | B5 |

Контракт JSON — `apps/api/src/types/org.ts`. Kotlin — `OrgPin` в `OrgDto.kt`.

### 3.3. Android — browse org-pins

| # | Критерий | Как проверить |
|---|---|---|
| M1 | Retrofit bbox | `ZylosApi.orgs(bbox, limit)` |
| M2 | Debounce 300 ms | Unit-тест: два быстрых `onCameraIdle` → один HTTP |
| M3 | Stale cancel | Быстрый pan — в UI финально последний bbox |
| M4 | Min zoom 15 | z14 — слой `org-pins` пуст, без HTTP |
| M5 | Limit по zoom | z15 → `limit=40`; z17 → `limit=120` (unit-тест таблицы §4.3) |
| M6 | Слой на карте | z16 в центре — ≥ 1 org-pin виден |
| M7 | Tap org-pin | → `/orgs/:id`, sheet organization |
| M8 | Airplane Mode | Карта жива; bbox не дергает сеть; Snackbar при попытке tap org без кэша |

### 3.4. Android — mobile Style JSON (OSM POI)

| # | Критерий | Как проверить |
|---|---|---|
| S1 | `style-mobile.json` в assets | Gradle копирует `infra/preview/style-mobile.json` |
| S2 | `poi-dot` z15 | filter rank ≤ 25 + class whitelist (§3 MOBILE-POI-ZOOM) |
| S3 | `poi-label` z17 | filter rank ≤ 12 (точки), ≤ 8 (подписи) |
| S4 | Веб-стиль | `infra/preview/style.json` **без** регрессии (не менять или менять только общие токены осознанно) |

### 3.5. Android — Search Multi

| # | Критерий | Как проверить |
|---|---|---|
| X1 | Multi auto | «apotek» → ≥ 3 org hits одной категории → режим Multi |
| X2 | ≤ 15 пинов | На карте не больше `min(hits.size, 15)` search-пинов |
| X3 | fitBounds | Камера охватывает все search-пины с padding |
| X4 | Browse скрыты | В Multi/Single browse `org-pins` не рисуются |
| X5 | Кнопка «Prikaži sve na karti» | Показывает все hits текущего запроса на карте |
| X6 | Clear search | × очищает query, Multi-пины, возврат в Browse |
| X7 | STAGE-12 регрессия | Single select hit, history, building tap без search focus |

### 3.6. Производительность

| # | Критерий | Порог | ID |
|---|---|---|---|
| P perf | Пан/зум z14→16 с org-pins | **≥ 30 FPS** среднее за жест; нет фризов **> 100 ms** | T4 |
| P pins | Org-пинов на z15 в viewport | **≤ 40** (limit запроса) | B5 |

T4 — замер на эмуляторе x86_64 или mid-range device, n ≥ 3 жеста; verify фиксирует наличие слоя и limit, не подменяет UI-профiling.

### 3.7. Тесты и verify

| # | Критерий | Как проверить |
|---|---|---|
| T1 | Unit tests | `:app:testDebugUnitTest` exit 0 |
| T2 | DTO contract | `ApiDtoContractTest` — массив `OrgPin` из bbox |
| T3 | stage13-verify | exit 0 |
| T4 | STAGE-11/12 verify | `stage11-verify`, `stage12-verify` → exit 0 (регрессия) |

---

## 4. Поведение клиента (спецификация)

### 4.1. Режимы карты

```text
MapPinMode.Browse     — query пуст или search не в фокусе, Multi не активен
MapPinMode.SearchSingle — выбран один hit (STAGE-12)
MapPinMode.SearchMulti  — Multi активен (auto или «Prikaži sve na karti»)
```

Переходы:

```text
onCameraIdle(bbox, zoom) [Browse only]
  → if zoom < 15: clear org-pins
  → debounce ORG_PINS_DEBOUNCE_MS(zoom)
  → GET /v1/orgs?bbox=&limit=OrgPinLimits.limit(zoom)
  → compute display_rank client-side (§4.4)
  → filter + render org-pins

onSelectHit(hit) → SearchSingle (как STAGE-12)

onSearchMulti(hits) / auto Multi
  → SearchMulti
  → search-pins GeoJSON (≤15)
  → fitBounds(hits)
  → hide org-pins
  → sheet: список hits (step 1 или 2)

onClearSearch / close sheet
  → Browse
```

### 4.2. Последовательность bbox-запроса

```text
onCameraIdle(lat, lon, zoom, visibleBounds)
  → if pinMode != Browse: return
  → if zoom < 15: clear org-pins, return
  → debounce OrgPinLimits.debounceMs(zoom)
  → limit = OrgPinLimits.limit(zoom)
  → GET /v1/orgs?bbox=minLon,minLat,maxLon,maxLat&limit=
  → rank + filter features for layer (§4.4, §4.5)
  → setGeoJson org-pins
```

Референс debounce/stale: `apps/web/src/hooks/useOrgPins.ts`, константы — `apps/web/src/lib/constants.ts` (`ORG_PINS_DEBOUNCE_MS = 300`, `ORG_PINS_MIN_ZOOM = 15`).

### 4.3. Search Multi

```text
onQueryResults(hits)
  → if SearchLogic.isMultiEligible(hits): offer Multi (chip или auto после debounce settle)
onShowAllOnMap()
  → pinMode = SearchMulti
  → pins = hits.take(15)
  → fitBounds with padding (search dock + sheet peek)
  → open sheet list mode

onSelectSearchPin(id) / list row tap
  → same as onSelectHit for that hit (flyTo + marker + building/org/peek)
```

**Multi eligibility (v1):**

```kotlin
hits.size >= 3 &&
hits.count { it.kind == organization } >= 3 &&
hits.filter { it.kind == organization }
    .mapNotNull { it.category_slug }
    .distinct()
    .size == 1
```

### 4.4. `display_rank` на клиенте (v1, без поля API)

API отдаёт `category_slug`, `lon`, `lat`. Клиент добавляет property в GeoJSON feature:

```text
display_rank = categoryTier(category_slug) + floor(distanceKm(pin, bboxCenter) * 3)
```

`categoryTier` — таблица §4.2 [MOBILE-POI-ZOOM.md](design/MOBILE-POI-ZOOM.md). Неразмеченные → **90**.

После сортировки по `display_rank ASC` клиент отбирает features для отрисовки:

| Zoom | Показать если `display_rank ≤` | Подписи `name` |
|:---:|:---:|:---:|
| 15 | 20 | нет |
| 16 | 35 | нет |
| 17 | 50 | top-20 |
| 18 | 70 | top-40 |
| 19+ | 90 | top-60 |

Когда API добавит `display_rank`, `source`, `phones` — переключиться на серверное значение (v1.1).

### 4.5. GeoJSON-слои на карте

| Source id | Режим | Слой |
|---|---|---|
| `org-pins` | Browse | Symbol/Circle по category; filter по zoom + display_rank |
| `selected-marker` | Search Single | CircleLayer (STAGE-12) |
| `search-pins` | Search Multi | CircleLayer или SymbolLayer; цвет отличим от org-pins |
| `selected-building` | Building pick | FillLayer (STAGE-11) |

При Search Single/Multi: `org-pins` → empty GeoJSON или `visibility: none`.

### 4.6. Константы

| Константа | Значение | Референс |
|---|---|---|
| `ORG_PINS_DEBOUNCE_MS` | 300 (z15–17), 250 (z18), 200 (z19+) | MOBILE-POI-ZOOM §7 |
| `ORG_PINS_MIN_ZOOM` | 15 | `constants.ts` |
| `ORG_PINS_LIMIT_MAX` | 200 | B5 |
| `SEARCH_MULTI_MAX_PINS` | 15 | S2 / MOBILE-POI-ZOOM §6 |
| `SEARCH_MULTI_MIN_HITS` | 3 | MOBILE-POI-ZOOM §6 |

### 4.7. UI-тексты (sr-Latn v1)

| Ситуация | Текст |
|---|---|
| Multi chip / кнопка | `Prikaži sve na karti` |
| Multi sheet title | `Rezultati pretrage` |
| Org pin loading | (без текста; точки появляются без блокировки UI) |
| Bbox network error | тихо: оставить последние pins или пусто; Snackbar только при явном tap |

---

## 5. Структура файлов (ожидаемые пути)

```text
infra/preview/
  style-mobile.json               ← poi-dot/poi-label filters §3 MOBILE-POI-ZOOM

apps/android/app/src/main/java/rs/zylos/novisad/
  MapActivity.kt                  ← bbox idle, org-pin hit-test, Multi UI
  map/
    MapDefaults.kt                ← + ORG_PINS_*, SEARCH_MULTI_*
    MapStyleFactory.kt            ← patch style-mobile.json
    OrgPins.kt                    ← GeoJSON org-pins source/layers
    SearchPins.kt                 ← GeoJSON search-pins (Multi)
    OrgPinLogic.kt                ← limit/debounce/rank/filter tables
  data/
    api/
      ZylosApi.kt                 ← + orgs(bbox, limit)
    repository/
      OrgRepository.kt            ← + inBbox()
  viewmodel/
    MapViewModel.kt               ← pinMode, bbox job, Multi state
    MapUiState.kt                 ← + pinMode, orgPinsJson, searchPinsJson
    SearchLogic.kt                ← + isMultiEligible()
  res/layout/
    activity_map.xml              ← + «Prikaži sve na karti» (dropdown footer)
app/src/test/java/.../
  map/OrgPinLogicTest.kt
  data/repository/OrgRepositoryTest.kt
  viewmodel/SearchMultiLogicTest.kt
  map/MapStyleFactoryTest.kt      ← mobile poi filters present

scripts/
  stage13-verify.ps1
  stage13-verify.sh
```

DTO **не дублировать** — `OrgPin` уже в `OrgDto.kt`. Новых полей API на этапе 13 **нет**.

---

## 6. Сборка и отладка

```powershell
# API (хост)
docker compose up -d postgis api

# Эмулятор
cd apps\android
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew :app:assembleDebug :app:testDebugUnitTest

# Телефон в LAN
docker compose -f docker-compose.yml -f docker-compose.mobile.yml up -d api
.\gradlew :app:assembleDebug -Pzylos.apiUrl=http://<LAN-IP>:3000
```

Verify:

```powershell
.\scripts\stage13-verify.ps1
node scripts\stage05-verify.mjs    # B5 регрессия
```

Ручная приёмка:

1. z16 в центре — org-пины видны; pan — обновление без «мигания» stale.
2. «apotek» → Multi или кнопка «Prikaži sve na karti» → ≤15 пинов, fitBounds, browse-пины скрыты.
3. Тап org-pin → карточка org; clear search → Browse.
4. STAGE-12: single hit, history, building tap — без регрессии.

---

## 7. Пути, которые меняет этап

- `apps/android/**` (код, layout, тесты)
- `infra/preview/style-mobile.json` (новый файл)
- `apps/android/app/build.gradle` — копировать `style-mobile.json` в assets вместо `style.json`
- `scripts/stage13-verify.ps1`, `scripts/stage13-verify.sh`
- `apps/android/README.md`
- Документы: этот файл; ссылки в [MOBILE.md](MOBILE.md), [PLAN.md](../PLAN.md)

**Не менять:** `apps/web/**`, `apps/api/**` (контракт `/v1`), PostGIS schema, Meilisearch, MBTiles, PMTiles. `infra/preview/style.json` — только если правка общая и не ломает веб-verify.

---

## 8. Prompt для агента (один чат = этот этап)

```text
Сделай STAGE-13 строго по Docs/mobile/STAGE-13-android-poi.md и Docs/mobile/design/MOBILE-POI-ZOOM.md.

Нужны: GET /v1/orgs?bbox= с debounce 300 ms и limit по zoom; GeoJSON org-pins;
style-mobile.json с poi-dot/poi-label filters; Search Multi (≤15 пинов, fitBounds,
browse-пины скрыты); unit-тесты; stage13-verify.ps1.
Не трогай apps/web, apps/api, OTP/route, display_rank в API.
Референс: apps/web/src/hooks/useOrgPins.ts, constants.ts ORG_PINS_*.
```

---

## 9. Выход в следующий этап

После зелёного `stage13-verify` → [STAGE-14-android-transit.md](STAGE-14-android-transit.md) (transit UI + `POST /v1/route`). Опционально v1.1: `display_rank` в API, кластеризация org-pins если **T4** не проходит на Phone-mid.
