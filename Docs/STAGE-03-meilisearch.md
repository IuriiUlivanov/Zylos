# Этап 3. Meilisearch и поиск API

Связан с [PLAN.md](PLAN.md), [STAGE-02-postgis.md](STAGE-02-postgis.md), [ARCHITECTURE.md](ARCHITECTURE.md), [PERFORMANCE.md](PERFORMANCE.md).

Цель: PostGIS из этапа 2 проецируется в **Meilisearch**; сервис **`api`** отвечает на `GET /v1/search`. Поиск адреса и организации работает из curl/скрипта, без React и без карты «как 2ГИС».

Вход этапа 2, без которого не начинать:

- `stage02-verify.sql` завершился с кодом **0**
- В PostGIS есть таблицы `address`, `organization`, `category` с объёмами не ниже порогов C1–C4 этапа 2
- `docker compose` → `postgis` healthy

Этап **не** пересобирает PMTiles, **не** повторяет импорт OSM/RGZ (кроме явного `pipeline-stage03` после уже зелёного этапа 2) и **не** меняет SQL-схему PostGIS.

Модели: **Composer 2.5** — Fastify, скрипт индексации, verify. **Opus 5 Thinking** — схема документов Meilisearch и контракт ответа `/search`. **GPT-5.6 Sol** — если не поднимается контейнер Meilisearch или падает reindex на большом объёме. Замеры S1/S4 — человек или скрипт benchmark.

---

## 1. Зачем этот этап

Карта без поиска — не 2ГИС. Пользователь вводит «apoteka», «булевар 12», «Trg slobode» и ждёт подсказки за доли секунды.

PostGIS умеет `ILIKE`, но as-you-type по 25k адресов и 1.5k организаций с опечатками и префиксами — задача **поискового движка**, не GIST. Meilisearch — проекция; мастер остаётся PostGIS (см. [ARCHITECTURE.md](ARCHITECTURE.md) §8).

Без этого этапа веб-карта (этап 4) не к чему подключить поле поиска, а клик по зданию (этап 5) не проверить end-to-end.

---

## 2. Что входит и что нет

### Входит

- Сервис `meilisearch` в `docker-compose.yml` (имя контейнера не менять)
- Сервис `api` (Fastify, TypeScript) с префиксом `/v1`
- Эндпоинты: `GET /v1/health`, `GET /v1/search`
- Настройки индекса Meilisearch: searchable / filterable / ranking
- Скрипт **полной переиндексации** PostGIS → Meilisearch (идемпотентный)
- Документы: все `address` с непустыми `street` и `housenumber`; все `organization` с `name`
- Поля для выдачи: `kind`, координаты, `building_id`, категория, подпись для UI
- Скрипт проверки `scripts/stage03-verify.ps1` и `.sh` с **жёсткими порогами**
- Пайплайн `scripts/pipeline-stage03.ps1` и `.sh`: index → verify → benchmark
- Переменные в `.env.example`: ключ Meilisearch, URL API
- Запись в `data/README.md`: число документов, дата последней индексации, p95 `/search`

### Не входит

- React, MapLibre, Capacitor, bottom sheet
- `GET /v1/orgs/:id`, `/buildings/at`, `/buildings/:id` — этап 5
- Photon / Nominatim fallback — этап 4+ (контракт API заложить, вызывать не обязательно)
- Valhalla, OTP, GTFS
- Админка editorial и `PUT /v1/admin/orgs` — этап 6 (но `source = editorial` в индексе предусмотреть)
- OpenAPI YAML / codegen — позже; достаточно JSON-контракта в этом файле
- `packages/shared` — появится с полным OpenAPI
- Пересборка тайлов, повторный OSM/RGZ import
- Публичный Overpass, скрейп PlanPlus / NSmart / Google

---

## 3. Критерии выполнения (Definition of Done)

Этап закрыт, только если выполнены **все** пункты. «Meilisearch healthy» недостаточно. `scripts/stage03-verify` должен завершаться с кодом **0**.

### 3.1. Предусловия

