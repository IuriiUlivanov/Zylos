# Этап 14. Android: маршрут transit (+ POST /v1/route)

Связан с [MOBILE.md](MOBILE.md) (§2.6, §6 этап 5.2), [design/MOBILE-DESIGN.md](design/MOBILE-DESIGN.md), [design/route/README.md](design/route/README.md) (макеты v2), [design/object-card/versions/v2-bottom-tabs-route-panel.md](design/object-card/versions/v2-bottom-tabs-route-panel.md), [STAGE-13-android-poi.md](STAGE-13-android-poi.md), [ARCHITECTURE.md](ARCHITECTURE.md), [PERFORMANCE.md](PERFORMANCE.md).

Цель: пользователь строит маршрут **A→B общественным транспортом JGSP** на Android — линия на карте (автобус сплошная, пеший участок пунктир), номера линий, время и шаги в bottom sheet. Для этого этап **закрывает контракт** `POST /v1/route` mode=`transit` в `apps/api` (OTP2) **и** UI в `apps/android`. Офлайн-карта MBTiles **остаётся**; маршрут без сети недоступен.

Вход, без которого не начинать:

- [STAGE-13-android-poi.md](STAGE-13-android-poi.md) закрыт: `scripts/stage13-verify.ps1` → exit **0**
- `docker compose` → `postgis`, `api` running; STAGE-12/13 регрессия зелёная
- GTFS JGSP Novi Sad в `data/gtfs/jgsp/` (zip не в git — см. `data/README.md`; verify проверяет наличие `agency.txt`, `routes.txt`, `stops.txt`, `stop_times.txt`, `trips.txt`, `calendar.txt`)
- Kotlin DTO `RouteDto.kt` зеркалит `apps/api/src/types/route.ts` — **форму JSON не менять** после фиксации на этапе
- Browse org-pins, Search Multi, building/org sheet из STAGE-11–13 **переиспользовать**, не дублировать

Этап **не** добавляет Valhalla walk/bike/car UI, live GPS автобусов (NSmart), правки `apps/web`, пересборку MBTiles, `GET /v1/transit/routes` (справочник линий на карте — v1.1). Walk-only fallback на клиенте — **не v1**.

Модели: **Opus 5** — контракт `/v1/route`, OTP proxy, R8/R4. **Grok 4.6** — route sheet, линии на карте, From/To UX. **Composer 2.5** — docker OTP, Retrofit, verify, unit-тесты. **GPT-5.6 Sol** — если OTP/GTFS не поднимается в compose.

---

## 1. Зачем этот этап

STAGE-13 закрыл browse POI и Search Multi. Продукт v1 по [MOBILE.md](MOBILE.md) обязан давать **маршрут общественным транспортом** — это ключевое отличие 2ГИС от «просто карты».

Без этого этапа OTP/GTFS и Fastify `/route` не проверяются end-to-end на телефоне. Пороги **R4, R7, R8, R9** из [PERFORMANCE.md](PERFORMANCE.md) становятся обязательными для сценария «выбрал A и B → линия на карте → шаги в sheet».

После STAGE-14 можно переходить к оптимизации и приёмке — [MOBILE.md](MOBILE.md) §6 этап 6 (профiling T4, размер APK/MBTiles).

---

## 2. Что входит и что нет

### Входит

#### Бэкенд (если `POST /v1/route` ещё нет)

- **`otp`** в `docker-compose.yml`: OpenTripPlanner 2, OSM graph + GTFS из `data/osm/novi-sad.osm.pbf` и `data/gtfs/jgsp/`
- **`POST /v1/route`** в `apps/api`:
  - body: `{ from: { lon, lat }, to: { lon, lat }, mode: "transit" }` — v1 **только** `transit`
  - проверка `city_boundary` до вызова OTP → **422** `{ error: "outside_city" }` (R8)
  - proxy OTP Plan API → нормализованный JSON (§4.1); timeout upstream **6 s**, клиенту **504** `{ error: "routing_timeout" }`
  - геометрия leg'ов — GeoJSON `LineString`, суммарный ответ **≤ 200 KB** (R7)
- **`apps/api/src/types/route.ts`** — канонический контракт; Vitest на парсер OTP → DTO
- **`scripts/stage14-verify.mjs`** (или секция в `.ps1`) — curl фикстур R4/R8 + 3+ A→B в городе

#### Android

- **`POST /v1/route`** в `ZylosApi` + `RouteRepository.planTransit(from, to)`
  - timeout OkHttp **8 s** (R4 клиент)
  - отмена устаревшего запроса при смене From/To
