# Этап 5. Клик по зданию: контур, адрес, организации, карточка

Связан с [PLAN.md](PLAN.md), [STAGE-04-web-map.md](STAGE-04-web-map.md), [STAGE-02-postgis.md](STAGE-02-postgis.md), [ARCHITECTURE.md](ARCHITECTURE.md), [PERFORMANCE.md](PERFORMANCE.md), [MOBILE.md](MOBILE.md).

Цель: карта этапа 4 **расширяется** до поведения 2ГИС «тап по дому». Клик по зданию (координата на карте) → `GET /v1/buildings/at` → подсветка **контура из PostGIS** (GeoJSON), bottom sheet со **списком адресов и организаций**. Тап по организации → `GET /v1/orgs/:id` — полная карточка (телефон, часы, сайт). Поиск и POI из тайлов **остаются**; приоритет hit-test зафиксирован в коде.

Вход этапа 4, без которого не начинать:

- `scripts/pipeline-stage04.ps1` (или `.sh`) завершился с кодом **0**
- `docker compose` → `postgis`, `meilisearch`, `api`, `tiles` running
- `apps/web` — зелёный `stage04-verify` и unit-тесты `npm test`
- PostGIS: пороги C5–C7 и фикстура F6 этапа 2 (здание с ≥ 2 организациями)

Этап **не** добавляет админку editorial, Valhalla/OTP/Photon, маршруты, Capacitor, **не** пересобирает PMTiles и **не** меняет SQL-схему PostGIS (только новые read-only запросы в API).

Модели: **Opus 5 Thinking** — SQL `buildings/at`, контракт JSON, упрощение контура (B6). **Grok 4.6** — sheet «здание + список org», переход в карточку org. **Composer 2.5** — Fastify routes, verify/benchmark, Vitest. **GPT-5.6 Sol** — если API не видит PostGIS или GIST не используется.

---

## 1. Зачем этот этап

Этап 4 доказал карту и поиск. Пользователь 2ГИС ожидает: **кликнул на дом — увидел, кто там сидит**.

Без этого этапа PostGIS-справочник (этап 2) и Meilisearch (этап 3) не проверяются end-to-end на сценарии «здание → список → карточка». Пороги **B1–B7, B4** из [PERFORMANCE.md](PERFORMANCE.md) становятся обязательными именно здесь.

---

## 2. Что входит и что нет

### Входит

- API Fastify (расширение `apps/api`):
  - `GET /v1/buildings/at?lon=&lat=`
  - `GET /v1/buildings/:id`
  - `GET /v1/orgs/:id`
  - `GET /v1/orgs?bbox=&limit=` — пины организаций в видимой области (лимит **200**, B5)
- PostGIS-запросы: `ST_Contains` / `ST_DWithin` по `building.geom` (GIST); проверка точки внутри `city_boundary`
- Упрощённый GeoJSON контура в ответе (`ST_Simplify`, бюджет **≤ 50 KB**, B6)
- Клиент `apps/web`:
  - клик по карте → здание (после POI и маркера)
  - слой `selected-building` (GeoJSON fill + outline)
  - bottom sheet: режимы **building** (адреса + список org) и **organization** (полная карточка)
  - URL: `?bldg=`, `?org=` (совместимо с `?q=&sel=` этапа 4)
  - выбор hit поиска с `building_id` → flyTo + подсветка здания + sheet building
- Vitest: API-клиент, маппинг ответов, приоритет selection, URL-state
- Скрипты `scripts/stage05-verify.mjs`, `stage05-benchmark.mjs`, `pipeline-stage05.ps1` / `.sh`
- Фикстура здания F6 в verify (SQL или зафиксированный UUID после первого зелёного прогона)

### Не входит

- `PUT /v1/admin/orgs/:id`, reindex editorial — **этап 6**
- Photon fallback в поиске
- Valhalla, OTP, GTFS, `POST /v1/route`
- Маршрут «до организации», кнопка «маршрут»
- Подсветка здания из **векторных тайлов** (контур только PostGIS)
- Dedup canonical OSM+RGZ адресов в UI (v1: показать оба, если оба привязаны)
- `packages/shared` / OpenAPI codegen — типы дублировать локально в `apps/web/src/types/` и `apps/api/src/types/`
- Half/full sheet с фото, отзывами, 3D
- Capacitor, офлайн

