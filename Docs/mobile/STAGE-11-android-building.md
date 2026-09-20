# Этап 11. Android: тап по зданию, bottom sheet, карточка организации

Связан с [MOBILE.md](MOBILE.md) (§6 этап 4), [design/MOBILE-DESIGN.md](design/MOBILE-DESIGN.md) (§4.3–4.5 bottom sheet), [STAGE-10-android.md](STAGE-10-android.md), [STAGE-05-building-click.md](../STAGE-05-building-click.md), [ARCHITECTURE.md](../ARCHITECTURE.md), [PERFORMANCE.md](../PERFORMANCE.md).

Цель: **`apps/android`** получает поведение 2ГИС «тап по дому». Тап по карте → `GET /v1/buildings/at` → `GET /v1/buildings/:id` → подсветка **контура из PostGIS** (GeoJSON), **BottomSheet** со списком адресов и организаций. Тап по организации → `GET /v1/orgs/:id` — полная карточка (телефон, часы, сайт). Офлайн-карта MBTiles **остаётся**; сеть нужна только для справочника.

Вход, без которого не начинать:

- [STAGE-10-android.md](STAGE-10-android.md) закрыт: `scripts/stage10-verify.ps1` → exit **0**
- `docker compose` → `postgis`, `api` running (Meilisearch — не блокер этого этапа)
- `GET /v1/buildings/at`, `/buildings/:id`, `/orgs/:id` отвечают локально (этап 5 веб/API уже реализован)
- Kotlin DTO в `apps/android/.../data/api/` зеркалят `apps/api/src/types` — **форму JSON не менять**
- PostGIS: фикстура F6 — здание с ≥ 2 организациями (см. [STAGE-02-postgis.md](../STAGE-02-postgis.md))

Этап **не** добавляет поиск, org-пины по bbox, маршруты, правки `apps/web`, пересборку MBTiles, изменение SQL-схемы PostGIS.

Модели: **Grok 4.6** — MapActivity, sheet, GeoJSON-слой. **Composer 2.5** — Retrofit, verify, unit-тесты. **Opus 5** — если путается контракт `/v1` или приоритет hit-test.

---

## 1. Зачем этот этап

STAGE-10 доказал офлайн-карту на телефоне. Пользователь 2ГИС ожидает: **тапнул на дом — увидел, кто там сидит**.

Без этого этапа PostGIS-справочник не проверяется end-to-end на Android. Пороги **B1–B4, B6, B7** из [PERFORMANCE.md](../PERFORMANCE.md) становятся обязательными для мобильного сценария «здание → список → карточка».

После STAGE-11 можно переходить к поиску — [STAGE-12-android-search.md](STAGE-12-android-search.md); org-пины — [STAGE-13-android-poi.md](STAGE-13-android-poi.md).

---

## 2. Что входит и что нет

### Входит

- **Сеть:** Retrofit + OkHttp к `BuildConfig.API_URL`; Moshi или kotlinx.serialization — по принятому в модуле стилю (один JSON-парсер)
- **`ZylosApi`** (или `BuildingRepository` / `OrgRepository`):
  - `GET /v1/buildings/at?lon=&lat=`
  - `GET /v1/buildings/{id}`
  - `GET /v1/orgs/{id}`
- **MapActivity / MapViewModel (MVI или MVVM):**
  - `onMapClick` → координата (lon, lat)
  - отмена устаревших запросов (новый тап отменяет предыдущий pick)
  - состояния: idle / loading / building / organization / error
- **Подсветка здания:** GeoJSON source `selected-building` + `FillLayer` / outline (как `apps/web` — `buildingHighlight.ts`, слои `selected-building`, `selected-building-outline`)
  - контур **только** из ответа `/buildings/:id`, **не** из MVT / `queryRenderedFeatures`
- **Bottom sheet** (`BottomSheetBehavior`, Material):
  - режим **building**: заголовок — адрес или «Zgrada»; список org (`RecyclerView` или Compose — **View system v1**, как STAGE-10)
  - режим **organization**: имя, категория, телефоны, часы, сайт, адрес
  - peek / half / expanded; анимация ≤ **250 ms** (U2)
  - тап по org в списке → карточка org; закрытие — **×**
- **Ошибки:**
  - **404** `/buildings/at` → haptic + Snackbar «Nema zgrade na ovoj tački»
  - **422** → Snackbar «Van grada Novi Sad»
  - сеть недоступна → Snackbar, карта жива