| # | Критерий | Как проверить |
|---|---|---|
| P1 | Этап 2 закрыт | `stage02-verify.sql` → success |
| P2 | PostGIS не пустой | `SELECT count(*) FROM address` ≥ 22 000; `organization` ≥ 1 500 |
| P3 | Тот же город | Граница по-прежнему relation **1649672**; PBF не пересобран |
| P4 | Meilisearch не с master key в git | Ключ только `.env` |

### 3.2. Инфраструктура

| # | Критерий | Как проверить |
|---|---|---|
| I1 | Контейнер `meilisearch` | `docker compose ps` → running, healthcheck green |
| I2 | Контейнер `api` | running, слушает `127.0.0.1:3000` (или порт из `.env`) |
| I3 | `GET /v1/health` | **200**, тело `{ "status": "ok", "meilisearch": "ok" }` |
| I4 | Meilisearch RSS | После индекса **≤ 300 MB** (S6 из [PERFORMANCE.md](PERFORMANCE.md)) |

### 3.3. Индекс

| # | Критерий | Минимум | Зачем |
|---|---|---|---|
| M1 | Документы `kind=address` | **22 000** | Не меньше суммы OSM+RGZ адресов с улицей и номером |
| M2 | Документы `kind=organization` | **1 500** | Порог C4 этапа 2 |
| M3 | Документы без координат | **0** | Каждый hit пригоден для карты |
| M4 | Дубликаты по `id` | **0** | Стабильный upsert |
| M5 | Организации без `category_slug` | **0** | Категория в выдаче |
| M6 | Доля `category_slug=other` среди org | **< 35%** | Как на этапе 2 |

Имя индекса: **`zylos`**. Менять нельзя без правки verify и API.

**Стабильный `id` документа:**

- address: `addr:{source}:{source_id}` (пример: `addr:rgz:123456789`)
- organization: `org:{source}:{source_id}` (пример: `org:osm:w482104321`)

### 3.4. Схема документа Meilisearch

Geometрию **не** индексировать текстом. Координаты — поле `_geo` (Meilisearch v1: `{ "lat": number, "lng": number }`).

**Общие поля**

| Поле | Тип | Назначение |
|---|---|---|
| `id` | string | primary key, см. выше |
| `kind` | `"address"` \| `"organization"` | filterable |
| `label` | string | строка для списка подсказок |
| `source` | `osm` \| `rgz` \| `editorial` | filterable |
| `_geo` | object | lat/lng из `address.geom` / `organization.geom` |
| `building_id` | string \| null | UUID здания, если есть FK |
| `postcode` | string \| null | address |

**address**

| Поле | Searchable | Пример |
|---|---|---|
| `street` | да | `Bulevar oslobođenja` |
| `street_sr_cyrl` | да | `Булевар ослобођења` |
| `housenumber` | да | `12` |
| `label` | да | `Bulevar oslobođenja 12` |

**organization**

| Поле | Searchable | Пример |
|---|---|---|
| `name` | да | `Apoteka Benu` |
| `category_slug` | filterable | `pharmacy` |
| `category_name` | да | `Apoteka` (из `category.name_sr`) |
| `tags` | да | `["brand:dm", "wheelchair"]` |

**Настройки индекса** (файл `infra/meilisearch/index-settings.json`):

```json
{
  "searchableAttributes": [
    "label",
    "name",
    "street",
    "street_sr_cyrl",
    "housenumber",
    "category_name",
    "tags"
  ],
  "filterableAttributes": ["kind", "category_slug", "source"],
  "sortableAttributes": ["_geo"],
  "rankingRules": [
    "words",
    "typo",
    "proximity",
    "attribute",
    "sort",
    "exactness"
  ],
  "typoTolerance": { "enabled": true }
}
```

При запросе с `lat`/`lon` API передаёт в Meilisearch sort по `_geoPoint(lat, lng):asc` после текстового ранжирования (или через `sort` query param v1).

### 3.5. API `GET /v1/search`

**Query**

| Параметр | Обязательный | Правило |
|---|---|---|
| `q` | да | строка поиска |
| `limit` | нет | default **10**, max **15** (S2) |
| `lat`, `lon` | нет | WGS84; если оба — geo-bias |
| `kind` | нет | `address` \| `organization` — фильтр |

**Поведение**