---

## 3. Критерии выполнения (Definition of Done)

Этап закрыт, только если выполнены **все** пункты. `scripts/stage05-verify` → exit **0**. `pipeline-stage04` по-прежнему зелёный (регрессия).

### 3.1. Предусловия

| # | Критерий | Как проверить |
|---|---|---|
| P1 | Этап 4 закрыт | `pipeline-stage04` → exit 0 |
| P2 | Сервисы up | `docker compose ps` → postgis, meilisearch, api, tiles |
| P3 | PostGIS conflation | C6 ≥ 40%, C7 ≥ 300 зданий с org (stage02-verify) |
| P4 | Фикстура F6 | Есть `building_id` с `count(organization) ≥ 2` |

### 3.2. API — здание

| # | Критерий | Порог | ID |
|---|---|---|---|
| A1 | `GET /v1/buildings/at` внутри здания | **200**, тело `{ id, label? }` минимум | — |
| A2 | Точка вне всех зданий | **404** `{ "error": "building_not_found" }` | — |
| A3 | Точка вне `city_boundary` | **422** `{ "error": "outside_city" }` за **≤ 30 ms** | R8-подобно |
| A4 | Перекрывающиеся полигоны | здание с **большей площадью** при `ST_Contains` на нескольких | — |
| A5 | p95 `/buildings/at` | **≤ 100 ms** сервер, 30 прогонов | B2 |
| A6 | p95 `/buildings/:id` | **≤ 150 ms**, ≤ 100 org в здании | B3 |
| A7 | GeoJSON `geometry` в `:id` | **≤ 50 KB** gzip или raw JSON | B6 |
| A8 | Здание без org | **200**, `organizations: []`, не timeout | B7 |

**Query `/buildings/at`**

| Параметр | Правило |
|---|---|
| `lon`, `lat` | обязательны, WGS84, finite |

**Пример 200 `/buildings/at`**

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "label": "Bulevar oslobođenja 12"
}
```

`label` — первый RGZ/OSM адрес здания или `name` здания, иначе `"Zgrada"`.

**Пример 200 `/buildings/:id`**

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": null,
  "centroid": { "lon": 19.845, "lat": 45.255 },
  "geometry": { "type": "Polygon", "coordinates": [[[...]]] },
  "addresses": [
    {
      "id": "addr:rgz:123",
      "label": "Bulevar oslobođenja 12",
      "street": "Bulevar oslobođenja",
      "housenumber": "12",
      "source": "rgz"
    }
  ],
  "organizations": [
    {
      "id": "org:osm:n123",
      "name": "Apoteka Benu",
      "category_slug": "pharmacy",
      "category_name": "Apoteka",
      "floor": null
    }
  ]
}
```

`organizations` — краткие строки для списка; полные поля — только в `/orgs/:id`.

### 3.3. API — организация

| # | Критерий | Порог | ID |
|---|---|---|---|
| O1 | `GET /v1/orgs/:id` существующий | **200**, поля ниже | — |
| O2 | Несуществующий id | **404** | — |
| O3 | p95 `/orgs/:id` | **≤ 80 ms** | B1 |
| O4 | `id` формат | `org:{source}:{source_id}` как в Meilisearch | — |

**Тело 200**

```json
{
  "id": "org:osm:n123",
  "name": "Apoteka Benu",
  "source": "osm",
  "category_slug": "pharmacy",
  "category_name": "Apoteka",
  "phones": ["+381 21 123456"],
  "website": "https://example.rs",
  "hours": "Mo-Fr 08:00-20:00",
  "floor": null,
  "tags": ["brand:benu"],
  "address": {
    "label": "Bulevar oslobođenja 12",
    "street": "Bulevar oslobođenja",
    "housenumber": "12"
  },
  "building_id": "550e8400-e29b-41d4-a716-446655440000",
  "location": { "lon": 19.8452, "lat": 45.2551 }
}
```

Пустые `phones` / `website` / `hours` — `[]` или `null`, не выдумывать.

### 3.4. API — пины bbox