- **Bottom tab bar** (v2, [design/object-card/versions/v2-bottom-tabs-route-panel.md](design/object-card/versions/v2-bottom-tabs-route-panel.md)):
  - **Pretraga** (default) — search bar над tab bar
  - **Ruta** — search bar **заменяется** route panel; tab bar **остаётся**
  - Od **и** Do заполнены → правая половина tab bar: CTA **«Napravi rutu»** (вместо label Ruta); тап → `POST /v1/route`
  - **↕ swap** справа от «Na karti», высота обеих строк — обмен Od ↔ Do; **если маршрут уже построен (Result)** — сразу повторный `POST /v1/route` для новых точек, без тапа «Napravi rutu»
- **Режим Route** (`MapRouteMode`):
  - **Idle + Search tab** — browse как STAGE-13
  - **Planning + Route tab** — route panel (From/To rows + Peške/Prevoz)
  - **Result** — линия + route sheet; org-pins **скрыты**
- **Route panel — выбор точек:**
  - **From row:** `[Moja lokacija]` (disabled без GPS) + поле autocomplete + `[Na karti]`
  - **To row:** spacer ● + поле autocomplete + `[Na karti]`
  - тап поля → autocomplete `/v1/search` (reuse STAGE-12)
  - **Na karti** → crosshair / tap на карте для активного поля
  - **Peške** / **Prevoz** — два mode pill (v1 API: только **Prevoz**)
  - выбранное **здание / org / точка** + tap **Ruta** → Route tab, **Do** = адрес объекта (label + coords), **Od** = GPS если есть; **без** кнопки «Ruta do ovde» в карточках
- **Отрисовка маршрута** (GeoJSON sources):
  - `route-walk` — `LineLayer`, `line-dasharray` [2, 2], цвет `muted` или `#5B6B7A`
  - `route-transit` — `LineLayer` сплошная; **каждый leg свой `line-color`** (`leg.route_color`, fallback palette)
  - `route-labels` — `SymbolLayer` с `route_short_name` (7A, 11, …) на mid-point transit leg
  - `route-from`, `route-to` — `CircleLayer` (accent / danger)
  - `fitBounds` **активного** itinerary в **верхние 50%** экрана (padding под route sheet + route panel + tab bar); zoom — весь маршрут в top half
- **Route results sheet** (над route panel; макеты — [design/route/README.md](design/route/README.md) §4):
  - **Outer sheet** step1 / step2 / step3 (как object sheet): после расчёта → **step2** (~полэкрана)
  - **step1** — только Od → Do
  - Results **flush** к route panel (From/To), **без зазора** и **без** скругления
  - **Nested cards** — один itinerary = одна карточка; carousel ↔ на **любом step**; **активная по центру** (без peek)
  - **Точки** над карточкой; в step1 — точки вместо текста «N varijante»
  - Активная карточка → линии на карте только её
  - Карточка: duration, transfers, leg rows (transit: icon+type+num+time; walk: icon+time)
  - Scroll внутри карточки если не помещается
  - Сортировка: duration ↑ → transfers ↑ → walk sum ↑ → OTP index ↑
  - **×** → Idle, слои очищены
- **Приоритет hit-test** (обновить STAGE-11–13):
  1. Route chrome / route sheet
  2. Search chrome / dropdown (если не в Route Result-only)
  3. Route endpoints (`route-from`, `route-to`)
  4. Search-пины / selected-marker
  5. Org browse-пины
  6. Тап по карте — в Planning: long-press handler; в Idle: здание (STAGE-11)
- **Ошибки UI** (sr-Latn):
  - 422 → «Tačke moraju biti u Novom Sadu»
  - 504 / timeout → «Ruta trenutno nije dostupna»
  - 404 / no path → «Nije pronađena ruta»
  - Airplane Mode → «Potrebna je mreža za rutu»
- **Unit-тесты:** DTO route JSON, leg color fallback, duration formatting, stale cancel
- **`scripts/stage14-verify.ps1` / `.sh`** — API fixtures + статика Android + регрессия STAGE-13

### Не входит

- `mode: walk | bike | car` (Valhalla) — отдельный этап после transit
- `GET /v1/transit/routes`, слой линий JGSP на карте без построения маршрута — v1.1
- GTFS-RT / live прибытие (NSmart) — запрещено скрейпить
- Turn-by-turn navigation, голос, фоновый GPS (U5)
- Отдельный full-screen Activity — только overlay + sheet на `MapActivity`
- Правки `apps/web`, Meilisearch, PostGIS schema (кроме read `city_boundary` в route handler)
- iOS, Capacitor