| # | Правило |
|---|---|
| A1 | `q` пустой или `q.length < 2` → **200**, `{ "hits": [], "query": "" }` за **≤ 20 ms** (S4), без запроса в Meilisearch |
| A2 | Иначе → Meilisearch multi-search или один индекс с фильтром |
| A3 | Meilisearch недоступен → **503** за **≤ 150 ms**, `{ "error": "search_unavailable" }`, не hang |
| A4 | Ответ p95 CPU сервера **≤ 120 ms** на прогретом индексе (S1) |
| A5 | Каждый hit содержит минимум: `id`, `kind`, `label`, `lat`, `lon`, `building_id?` |

**Пример ответа 200**

```json
{
  "query": "apotek",
  "hits": [
    {
      "id": "org:osm:n1234567890",
      "kind": "organization",
      "label": "Apoteka Benu",
      "name": "Apoteka Benu",
      "category_slug": "pharmacy",
      "lat": 45.2551,
      "lon": 19.8452,
      "building_id": "550e8400-e29b-41d4-a716-446655440000"
    }
  ],
  "processingTimeMs": 4
}
```

Координаты в JSON — **lat/lon** (не GeoJSON order), чтобы клиент этапа 4 не путал.

**Ошибки**

| Код | Когда |
|---|---|
| 400 | `limit` > 15 или не число |
| 503 | Meilisearch down / timeout |
| 500 | неожиданная ошибка (логировать, без stack trace наружу) |

CORS: разрешить origin превью (`http://localhost:8080`) и будущего веба (`http://localhost:5173`).

### 3.6. Поисковые фикстуры (функциональные)

Скрипт verify выполняет HTTP-запросы к `api`. Все должны пройти.

| # | Запрос | Ожидание |
|---|---|---|
| F1 | `q=apotek&limit=15` | ≥ 5 hits, все `kind=organization`, ≥ 1 с `category_slug=pharmacy` |
| F2 | `q=futo` | ≥ 3 hits (улица / район / POI с «Futo» в имени) |
| F3 | `q=булевар` или `q=bulevar` | ≥ 10 hits, среди них ≥ 5 `kind=address` |
| F4 | `q=12&kind=address&lat=45.255&lon=19.845` | ≥ 1 address в радиусе ~2 km от центра (номер «12» + geo) |
| F5 | `q=a` | **200**, `hits: []` (короткий запрос) |
| F6 | `q=` (пустой) | **200**, `hits: []`, время **≤ 20 ms** |
| F7 | Объекты из `infra/postgis/fixtures.sql` | По `name` / адресу каждого из 3 зафиксированных POI — hit в top 5 |

F7 использует те же OSM id, что записаны на этапе 2. Если `fixtures.sql` пуст — этап 2 не закрыт.

### 3.7. Производительность (обязательные SLO этапа)

Из [PERFORMANCE.md](PERFORMANCE.md) §6 — **требовать на этом этапе**:

| ID | Порог | Как мерить |
|---|---|---|
| S1 | p95 `GET /v1/search` ≤ **120 ms** CPU | `scripts/stage03-benchmark.mjs`: 30× `q=apotek`, после прогрева |
| S2 | `limit` ≤ **15** | assert в API + verify |
| S4 | `q.length < 2` ≤ **20 ms** | 30× `q=a` и `q=` |
| S6 | Meilisearch RSS ≤ **300 MB** | `docker stats meilisearch --no-stream` после index |

End-to-end с RTT 50 ms (S1 в PERFORMANCE) проверяется на этапе 4; здесь достаточно server-side p95.

### 3.8. Повторяемость

| # | Критерий | Как проверить |
|---|---|---|
| R1 | Повторный index | Второй запуск: counts M1–M2 ±1%, без дублей M4 |
| R2 | PostGIS не тронут | Counts building/address/org как после этапа 2 |
| R3 | PMTiles не тронут | mtime `data/tiles/novi-sad.pmtiles` без изменений |
| R4 | Команда одной кнопкой | `pipeline-stage03` делает index → verify → benchmark |
| R5 | Verify красный = fail | Сломанный порог в копии скрипта падает |

### 3.9. Чек-лист закрытия (для человека)