| # | Критерий | Порог | ID |
|---|---|---|---|
| B5a | `GET /v1/orgs?bbox=minLon,minLat,maxLon,maxLat` | **200**, массив ≤ **200** | B5 |
| B5b | p95 bbox | **≤ 150 ms** | B5 |
| B5c | Поля пина | `id`, `name`, `category_slug`, `lon`, `lat` | — |

Клиент запрашивает bbox при `zoom ≥ 15` и debounce **300 ms** после `moveend`. На z14 пины не грузить (шум).

### 3.5. Клиент — клик и слои

| # | Критерий | Как проверить |
|---|---|---|
| C1 | Приоритет клика | POI (`poi-dot`/`poi-label`) → маркер → **здание API** → пустой клик закрывает sheet |
| C2 | Подсветка | После 200 `/buildings/at` + `/buildings/:id` слой `selected-building` visible, контур совпадает с PostGIS |
| C3 | Sheet building | Заголовок — адрес или «Zgrada»; список org ≥ 1 на фикстуре F6 |
| C4 | Sheet org | Тап по org → `/orgs/:id`; телефон/часы/сайт если есть в БД |
| C5 | Поиск → здание | Hit org с `building_id` → flyTo + контур + sheet building (не только peek hit) |
| C6 | URL | `?bldg=<uuid>` reload восстанавливает контур и sheet; `?org=org:…` — карточку org |
| C7 | 404 здание | Лёгкий toast «Nema zgrade» / «Здание не найдено», карта жива |
| C8 | POI tile | По-прежнему **без** `/orgs/:id` до явного тапа «Открыть в справочнике» (опционально) или сразу org если POI совпал с org id — **зафиксировать одно поведение в коде** |

Рекомендация v1: клик по **tile POI** остаётся карточкой из MVT (этап 4); кнопка «Više» / переход — если `queryRenderedFeatures` дал `osm_id`, попытка match `org:osm:…` — **не блокер этапа 5**, достаточно клика по **зданию** и списка org.

### 3.6. UI-фикстуры (verify)

Playwright/Puppeteer на `http://127.0.0.1:5173` (или preview `:4173`).

| # | Сценарий | Ожидание |
|---|---|---|
| F1 | Клик центр F6 (здание с ≥2 org) | sheet building, ≥ 2 `[data-testid=building-org]` |
| F2 | Клик первый org в списке | `[data-testid=org-card]`, имя org |
| F3 | `?bldg=<uuid F6>` reload | контур + sheet без повторного клика |
| F4 | Клик по воде/парку | 404 или closed sheet, без 500 |
| F5 | Hit поиска org с building_id | подсветка здания |
| F6 | bbox пины z16 | ≥ 10 `[data-testid=org-pin]` в центре (если данные есть) |
| F7 | Регрессия этапа 4 | `SKIP_STAGE04=0` subset: search apotek + sheet — OK |

### 3.7. Производительность (обязательные SLO)

Из [PERFORMANCE.md](PERFORMANCE.md) §7 — **требовать на этом этапе**:

| ID | Порог | Как мерить |
|---|---|---|
| B1 | `/orgs/:id` p95 ≤ **80 ms** | `stage05-benchmark.mjs`, 30× |
| B2 | `/buildings/at` p95 ≤ **100 ms** | curl/API benchmark |
| B3 | `/buildings/:id` p95 ≤ **150 ms** | F6 building id |
| B4 | Клик → список org в DOM p95 ≤ **350 ms** | Puppeteer + Performance |
| B5 | bbox `/orgs` ≤ **200** obj, p95 ≤ **150 ms** | benchmark |
| B6 | GeoJSON контура ≤ **50 KB** | assert на ответ `:id` |
| B7 | Пустое здание в бюджете B2/B3 | SQL fixture + API |

Этап 4 SLO (T1, T2, S1 e2e, L1) — **не регрессировать**; verify может запускать короткий subset stage04.

### 3.8. Повторяемость

| # | Критерий | Как проверить |
|---|---|---|
| R1 | `pipeline-stage05` | test → build → verify → benchmark → exit 0 |
| R2 | PMTiles mtime | не изменился |
| R3 | `stage04-verify` | subset или full — зелёный |
| R4 | `stage03-verify` | search apotek ≥ 5 hits |

### 3.9. Чек-лист закрытия (для человека)