- **Unit-тесты:** маппинг DTO, парсинг GeoJSON для слоя, логика приоритета состояния sheet (без эмулятора)
- **`scripts/stage11-verify.ps1` / `.sh`** — статические проверки + опционально curl к API с фикстурой F6
- **`network_security_config.xml`** — cleartext для debug LAN уже есть; document LAN в README

### Не входит

- Autocomplete, `GET /v1/search`, Room — **следующий этап** (MOBILE §6 этап 5)
- `GET /v1/orgs?bbox=` — browse-пины по zoom ([design/MOBILE-POI-ZOOM.md](design/MOBILE-POI-ZOOM.md), этап 5)
- POI из тайлов (`queryRenderedFeatures` по `poi-dot`) — опционально v1.1; v1 достаточно **тапа по зданию через API**
- `POST /v1/route`, OTP, Valhalla
- Кнопка «Маршрут до организации»
- Deep links `zylos://building/{id}` — backlog
- Hilt / Koin — не обязателен; ручная фабрика Api + ViewModel допустима
- Правки `apps/api`, PostGIS, `apps/web`
- iOS, Capacitor

---

## 3. Критерии выполнения (Definition of Done)

Этап закрыт, только если `scripts/stage11-verify.ps1` (или `.sh`) → exit **0** **и** `./gradlew :app:testDebugUnitTest` → exit **0**. «Тап сработал на одном здании вручную» недостаточно без verify.

### 3.1. Предусловия

| # | Критерий | Как проверить |
|---|---|---|
| P1 | STAGE-10 закрыт | `stage10-verify` → exit 0 |
| P2 | API up | `curl -s -o /dev/null -w "%{http_code}" "http://127.0.0.1:3000/v1/health"` → 200 |
| P3 | Building endpoints | `curl "http://127.0.0.1:3000/v1/buildings/at?lon=19.845&lat=45.255"` → 200 или 404 (не 5xx) |
| P4 | Фикстура F6 | SQL или зафиксированные lon/lat здания с ≥ 2 org в verify |

### 3.2. API (регрессия — клиент не меняет контракт)

| # | Критерий | Порог | ID |
|---|---|---|---|
| A1 | `/buildings/at` внутри здания F6 | **200** `{ id, label? }` | B2 |
| A2 | `/buildings/:id` F6 | **200**, `organizations.length ≥ 2` | B3 |
| A3 | `/orgs/:id` из списка | **200**, `phones`/`hours`/`website` как в БД | B1 |
| A4 | GeoJSON `geometry` | **≤ 50 KB** raw | B6 |
| A5 | Здание без org | **200**, `organizations: []` | B7 |

Контракт JSON — [STAGE-05-building-click.md](../STAGE-05-building-click.md) §3.2–3.3. Kotlin DTO — `BuildingDto.kt`, `OrgDto.kt`.

### 3.3. Android — карта и сеть

| # | Критерий | Как проверить |
|---|---|---|
| M1 | Retrofit base URL | `BuildConfig.API_URL`; эмулятор `10.0.2.2:3000` |
| M2 | `onMapClick` | Listener на `MapLibreMap`; координаты WGS84 |
| M3 | Подсветка | После 200 `:id` слой `selected-building` visible |
| M4 | Отмена stale | Быстрые два тапа — в UI финально второе здание |
| M5 | Офлайн карта | Airplane Mode: MBTiles рисуется; sheet показывает ошибку сети при тапе |
| M6 | Атрибуция OSM | Не регрессировать STAGE-10 |

### 3.4. Android — bottom sheet

| # | Критерий | Как проверить |
|---|---|---|
| S1 | Sheet building | Заголовок = `label` или первый address; список org из F6 |
| S2 | Sheet organization | Тап по org → `/orgs/:id`; поля отображены если не null |
| S3 | 404 | Snackbar «Nema zgrade…», sheet закрыт или peek пустой |
| S4 | 422 | Snackbar «Van grada…» |
| S5 | Back org → building | Кнопка возвращает список org, контур здания сохранён |
| S6 | Закрытие | Swipe down / close очищает подсветку |
| S7 | p95 tap → список | **≤ 350 ms** на эмуляторе x86_64 или mid-range device, n ≥ 10 | B4 |

### 3.5. Тесты и verify

| # | Критерий | Как проверить |
|---|---|---|
| T1 | Unit tests | `:app:testDebugUnitTest` exit 0 |
| T2 | DTO contract | `ApiDtoContractTest` покрывает building + org (расширить при необходимости) |
| T3 | stage11-verify | exit 0 |