- [ ] `docker compose up -d postgis meilisearch api` → все healthy
- [ ] `scripts/pipeline-stage03.ps1` (или `.sh`) → exit 0
- [ ] M1–M6, F1–F7, S1/S4/S6 в отчёте
- [ ] `curl "http://127.0.0.1:3000/v1/search?q=apotek"` — осмысленный JSON
- [ ] В git: `apps/api/`, `infra/meilisearch/`, скрипты; **нет** `.env` с master key

---

## 4. Константы

| Параметр | Значение |
|---|---|
| Индекс Meilisearch | `zylos` |
| Meilisearch URL (внутри compose) | `http://meilisearch:7700` |
| API URL (dev) | `http://127.0.0.1:3000` |
| Master key | `MEILI_MASTER_KEY` в `.env` |
| PostGIS | те же `POSTGRES_*`, что этап 2 |
| БД | `zylos`, CRS **EPSG:4326** |
| Граница города | не индексируется отдельным документом |
| Языки поиска | sr-Latn + sr-Cyrl в searchable; ru/en — через `name:ru` позже editorial |

---

## 5. SQL для экспорта (логика, не копировать слепо)

Скрипт индексации читает PostGIS батчами (например 2 000 строк), не держит 25k строк в RAM Node.

**Addresses**

```sql
SELECT
  'addr:' || source || ':' || source_id AS id,
  'address' AS kind,
  source,
  street,
  street_sr_cyrl,
  housenumber,
  postcode,
  building_id::text,
  ST_Y(geom) AS lat,
  ST_X(geom) AS lon,
  trim(street || ' ' || housenumber) AS label
FROM address
WHERE street IS NOT NULL AND trim(street) <> ''
  AND housenumber IS NOT NULL AND trim(housenumber::text) <> '';
```

**Organizations**

```sql
SELECT
  'org:' || o.source || ':' || o.source_id AS id,
  'organization' AS kind,
  o.source,
  o.name,
  c.slug AS category_slug,
  c.name_sr AS category_name,
  o.tags,
  o.building_id::text,
  ST_Y(o.geom) AS lat,
  ST_X(o.geom) AS lon,
  o.name AS label
FROM organization o
JOIN category c ON c.id = o.category_id
WHERE o.name IS NOT NULL AND trim(o.name) <> '';
```

После батча — `POST /indexes/zylos/documents` (Meilisearch bulk). В конце — optional `POST /indexes/zylos/settings` если settings менялись.

Полная переиндексация: `DELETE /indexes/zylos/documents` или swap index `zylos_tmp` → `zylos` — главное, чтобы не было окна с пустым индексом дольше 30 с в dev.

---

## 6. Структура файлов

```
apps/api/
  package.json
  tsconfig.json
  Dockerfile              ← multi-stage, node 20 alpine
  src/
    index.ts              ← Fastify bootstrap
    config.ts             ← env
    routes/
      health.ts
      search.ts
    services/
      meilisearch.ts
    types/
      search.ts
infra/meilisearch/
  index-settings.json
scripts/
  index-meilisearch.mjs   ← или .ts через tsx; читает pg, пишет meili
  stage03-verify.ps1
  stage03-verify.sh
  stage03-benchmark.mjs
  pipeline-stage03.ps1
  pipeline-stage03.sh
docker-compose.yml        ← + meilisearch, api
.env.example              ← + MEILI_MASTER_KEY, API_PORT
```

`.gitignore`: не добавлять `meili_data` в git; volume `meili_data` в compose.

---

## 7. Docker Compose (добавить)

Не менять имена существующих сервисов. Добавить:

**meilisearch**

- image: `getmeili/meilisearch:v1.11` (или актуальный v1.x из доков)
- env: `MEILI_MASTER_KEY`, `MEILI_ENV=development`
- volume: `meili_data`
- ports: `127.0.0.1:7700:7700`
- healthcheck: `GET /health`

**api**

- build: `./apps/api`
- depends_on: `postgis` healthy, `meilisearch` healthy
- env: `DATABASE_URL`, `MEILI_URL`, `MEILI_MASTER_KEY`, `PORT=3000`
- ports: `127.0.0.1:3000:3000`
- healthcheck: `GET /v1/health`

`postgis` и `tiles` — без изменений ролей.

---

## 8. Пайплайн (порядок)