- [ ] `docker compose up -d postgis meilisearch api tiles`
- [ ] `cd apps/web && npm test && npm run dev` — клик по дому в центре открывает список
- [ ] `scripts/pipeline-stage05.ps1` → exit 0
- [ ] B2, B4, B6 в отчёте benchmark
- [ ] Визуально: контур здания + sheet «как 2ГИС» (ручная приёмка)
- [ ] В git: routes API, расширения web, verify; **нет** `.env` с секретами

---

## 4. Константы

| Параметр | Значение |
|---|---|
| Building hit | координата `click` / `touchend`, не bbox тайла |
| `/buildings/at` fallback | `ST_DWithin(geom::geography, point, **3** m)` если нет `ST_Contains` |
| Simplify tolerance | `ST_Simplify(geom, **0.00001**)` (~1 m) или адаптивно до B6 |
| Bbox org pins | debounce **300 ms**, min zoom **15**, limit **200** |
| Sheet peek (building) | **120 px** (список org); org card — **half** ~45% экрана |
| URL keys | `bldg`, `org`, сохранять `q`, `sel` |
| Org pin layer | `org-pins` GeoJSON source |
| Building layer | `selected-building` fill + `selected-building-outline` line |

---

## 5. Hit-test и порядок обработки

```text
map.on('click', e):
  if queryRenderedFeatures(poi-dot, poi-label)[0]:
    → sheet POI (этап 4), clear building highlight
    return
  if queryRenderedFeatures(selected-marker)[0]:
    return
  lon, lat = e.lngLat
  loadingBuilding = true
  GET /v1/buildings/at?lon=&lat=
    404 → toast, close building sheet
    422 → toast outside city
    200 → GET /v1/buildings/:id
      → setGeoJSON(selected-building)
      → openSheet('building')
      → history ?bldg=id (& clear org=)
  if no building and not marker:
    closeSheet()  // как Poi2 этапа 4
```

Параллельные запросы: abort предыдущий `buildings/at` при новом клике (как S3 в поиске).

---

## 6. Bottom sheet v2

Режимы (один компонент, prop `mode`):

| mode | Содержимое | Высота |
|---|---|---|
| `closed` | — | 0 |
| `peek` | hit поиска / tile POI (этап 4) | 88 px |
| `building` | адрес(а), список org, chevron | peek → **120 px** min, scroll в half |
| `organization` | имя, категория, телефоны (tel:), часы, сайт, адрес | **half** |

Переход org → назад → `building`. Свайп вниз из org → building; из building → closed + снять контур.

`data-testid`: `building-sheet`, `building-org`, `org-card`, `org-phone`, `org-hours`.

---

## 7. PostGIS (только read, в API)

**`/buildings/at`** (псевдо-SQL):

```sql
-- 1) city_boundary
-- 2) ST_Contains(b.geom, ST_SetSRID(ST_Point(lon, lat), 4326))
--    ORDER BY ST_Area(b.geom) DESC LIMIT 1
-- 3) else ST_DWithin(b.geom::geography, point::geography, 3) ORDER BY dist LIMIT 1
```

**`/buildings/:id`**

```sql
-- building + ST_AsGeoJSON(ST_Simplify(geom, tol))
-- addresses WHERE building_id = :id ORDER BY source, street, housenumber
-- organizations JOIN category WHERE building_id = :id ORDER BY name LIMIT 100
```

Индексы `building_geom_gix`, `building_geog_gix`, `address_building_idx`, `organization_building_idx` — уже в schema этапа 2; verify B2 падает, если seq scan.

---

## 8. Структура файлов

```text
apps/api/src/
  routes/buildings.ts      ← /buildings/at, /buildings/:id
  routes/orgs.ts           ← /orgs/:id, /orgs bbox
  services/postgis.ts      ← pool, queries
  types/building.ts
  types/org.ts
apps/web/src/
  hooks/useBuildingPick.ts
  hooks/useOrgPins.ts
  components/BuildingSheet.tsx
  components/OrgCard.tsx
  components/OrgPinLayer.tsx   ← или логика в MapView
  lib/buildingApi.ts
  lib/orgApi.ts
  types/building.ts
  types/org.ts
infra/postgis/
  fixtures.sql               ← + stage05_building_fixture (uuid F6) после первого прогона
scripts/
  stage05-verify.mjs
  stage05-verify.ps1 / .sh
  stage05-benchmark.mjs
  stage05-lib.mjs
  pipeline-stage05.ps1 / .sh
```