---

## 4. Поведение клиента (спецификация)

### 4.1. Последовательность запросов

```text
onMapClick(lon, lat)
  → GET /v1/buildings/at?lon=&lat=
  → 404: snackbar, clear highlight
  → 422: snackbar outside city
  → 200: GET /v1/buildings/{id}
  → add GeoJSON source/layers selected-building
  → open sheet mode=building
tap org row
  → GET /v1/orgs/{id}
  → sheet mode=organization
```

Контур и список org **не** из MVT — источник истины **PostGIS** ([ARCHITECTURE.md](../ARCHITECTURE.md)).

### 4.2. Приоритет hit-test (v1)

На STAGE-11 достаточно:

1. Тап по карте (не по search chrome) → **здание через API**
2. Пустой участок без здания → 404 toast

POI из тайлов и маркер поиска — **этап 5** (когда появится search). Зафиксировать в коде комментарием TODO.

### 4.3. GeoJSON на карте

- Source id: `selected-building`
- Layers: fill + outline (цвета согласовать с `infra/preview/style.json` — `selected-building`, `selected-building-outline` если есть в mobile-стиле; иначе добавить в mobile-копию стиля)
- При закрытии sheet или новом тапе — `source.setGeoJson(empty)` или remove layers

Референс веб: `apps/web/src/lib/buildingHighlight.ts`, `apps/web/src/components/MapView.tsx`.

### 4.4. UI-тексты (sr-Latn v1)

| Ситуация | Текст |
|---|---|
| 404 building | `Nema zgrade na ovoj tački` |
| 422 outside | `Van grada Novi Sad` |
| Network error | `Nema veze sa serverom` |
| Loading | `Učitavam…` |
| Пустой список org | `Nema organizacija u zgradi` |
| Sheet title fallback | `Zgrada` |

---

## 5. Структура файлов (ожидаемые пути)

```text
apps/android/app/src/main/java/rs/zylos/novisad/
  MapActivity.kt                    ← onMapClick, bind sheet
  map/
    BuildingHighlight.kt            ← GeoJSON source/layers
  data/
    api/
      ZylosApi.kt                   ← Retrofit interface
      ApiClient.kt                  ← OkHttp + base URL
    repository/
      BuildingRepository.kt
      OrgRepository.kt
  ui/
    sheet/
      BuildingSheetFragment.kt      ← или включить в activity layout
      OrgSheetContent.kt
  viewmodel/
    MapViewModel.kt
  res/layout/
    activity_map.xml                ← CoordinatorLayout + BottomSheet
app/src/test/java/.../
  map/BuildingHighlightTest.kt
  data/api/ApiDtoContractTest.kt    ← расширить
```

DTO **не дублировать** — использовать существующие `BuildingDto.kt`, `OrgDto.kt`.

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
.\scripts\stage11-verify.ps1
```

---

## 7. Пути, которые меняет этап

- `apps/android/**` (код, layout, тесты)
- `scripts/stage11-verify.ps1`, `scripts/stage11-verify.sh`
- `apps/android/README.md`
- Документы: этот файл; ссылки в [MOBILE.md](MOBILE.md), [PLAN.md](../PLAN.md), [ARCHITECTURE.md](../ARCHITECTURE.md)

**Не менять:** `apps/web/**`, `apps/api/**` (контракт `/v1`), PostGIS schema, `infra/preview/style.json` (веб), MBTiles, PMTiles.

---

## 8. Prompt для агента (один чат = этот этап)

```text
Сделай STAGE-11 строго по Docs/mobile/STAGE-11-android-building.md.

Нужны: Retrofit к /v1/buildings/at, /buildings/:id, /orgs/:id; onMapClick;
GeoJSON selected-building; BottomSheet building + organization; Snackbar 404/422;
unit-тесты; stage11-verify.ps1. Не трогай apps/web, apps/api, поиск, org bbox pins.
Референс контракта: apps/web/src/lib/buildingApi.ts, BottomSheet.tsx.
```

---

## 9. Выход в следующий этап

После зелёного `stage11-verify` → [STAGE-12-android-search.md](STAGE-12-android-search.md) (autocomplete `/v1/search`). Далее org-пины — [STAGE-13-android-poi.md](STAGE-13-android-poi.md); transit — [MOBILE.md](MOBILE.md) §6 этап 5.2.