---

## 3. Критерии выполнения (Definition of Done)

Этап закрыт, только если `scripts/stage14-verify.ps1` (или `.sh`) → exit **0** **и** `./gradlew :app:testDebugUnitTest` → exit **0** **и** `./gradlew :app:test` (api Vitest если добавлен) для route handler.

### 3.1. Предусловия

| # | Критерий | Как проверить |
|---|---|---|
| P1 | STAGE-13 закрыт | `stage13-verify` → exit 0 |
| P2 | GTFS на диске | `data/gtfs/jgsp/agency.txt` exists |
| P3 | OTP healthy | `curl http://127.0.0.1:<OTP_PORT>/otp/routers/default/index/graphql` или documented health → не connection refused после `compose up otp` |
| P4 | API up | `curl …/v1/health` → 200 |

### 3.2. API — POST /v1/route (transit)

| # | Критерий | Порог | ID |
|---|---|---|---|
| A1 | F-R1 центр → Liman | **200**, `legs.length ≥ 1`, ≥ 1 leg `mode=transit` или walk+transit | R4 |
| A2 | F-R2 Петроварадин → центр | **200**, `duration_sec > 0` | R4 |
| A3 | F-R3 reverse F-R1 | **200**, время в разумных пределах (±30% от F-R1) | — |
| A4 | Точка вне города | **422**, `{ error: "outside_city" }`, **≤ 30 ms** | R8 |
| A5 | p95 F-R1 (localhost, OTP warm) | **≤ 1.5 s** | R4 |
| A6 | Размер JSON F-R1 | **≤ 200 KB** | R7 |
| A7 | OTP down | **504** за **≤ 6 s**, не hang | — |

**Фикстуры координат (EPSG:4326, lon/lat):**

| ID | From | To | Зачем |
|---|---|---|---|
| F-R1 | 19.845, 45.255 (Trg) | 19.840, 45.238 (Liman) | типичный автобус + пешие участки |
| F-R2 | 19.862, 45.252 (Petrovaradin) | 19.845, 45.255 (centar) | правый берег → центр |
| F-R3 | swap F-R1 | — | симметрия; после Result — auto-rebuild |

Контракт JSON — §4.1. Kotlin — `RouteDto.kt`.

### 3.3. Android — route UI

| # | Критерий | Как проверить |
|---|---|---|
| M1 | Retrofit route | `ZylosApi.route(RouteRequest)` |
| M2 | Enter Route mode | Кнопка «Ruta» / иконка в search dock → панель Od/Do |
| M3 | Pick From via search | Autocomplete hit → поле Od заполнено координатами |
| M4 | Long-press To | Long-press на карте → поле Do |
| M5 | Build | «Napravi rutu» → POST, spinner ≤ 8 s |
| M5b | Swap after Result | ↕ при готовом маршруте → auto POST, без второго тапа CTA |
| M6 | Line on map | ≥ 1 `route-transit` или `route-walk` слой виден |
| M7 | Dash walk | Walk leg — пунктир (unit или screenshot checklist) |
| M8 | Route label | На transit leg виден `route_short_name` (если OTP отдал) |
| M9 | Route sheet | nested cards carousel; step2 default; × очищает |
| M9b | Variant swipe | ↔ меняет active + map layers + fitBounds top 50% |
| M10 | Object → Route tab | Org/building open → tap Ruta → Do = адрес, Planning |
| M11 | 422 UI | Точка вне города → Snackbar R8 текст |
| M12 | Airplane Mode | Карта жива; build → «Potrebna je mreža» |
| M13 | STAGE-13 регрессия | Browse pins, Search Multi, org tap — без регрессии |

### 3.4. Производительность

| # | Критерий | Порог | ID |
|---|---|---|---|
| P perf | addLayer route после JSON | **≤ 100 ms** на mid-range | R9 |
| P ui | Открытие route sheet | **≤ 250 ms** | U2 |

R9 — замер `SystemClock.elapsedRealtime()` в debug вокруг `setGeoJson` + `addLayer`; verify проверяет наличие слоёв, не подменяет device profiling.

### 3.5. Тесты и verify