---

## 9. Пайплайн (порядок)

1. `docker compose up -d postgis meilisearch api tiles`
2. `cd apps/web && npm ci && npm test && npm run build`
3. `cd apps/api && npm run build` (если отдельный build)
4. `node scripts/stage05-verify.mjs`
5. `node scripts/stage05-benchmark.mjs`
6. Subset `stage04-verify` (или `SKIP_WEB_BUILD=1` full)
7. Запись в `data/README.md`: B2/B3/B4 p95, дата

Не запускать `pipeline-stage02` / пересборку PMTiles.

---

## 10. `stage05-verify` — обязательные проверки

```text
assert api buildings/at F6 point == 200
assert api buildings/:id organizations.length >= 2
assert api orgs/:id first org == 200 with name
assert geojson_bytes <= 51200
assert p95 buildings/at <= 100ms (benchmark gate)
assert ui click F6 → building-org count >= 2
assert ui org click → org-card visible
assert url ?bldg= restore
assert bbox orgs limit <= 200
assert stage04 search apotek subset OK
```

Любой провал → exit **1**.

---

## 11. Типичные поломки

| Симптом | Что делать |
|---|---|
| Всегда 404 на клик | Клик по **тайлу** здания без PostGIS-полигона; проверить `/buildings/at` curl в центре |
| p95 > 100 ms | Нет GIST, full table scan; `EXPLAIN` на запрос |
| GeoJSON > 50 KB | Увеличить simplify tolerance; не отдавать MultiPolygon без simplification |
| Список org пуст при видимых POI | C6 провален; `link_buildings.sql` |
| Дубли адресов OSM+RGZ | Ожидаемо v1; не dedup в API |
| Контур смещён | Не путать 4326 lon/lat в GeoJSON |
| Sheet не показывает org | z-index; async race — abort controller |
| B4 > 350 ms | Два последовательных fetch без pipeline; объединить или prefetch `:id` после `at` |
| F6 fail | Записать реальный UUID F6 в `fixtures.sql` |

---

## 12. Как давать задачу AI

Сначала **Opus 5** (SQL + JSON контракт + B6). Потом **Composer 2.5** (routes + web + verify). UI sheet — **Grok 4.6**.

Промпт:

```
Сделай Этап 5 строго по Docs/STAGE-05-building-click.md.

Вход: зелёный pipeline-stage04, docker compose (postgis, meilisearch, api, tiles), apps/web с картой и поиском.
Не добавляй админку, Valhalla, OTP, Photon, маршруты, Capacitor. Не пересобирай PMTiles, не меняй schema.sql этапа 2.

Нужны: GET /v1/buildings/at, /buildings/:id, /orgs/:id, /orgs bbox; подсветка GeoJSON; sheet building + org card; URL ?bldg=&org=; stage05-verify и pipeline-stage05 с порогами B1–B7, F1–F7.

Запусти test, verify, benchmark. В конце выведи B2/B4 p95 и результат F1/F2 по F6 fixture.
```

---

## 13. Оценка времени

| Работа | Ориентир |
|---|---|
| PostGIS queries + routes buildings | 2–3 часа |
| Routes orgs + bbox | 1–2 часа |
| MapView highlight + click flow | 2–3 часа |
| BuildingSheet + OrgCard | 2–4 часа |
| URL state + search→building | 1–2 часа |
| verify + benchmark + Vitest | 2–3 часа |
| F6 fixture + отладка B4 | 1–2 часа |

---

## 14. Выход в этап 6

Этап 6 (админка editorial) стартует только при зелёном stage05.

Этап 6 добавит:

- `PUT /v1/admin/orgs/:id` — телефон, часы, website поверх OSM
- reindex одного документа Meilisearch (S5)
- простую форму в `apps/web` или отдельный `/admin`

Клиент этапа 5 **не удалять** — карточка org уже показывает поля из PostGIS; editorial обновит те же поля после save.

Пороги маршрутов R1–R9 — этапы 7–9 ([PERFORMANCE.md](PERFORMANCE.md) §8).
