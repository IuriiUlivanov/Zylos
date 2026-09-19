# Этап 12. Android: autocomplete поиска

Связан с [MOBILE.md](MOBILE.md) (§6 этап 5.1), [design/MOBILE-DESIGN.md](design/MOBILE-DESIGN.md) (§4.1–4.2 search UI), [STAGE-11-android-building.md](STAGE-11-android-building.md), [STAGE-03-meilisearch.md](STAGE-03-meilisearch.md), [ARCHITECTURE.md](ARCHITECTURE.md), [PERFORMANCE.md](PERFORMANCE.md).

Цель: **`apps/android`** получает рабочий **autocomplete** — строка поиска **снизу** экрана (2GIS Android, [design/MOBILE-DESIGN.md](design/MOBILE-DESIGN.md) §2), `GET /v1/search` (Meilisearch), dropdown **вверх** от search card, выбор hit → `easeTo` + маркер + bottom sheet **над** поиском (здание / org / peek). **Room** — кэш последних запросов (офлайн только история, не индекс). Офлайн-карта MBTiles **остаётся**; без сети поиск показывает ошибку, карта жива.

Вход, без которого не начинать:

- [STAGE-11-android-building.md](STAGE-11-android-building.md) закрыт: `scripts/stage11-verify.ps1` → exit **0**
- `docker compose` → `postgis`, `api`, **`meilisearch`** running; индекс проиндексирован (`scripts/index-meilisearch.mjs`)
- `GET /v1/search` отвечает локально; фикстуры F1–F7 — [STAGE-03-meilisearch.md](STAGE-03-meilisearch.md) §3.6
- Kotlin DTO `SearchDto.kt` зеркалит `apps/api/src/types/search.ts` — **форму JSON не менять**
- Bottom sheet building / organization из STAGE-11 **переиспользовать**, не дублировать

Этап **не** добавляет browse org-пины (`GET /v1/orgs?bbox=`), Search Multi («все hits на карте»), маршруты OTP, правки `apps/web` / `apps/api`, пересборку MBTiles.

Модели: **Grok 4.6** — SearchBar, dropdown, маркер на карте. **Composer 2.5** — Retrofit, Room, verify, unit-тесты. **Opus 5** — если путается контракт `/v1/search` или приоритет hit-test.

---

## 1. Зачем этот этап

STAGE-11 доказал «тап по дому → список org». Пользователь 2ГИС ожидает: **набрал «апотека» или «бulevar 47» — увидел подсказки и перешёл к объекту**.

Без этого этапа Meilisearch не проверяется end-to-end на Android. Пороги **S1–S4** из [PERFORMANCE.md](PERFORMANCE.md) становятся обязательными для мобильного сценария «ввод → список → карточка».

После STAGE-12 можно переходить к browse org-пинам и Search Multi — [design/MOBILE-POI-ZOOM.md](design/MOBILE-POI-ZOOM.md), [STAGE-13-android-poi.md](STAGE-13-android-poi.md) (черновик). Transit — отдельно, [MOBILE.md](MOBILE.md) §6 этап 5.2.

---

## 2. Что входит и что нет

### Входит

- **`GET /v1/search`** в `ZylosApi`:
  - query: `q`, `limit` (default **10**, max **15**), `lat`, `lon`, опционально `kind`
  - debounce **150 ms**; `q.length < 2` — без запроса (S4)
  - отмена устаревших запросов (новый символ / clear отменяет предыдущий — S3)
- **`SearchRepository`** — обёртка над Retrofit; geo из центра камеры (или последний `onCameraIdle`)
- **UI поиска** (View system v1, [design/MOBILE-DESIGN.md](design/MOBILE-DESIGN.md) §4.1–4.2):
  - `searchChrome` закреплён **снизу** (`layout_gravity=bottom`, inset nav bar)
  - `EditText` + кнопка clear; object sheet — `marginBottom` = `search_dock_height`
  - `RecyclerView` dropdown **над** search card (overlay вверх, не BottomSheet)
  - иконка kind: address / organization (как `SearchDropdown.tsx`)
  - loading indicator при `q.length ≥ 2`
- **Выбор hit** (логика как `apps/web/src/App.tsx` `selectHit`):
  - `easeTo(lon, lat)`, zoom ≥ **16**, duration **800 ms**
  - GeoJSON source `selected-marker` + `SymbolLayer` (или circle — согласовать с mobile style)
  - если `building_id != null` → `GET /buildings/:id` → sheet **building** + контур (STAGE-11)
  - если `kind == organization` и здание не загрузилось → sheet **organization** через `GET /orgs/:id` (id из `hit.id`)
  - иначе → sheet **peek**: заголовок `label`, подзаголовок «Adresa» / категория
- **Приоритет hit-test** (обновить комментарий TODO из STAGE-11):
  1. Тап по search chrome / dropdown → **не** `onMapClick`
  2. Тап по маркеру поиска → peek / org (если есть данные)
  3. Тап по карте → здание через API (STAGE-11)