| # | Критерий | Как проверить |
|---|---|---|
| T1 | Android unit tests | `:app:testDebugUnitTest` exit 0 |
| T2 | DTO contract | `ApiDtoContractTest` — sample `RouteResponse` |
| T3 | API unit tests | Vitest route parser / 422 (если добавлены) |
| T4 | stage14-verify | exit 0 |
| T5 | STAGE-12/13 verify | регрессия exit 0 |

---

## 4. Контракт и поведение

### 4.1. POST /v1/route — JSON (канон)

**Request:**

```json
{
  "from": { "lon": 19.845, "lat": 45.255 },
  "to": { "lon": 19.840, "lat": 45.238 },
  "mode": "transit"
}
```

**Response 200:**

```json
{
  "mode": "transit",
  "duration_sec": 1680,
  "distance_m": 4200,
  "transfers": 1,
  "legs": [
    {
      "mode": "walk",
      "duration_sec": 360,
      "distance_m": 280,
      "geometry": {
        "type": "LineString",
        "coordinates": [[19.845, 45.255], [19.844, 45.254]]
      }
    },
    {
      "mode": "transit",
      "duration_sec": 900,
      "distance_m": 3200,
      "route_short_name": "7A",
      "route_color": "E30613",
      "from_stop_name": "Trg Slobode",
      "to_stop_name": "Liman III",
      "headsign": "Liman",
      "geometry": {
        "type": "LineString",
        "coordinates": [[19.844, 45.254], [19.840, 45.238]]
      }
    }
  ]
}
```

**Ошибки:**

| HTTP | body | Когда |
|---|---|---|
| 400 | `{ "error": "invalid_request" }` | нет from/to, mode ≠ transit |
| 422 | `{ "error": "outside_city" }` | точка вне `city_boundary` |
| 404 | `{ "error": "no_route" }` | OTP не нашёл путь |
| 504 | `{ "error": "routing_timeout" }` | OTP / Valhalla timeout |

Поля `route_color` — **6 hex без `#`**. Клиент добавляет `#` при необходимости.

### 4.2. Последовательность Android

```text
onRouteFabClick()
  → routeMode = Planning
  → hide browse org-pins (как Search Multi)
  → show routeChrome (Od/Do)

onPickFrom(hit | longPress)
  → from = { lon, lat, label? }

onPickTo(hit | longPress | orgCard)
  → to = { lon, lat, label? }

onBuildRoute()
  → if offline: Snackbar
  → POST /v1/route { from, to, mode: transit }
  → routeMode = Result
  → sort itineraries (duration → transfers → walk → index)
  → render active legs → route-walk / route-transit / route-labels
  → fitBounds(active legs, topHalf=0.5)
  → open route results sheet (step2)

onSelectRouteVariant(index)
  → activeVariant = index
  → re-render map layers for variant
  → fitBounds(topHalf=0.5)

onClearRoute()
  → routeMode = Idle
  → clear route sources
  → close route sheet
  → restore Browse pins on next camera idle
```

### 4.3. GeoJSON-слои

| Source id | Слой | Стиль |
|---|---|---|
| `route-walk` | `route-walk-line` | dash, width 4 dp, `#5B6B7A` |
| `route-transit` | `route-transit-line` | solid, width 6 dp, `route_color` |
| `route-labels` | `route-transit-label` | `route_short_name`, halo |
| `route-from` | `route-from-dot` | accent circle |
| `route-to` | `route-to-dot` | `#B42318` circle |

Z-order: под org-pins / search-pins в Idle; в Result — **поверх** POI, под endpoint markers.

### 4.4. Константы

| Константа | Значение | ID |
|---|---|---|
| `ROUTE_CLIENT_TIMEOUT_MS` | 8000 | R4 |
| `ROUTE_UPSTREAM_TIMEOUT_MS` | 6000 | — |
| `ROUTE_SHEET_ANIM_MS` | 250 | U2 |
| `ROUTE_LINE_WIDTH_TRANSIT` | 6 dp | MOBILE §2.2 |
| `ROUTE_LINE_WIDTH_WALK` | 4 dp | — |

### 4.5. UI-тексты (sr-Latn v1)

| Ситуация | Текст |
|---|---|
| Кнопка режима | `Ruta` |
| From / To labels | `Od` / `Do` |
| Build | `Napravi rutu` |
| Walk leg | `Pešačenje · {N} min` |
| Transit leg | `Autobus {short_name} · {N} min` |
| Summary | `{N} min · {M} presedanja` (M=0 → «bez presedanja») |
| No route | `Nije pronađena ruta` |
| Outside city | `Tačke moraju biti u Novom Sadu` |
| Timeout | `Ruta trenutno nije dostupna` |
| Offline | `Potrebna je mreža za rutu` |