1. `docker compose up -d postgis meilisearch api`
2. Дождаться healthchecks
3. Применить settings индекса (если ещё нет)
4. `node scripts/index-meilisearch.mjs` — полная переиндексация
5. `scripts/stage03-verify.ps1` — M1–M6, F1–F7, health, RSS
6. `node scripts/stage03-benchmark.mjs` — S1, S4
7. Дописать в `data/README.md`: document counts, p95, дата index

Не запускать `pipeline-stage02` и не пересобирать PMTiles.

Порядок при **повторном импорте этапа 2**: сначала зелёный stage02, затем `pipeline-stage03`.

---

## 9. `stage03-verify` — обязательные проверки

Скрипт на PowerShell/bash + curl + опционально `node -e` для Meilisearch stats API.

Минимум ассертов:

```text
assert meilisearch_health == ok
assert api_health.meilisearch == ok
assert index_stats.address >= 22000
assert index_stats.organization >= 1500
assert search(q=apotek).hits >= 5
assert search(q=a).hits == 0 && time_ms <= 20
assert search(q=apotek).limit_max enforced
assert docker_stats.meilisearch_mem <= 300MB
-- benchmark: p95 search(apotek) <= 120ms (30 samples, warmup 5)
```

Любой провал → exit **1**. В конце — таблица метрик для README.

---

## 10. Типичные поломки

| Симптом | Что делать |
|---|---|
| 0 documents | Index script смотрит не ту БД / не тот host (`postgis` vs `localhost`) |
| M1 OK, F3 fail | Не в searchable `street_sr_cyrl`; проверить settings |
| Все org `other` | Не JOIN category; seed категорий не применён |
| p95 800 ms | Индекс не прогрет; limit 500; Meilisearch на HDD — перенести volume на SSD |
| 401 Meilisearch | Забыли `Authorization: Bearer $MEILI_MASTER_KEY` в index script |
| Дубли в выдаче | Два address OSM+RGZ — **норма** на v1; dedup canonical — этап 5 UI |
| API CORS fail на этап 4 | Добавить origin Vite в конфиг заранее |
| RSS > 300 MB | Индексируют `geom` WKT или лишние поля — урезать документ |
| `q=12` тысячи hits | Требовать `kind=address` + geo или min word length в API для чистых цифр |

---

## 11. Как давать задачу AI

Сначала **Opus 5** (схема документа + JSON ответа + пороги verify). Потом **Composer 2.5** (api + index script + compose). Docker — **GPT-5.6 Sol**.

Промпт:

```
Сделай Этап 3 строго по Docs/STAGE-03-meilisearch.md.

Вход: зелёный stage02-verify, PostGIS с address/organization, docker compose.
Не пиши React/MapLibre, не добавляй /orgs и /buildings, не поднимай Valhalla/OTP/Photon, не пересобирай PMTiles, не меняй schema.sql этапа 2.

Нужны: meilisearch + api в docker-compose, apps/api с GET /v1/health и GET /v1/search, scripts/index-meilisearch.mjs, infra/meilisearch/index-settings.json, stage03-verify и pipeline-stage03 с порогами M1–M6, F1–F7, S1/S4/S6.

Запусти index и verify. В конце выведи counts, p95 search и результат F7 по fixtures.sql.
```

Ревью контракта `/search` — Opus, не той сессией, что писала route handler.

---

## 12. Оценка времени

| Работа | Ориентир |
|---|---|
| Compose + Meilisearch | 30–60 мин |
| Index script + settings | 1–2 часа |
| Fastify `/search` + health | 1–2 часа |
| Verify + benchmark | 1 час |
| Отладка кириллицы / geo sort | 30–60 мин |

---

## 13. Выход в этап 4

Этап 4 (веб-карта MapLibre) стартует только при зелёном stage03.

Клиент этапа 4:

- подключает PMTiles с `tiles` (как превью этапа 1);
- поле поиска → debounce 150 ms → `GET /v1/search`;
- tap по hit → flyTo + pin (карточка `/orgs/:id` — когда будет этап 5).

Индекс **не** дублировать во фронте. После editorial (этап 6) — reindex одного документа или полный `index-meilisearch`.

Пороги этапа 4 из [PERFORMANCE.md](PERFORMANCE.md): L1–L4, T1, T2, T4, U1–U3.