- **Room** (минимальный v1):
  - таблица `search_history`: `query`, `hit_id?`, `label?`, `timestamp`
  - хранить последние **10** уникальных `query`; при фокусе на пустом поле — показать историю
  - офлайн: только локальная история; live search — «Nema veze sa serverom»
- **Ошибки UI** (sr-Latn):
  - 0 hits → «Ništa nije pronađeno»
  - Meilisearch down / 503 → «Pretraga privremeno nedostupna»
  - сеть → «Nema veze sa serverom» (уже есть)
- **Unit-тесты:** debounce/stale logic, DTO, Room DAO, peek vs building routing (без эмулятора)
- **`scripts/stage12-verify.ps1` / `.sh`** — статика + curl F1–F5 к `/v1/search`

### Не входит

- `GET /v1/orgs?bbox=` — browse-пины — **STAGE-13**
- Search Multi (≤15 пинов на карте, fitBounds) — **STAGE-13**
- Фильтры `poi-dot` / `rank` в mobile Style JSON — **STAGE-13**
- `POST /v1/route`, OTP, Valhalla — **этап 5.2**
- Кнопка «Маршрут до организации»
- Photon fallback на клиенте (делает API при 0 hits)
- Hilt — не обязателен; Room + ручная фабрика допустимы
- Правки `apps/api`, Meilisearch settings, `apps/web`
- iOS, Capacitor

---

## 3. Критерии выполнения (Definition of Done)

Этап закрыт, только если `scripts/stage12-verify.ps1` (или `.sh`) → exit **0** **и** `./gradlew :app:testDebugUnitTest` → exit **0**.

### 3.1. Предусловия

| # | Критерий | Как проверить |
|---|---|---|
| P1 | STAGE-11 закрыт | `stage11-verify` → exit 0 |
| P2 | API + Meilisearch up | `curl …/v1/health` → 200; `docker compose ps meilisearch` running |
| P3 | Индекс | `stage03-verify` → exit 0 или F1 curl вручную → ≥ 5 hits |
| P4 | Search endpoint | `curl "http://127.0.0.1:3000/v1/search?q=apotek&limit=15"` → 200, `hits.length ≥ 5` |

### 3.2. API (регрессия — клиент не меняет контракт)

| # | Критерий | Порог | ID |
|---|---|---|---|
| A1 | F1 `q=apotek&limit=15` | ≥ 5 hits, все `kind=organization`, ≥ 1 `category_slug=pharmacy` | — |
| A2 | F3 `q=bulevar` | ≥ 10 hits, ≥ 5 `kind=address` | — |
| A3 | F5 `q=a` | **200**, `hits: []` | S4 |
| A4 | F6 `q=` (пустой) | **200**, `hits: []`, ≤ 20 ms | S4 |
| A5 | `limit` | сервер обрезает ≤ **15** | S2 |

Контракт JSON — `apps/api/src/types/search.ts`. Kotlin — `SearchDto.kt`.

### 3.3. Android — поиск

| # | Критерий | Как проверить |
|---|---|---|
| M1 | Retrofit `/v1/search` | `ZylosApi.search(q, limit, lat, lon)` |
| M2 | Debounce 150 ms | Unit-тест: два быстрых символа → один запрос |
| M3 | Stale cancel | Быстро «ap» → «apotek» — в UI финально второй ответ (S3) |
| M4 | Min length 2 | «a» — dropdown пустой, без HTTP |
| M5 | Geo bias | `lat`/`lon` из камеры передаются в query |
| M6 | Dropdown | ≥ 1 hit отображается с `label` и kind-icon |
| M7 | Select org hit | Тап → камера летит, sheet building или org |
| M8 | Select address hit | Тап → маркер + peek sheet «Adresa» |
| M9 | Clear search | × очищает query, dropdown, маркер; sheet закрывается |
| M10 | Map click при фокусе search | Не вызывает `/buildings/at` пока dropdown открыт |
| M11 | Airplane Mode | История Room видна; live search → Snackbar сети |
| M12 | STAGE-11 регрессия | Тап по карте без фокуса search — здание работает |

### 3.4. Android — Room

| # | Критерий | Как проверить |
|---|---|---|
| R1 | Сохранение | После select hit query попадает в history |
| R2 | Лимит 10 | Unit-тест DAO: 11-й запрос вытесняет старый |
| R3 | Офлайн history | Airplane Mode + focus на пустом поле → последние queries |

### 3.5. Тесты и verify

| # | Критерий | Как проверить |
|---|---|---|
| T1 | Unit tests | `:app:testDebugUnitTest` exit 0 |
| T2 | DTO contract | `ApiDtoContractTest` покрывает `SearchResponse` |
| T3 | stage12-verify | exit 0 |

Порог **S1** p95 ≤ 200 ms e2e — замер на эмуляторе / mid-range после первой сборки; verify проверяет curl p95 к localhost, не подменяет UI-замер.

---

## 4. Поведение клиента (спецификация)

### 4.1. Последовательность запросов