---

## 5. Структура файлов (ожидаемые пути)

```text
data/gtfs/jgsp/                    ← GTFS zip распакован (не в git)
infra/otp/                         ← Dockerfile или compose mount, router-config.json

apps/api/src/
  types/route.ts
  routes/route.ts                  ← POST /v1/route
  services/otpClient.ts            ← proxy OTP Plan
  routes/route.test.ts

apps/android/app/src/main/java/rs/zylos/novisad/
  MapActivity.kt                   ← route mode, long-press, hit-test
  map/
    RouteLayers.kt                 ← walk/transit/labels/endpoints
    MapDefaults.kt                 ← + ROUTE_* constants
  data/
    api/
      ZylosApi.kt                  ← + route()
      RouteDto.kt
    repository/
      RouteRepository.kt
  ui/
    route/
      RouteChrome.kt / layout      ← Od/Do panel (View system v1)
      RouteSheetAdapter.kt         ← leg list
  viewmodel/
    MapViewModel.kt                ← routeMode, build/clear
    RouteLogic.kt                  ← formatting, layer mapping
  res/layout/
    route_chrome.xml
    item_route_leg.xml
app/src/test/java/.../
  data/api/RouteDtoTest.kt
  map/RouteLogicTest.kt
  viewmodel/RouteModeTest.kt

scripts/
  stage14-verify.ps1
  stage14-verify.sh
  stage14-verify.mjs               ← curl R4/R8 fixtures
```

---

## 6. Сборка и отладка

```powershell
# GTFS (вручную): распаковать в data/gtfs/jgsp/

# OTP + API
docker compose up -d postgis otp api
# первый старт OTP — до R6 (90 s); дождаться healthy

# Verify API
node scripts/stage14-verify.mjs

# Android
cd apps\android
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew :app:assembleDebug :app:testDebugUnitTest

# Телефон в LAN
docker compose -f docker-compose.yml -f docker-compose.mobile.yml up -d api otp
.\gradlew :app:assembleDebug -Pzylos.apiUrl=http://<LAN-IP>:3000
```

Verify:

```powershell
.\scripts\stage14-verify.ps1
.\scripts\stage13-verify.ps1    # регрессия
```

Ручная приёмка:

1. «Ruta» → Od = Trg (search), Do = long-press Liman → линия + sheet с автобусом.
2. Org card open → tap **Ruta** → Do = адрес → **Napravi rutu** → fitBounds.
3. Точка вне города → 422 Snackbar.
4. Airplane Mode → карта жива, route blocked.
5. × → слои очищены, browse pins возвращаются.

---

## 7. Пути, которые меняет этап

- `apps/android/**` (route UI, layers, тесты)
- `apps/api/**` (`POST /v1/route`, types, OTP client, tests)
- `docker-compose.yml` — сервис `otp` (имя контейнера **`otp`** — не менять)
- `infra/otp/**` — конфиг роутера
- `scripts/stage14-verify.*`
- `apps/android/README.md`
- Документы: этот файл; ссылки в [MOBILE.md](MOBILE.md), [PLAN.md](PLAN.md)

**Не менять:** `apps/web/**`, Meilisearch settings, PostGIS schema (кроме использования `city_boundary`), MBTiles, `infra/preview/style.json` / `style-mobile.json` (route layers — runtime GeoJSON, не style.json v1).

---

## 8. Prompt для агента (один чат = этот этап)

```text
Сделай STAGE-14 строго по Docs/STAGE-14-android-transit.md.

Нужны: POST /v1/route mode=transit (OTP2, GTFS JGSP, R4/R8/R7);
Android Route mode (Od/Do, search + long-press, object → Route tab → Do);
GeoJSON route-walk (dash) + route-transit (solid) + labels;
route sheet; unit-тесты; stage14-verify.ps1 + .mjs.
Не трогай apps/web, Valhalla walk UI, GET /v1/transit/routes.
Референс UX: MOBILE.md §2.6, MOBILE-DESIGN.md §2.
```

---

## 9. Выход в следующий этап

После зелёного `stage14-verify` → профiling и приёмка mobile v1 — [MOBILE.md](MOBILE.md) §6 этап 6 (T4 на Phone-mid, размер MBTiles/APK). Опционально v1.1: Valhalla `mode=walk` в том же UI, `GET /v1/transit/routes` для browse линий JGSP.