```text
onQueryChange(text)
  → if text.length < 2: clear hits, no HTTP
  → debounce 150 ms
  → GET /v1/search?q=&limit=10&lat=&lon=
  → render dropdown
onSelectHit(hit)
  → easeTo(hit.lon, hit.lat, zoom=max(current, 16))
  → set selected-marker
  → if hit.building_id: GET /buildings/{id} → sheet building
  → else if hit.kind == organization: GET /orgs/{id} → sheet organization
  → else: sheet peek (label only)
  → Room: save query + hit metadata
```

Источник подсказок — **Meilisearch через API**, не локальный индекс ([ARCHITECTURE.md](ARCHITECTURE.md)).

### 4.2. Константы (как веб)

| Константа | Значение | Файл-референс |
|---|---|---|
| `SEARCH_DEBOUNCE_MS` | 150 | `apps/web/src/lib/constants.ts` |
| `SEARCH_MIN_LENGTH` | 2 | там же |
| `SEARCH_LIMIT` | 15 (клиент шлёт 10) | там же |
| `FLY_DURATION_MS` | 800 | там же |

### 4.3. Маркер поиска на карте

- Source id: `selected-marker`
- Layer: `SymbolLayer` или `CircleLayer` (цвет согласовать с mobile style; референс — `MARKER_*` в `constants.ts`)
- При clear search / close sheet / новом map pick — remove marker
- Не путать с `selected-building` (контур PostGIS)

### 4.4. UI-тексты (sr-Latn v1)

| Ситуация | Текст |
|---|---|
| Placeholder | `Pretraga…` |
| Empty results | `Ništa nije pronađeno` |
| Search unavailable | `Pretraga privremeno nedostupna` |
| Network error | `Nema veze sa serverom` |
| Loading | `Učitavam…` |
| Kind address | `Adresa` |
| Kind organization | категория или `Organizacija` |
| History section | `Nedavno` |

---

## 5. Структура файлов (ожидаемые пути)

```text
apps/android/app/src/main/java/rs/zylos/novisad/
  MapActivity.kt                    ← bind search UI, dropdown, camera geo
  map/
    SearchMarker.kt                 ← GeoJSON selected-marker
  data/
    api/
      ZylosApi.kt                   ← + search()
    repository/
      SearchRepository.kt
    local/
      ZylosDatabase.kt
      SearchHistoryDao.kt
      SearchHistoryEntity.kt
  ui/
    search/
      SearchDropdownAdapter.kt
  viewmodel/
    MapViewModel.kt                 ← + search state / selectHit (или SearchViewModel)
  res/layout/
    activity_map.xml                ← EditText + dropdown RecyclerView
    item_search_hit.xml
    item_search_history.xml
app/src/test/java/.../
  data/repository/SearchRepositoryTest.kt
  viewmodel/SearchLogicTest.kt      ← debounce, stale, routing
  data/local/SearchHistoryDaoTest.kt
```

DTO **не дублировать** — расширить `SearchDto.kt` только если в API появились новые поля (на этапе 12 — **нет**).

---

## 6. Сборка и отладка

```powershell
# API + Meilisearch (хост)
docker compose up -d postgis meilisearch api
node scripts/index-meilisearch.mjs   # если индекс пуст

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
.\scripts\stage12-verify.ps1
```

Ручная приёмка: ввести «apotek» → ≥ 5 org; выбрать hit → камера + sheet; очистить → маркер исчез; Airplane Mode → история без live hits.

---

## 7. Пути, которые меняет этап

- `apps/android/**` (код, layout, Room, тесты)
- `scripts/stage12-verify.ps1`, `scripts/stage12-verify.sh`
- `apps/android/README.md`
- Документы: этот файл; ссылки в [MOBILE.md](MOBILE.md), [PLAN.md](PLAN.md), [ARCHITECTURE.md](ARCHITECTURE.md)

**Не менять:** `apps/web/**`, `apps/api/**` (контракт `/v1`), Meilisearch settings, PostGIS schema, MBTiles, PMTiles, mobile Style JSON rank-фильтры.

---

## 8. Prompt для агента (один чат = этот этап)

```text
Сделай STAGE-12 строго по Docs/STAGE-12-android-search.md.

Нужны: GET /v1/search с debounce 150 ms и stale cancel; EditText + dropdown;
selectHit → easeTo + selected-marker + sheet (building/org/peek через STAGE-11);
Room history (10 записей); unit-тесты; stage12-verify.ps1.
Не трогай apps/web, apps/api, org bbox pins, OTP/route.
Референс: apps/web/src/hooks/useSearch.ts, SearchBar.tsx, App.tsx selectHit.
```

---

## 9. Выход в следующий этап

После зелёного `stage12-verify` → browse org-пины и Search Multi по [design/MOBILE-POI-ZOOM.md](design/MOBILE-POI-ZOOM.md) (**STAGE-13**). Transit (`POST /v1/route`) — параллельно на бэкенде, UI маршрута — после POI или отдельным этапом 5.2.
