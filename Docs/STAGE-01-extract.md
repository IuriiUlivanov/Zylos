# Этап 1. Extract OSM Нови-Сада, PMTiles, каркас Docker

Связан с [PLAN.md](PLAN.md). Автопроверки карты: [STAGE-01-map-verify.md](STAGE-01-map-verify.md). Это **единственный** этап, который нужно закрыть, прежде чем писать поиск, API и UI приложения.

Цель этапа: на локальной машине есть обрезанный OSM Grad Novi Sad, из него собраны векторные тайлы, они открываются в браузере, на карте видны **улицы, здания и номера домов**. Рядом в Docker крутится пустой PostGIS — готов к этапу 2, но данные в него ещё не льём.

Модель для реализации: **GPT-5.6 Sol** поднимает пайплайн и Docker. **Composer 2.5** подчищает скрипты. Карту в браузере смотрит человек. **Не** открывать этот этап на Opus «написать всё приложение».

---

## 1. Зачем этот этап

Без своего extract все следующие агенты полезут в публичный Overpass и Geofabrik на каждый чих. Это медленно, ломается по rate limit и даёт разные границы города.

После этапа 1 в репозитории появляется **один канонический кусок Нови-Сада**. Его же потом едят PostGIS, Valhalla и OTP.

---

## 2. Что входит и что нет

### Входит

- Каркас репозитория и `.gitignore`
- Граница Grad Novi Sad (OSM relation **1649672**)
- Скачивание `serbia-latest.osm.pbf` с Geofabrik
- Обрезка osmium → `novi-sad.osm.pbf`
- Сборка `novi-sad.pmtiles` через Planetiler (схема OpenMapTiles)
- `docker compose`: PostGIS 16 + Caddy, который отдаёт тайлы и превью
- Статическая страница превью MapLibre: здания и housenumber, атрибуция ODbL
- Скрипты, которые можно прогнать повторно (идемпотентно, с `-Force` на пересборку)

### Не входит (следующие этапы)

- Импорт OSM/RGZ в PostGIS
- Meilisearch, Fastify, React-приложение «как 2ГИС»
- Valhalla, OpenTripPlanner, GTFS
- Capacitor, аккаунты, админка
- Красивый basemap на весь город как продукт — только **диагностический** стиль, чтобы доказать, что в тайлах есть нужные слои
- Скрейп сайтов, Google Maps, публичный Overpass в runtime

---

## 3. Критерий готовности (Definition of Done)

Этап закрыт, только если выполнено **всё**:

1. Файл `data/osm/novi-sad.osm.pbf` существует, размер ориентировочно десятки МБ (не весь сербский extract ~228 MB).
2. `osmium fileinfo data/osm/novi-sad.osm.pbf` показывает bounding box внутри Воеводины (примерно lon 19.5–20.2, lat 45.1–45.5).
3. Файл `data/tiles/novi-sad.pmtiles` существует. `pmtiles show` / Planetiler log: maxzoom ≥ 14, есть слои `building`, `housenumber`, `transportation`.
4. `docker compose up -d` поднимает `postgis` (healthy) и `tiles`.
5. `GET http://localhost:8080/tiles/novi-sad.pmtiles` с заголовком `Range: bytes=0-16383` отвечает **206** и `Content-Range`. Без Range PMTiles в браузере не заведётся.
6. `http://localhost:8080/` открывает карту. Центр: **45.255, 19.845** (центр Нови-Сада). На зуме **14–16** видны контуры зданий; на **15–16** — номера домов там, где они есть в OSM.
7. На карте есть текст: `© OpenStreetMap contributors`.
8. Повторный запуск скрипта без флага пересборки не качает Geofabrik заново, если PBF уже на диске.
9. В `data/README.md` записаны дата extract, URL источника, relation id, размеры файлов.
10. `scripts/stage01-verify.ps1` (или `.sh`) завершается с кодом **0**: HTTP Range 206, здания в центре / Петроварадине / Лимане, Дунай залит полигоном `water-fill`.

Если здания не видны — этап **не** закрыт, даже если compose зелёный. Красный verify = этап открыт.

---

## 4. Зафиксированные константы

| Параметр | Значение |
|---|---|
| Город | Grad Novi Sad |
| OSM relation | `1649672` ([openstreetmap.org/relation/1649672](https://www.openstreetmap.org/relation/1649672)) |
| Не путать с | relation `9273976` (населённый пункт Novi Sad без пригородов) |
| Источник OSM | `https://download.geofabrik.de/europe/serbia-latest.osm.pbf` |
| Лицензия карты | ODbL, атрибуция обязательна |
| Схема тайлов | OpenMapTiles (дефолтный профиль Planetiler) |
| Формат тайлов | PMTiles v3 |
| Maxzoom генерации | 14 (MapLibre overzoom до 16–18) |
| Центр превью | `[19.845, 45.255]`, zoom 14 |
| Порт тайлов/превью | `8080` |
| PostGIS | `16-3.5`, БД `zylos`, порт `5432` |
| Координаты | хранить lon/lat WGS84; в PostGIS позже `EPSG:4326` / `geography` |

Почему relation 1649672, а не bbox: административная граница включает Петроварадин и Сремску-Каменицу. Прямоугольник обрежет Дунай криво или захватит чужие сёла.

---

## 5. Целевая структура репозитория после этапа

```
Zylos/
  Docs/
    PLAN.md
    STAGE-01-extract.md        ← этот файл
    STAGE-01-map-verify.md     ← что проверяет stage01-verify
  .gitignore
  .env.example
  docker-compose.yml
  data/
    README.md
    boundary/
      grad-novi-sad.geojson    ← коммитить (маленький)
    osm/                       ← gitignore *.pbf
    tiles/                     ← gitignore *.pmtiles
  infra/
    caddy/
      Caddyfile
    preview/
      index.html
  scripts/
    fetch-boundary.sh          # и .ps1-обёртка
    download-serbia.sh
    extract-novi-sad.sh
    build-pmtiles.sh
    pipeline.sh                # всё по порядку
    pipeline.ps1               # то же для Windows
    stage01-verify.ps1         # HTTP + тайлы + рендер MapLibre
    stage01-verify.sh
    map-verify/                # node: verify.mjs
```

На Windows скрипты `.sh` гонять **внутри Docker** или Git Bash. Для оператора — `pipeline.ps1`, который только вызывает `docker run` / `docker compose`. Не требовать установленный локально osmium или Java.

---

## 6. `.gitignore` (обязательные строки)

```
.env
data/osm/*.pbf
data/osm/*.pbf.md5
data/tiles/*.pmtiles
data/planetiler-tmp/
data/tmp/
*.log
```

Коммитить: `data/boundary/grad-novi-sad.geojson`, `data/README.md`, скрипты, compose, превью.

Не коммитить сербский PBF и PMTiles — они тяжёлые и пересобираемые.

---

## 7. Пошаговый пайплайн

Все `docker run` монтируют `./data` в `/data`. Каталоги `data/osm`, `data/tiles`, `data/boundary`, `data/tmp` создать заранее.

### Шаг 7.1 — граница города

Сначала из уже скачанного Serbia PBF (предпочтительно, без Overpass):

```bash
docker run --rm -v "${PWD}/data:/data" iboates/osmium:latest \
  getid --overwrite -r -t \
  /data/osm/serbia-latest.osm.pbf r1649672 \
  -o /data/tmp/grad-novi-sad.osm.pbf

docker run --rm -v "${PWD}/data:/data" iboates/osmium:latest \
  export --overwrite --geometry-types=polygon \
  /data/tmp/grad-novi-sad.osm.pbf \
  -o /data/boundary/grad-novi-sad.geojson
```

Если PBF ещё нет — сначала шаг 7.2, потом вернуться.

Проверка GeoJSON:

- `type` FeatureCollection или Feature, geometry Polygon/MultiPolygon
- координаты в Воеводине
- файл коммитится в git

Если osmium export выдал линии вместо полигона — взять готовый контур:

`https://polygons.openstreetmap.fr/get_geojson.py?id=1649672&params=0`

Сохранить как `data/boundary/grad-novi-sad.geojson`. Это запасной путь, не runtime-зависимость приложения.

### Шаг 7.2 — скачать Сербию

```bash
# класть в data/osm/serbia-latest.osm.pbf
curl -L --continue-at - \
  -o data/osm/serbia-latest.osm.pbf \
  https://download.geofabrik.de/europe/serbia-latest.osm.pbf
```

На Windows в `pipeline.ps1`: `Invoke-WebRequest` с `-Resume` или `curl.exe`. Если файл уже есть и размер > 100 MB — пропуск.

Сверять `.md5` с той же папки Geofabrik: `https://download.geofabrik.de/europe/serbia-latest.osm.pbf.md5`.

Ориентир: ~200–250 MB, качается минуты.

### Шаг 7.3 — обрезать Нови-Сад

```bash
docker run --rm -v "${PWD}/data:/data" iboates/osmium:latest \
  extract --overwrite \
  --strategy=complete_ways \
  --polygon=/data/boundary/grad-novi-sad.geojson \
  /data/osm/serbia-latest.osm.pbf \
  -o /data/osm/novi-sad.osm.pbf
```

Стратегия `complete_ways`: в extract попадают полные way, даже если часть узлов снаружи полигона. Это нужно, чтобы здания и дороги на границе не рвались.

Если после сборки тайлов нет больших зданий-мультиполигонов — пересобрать с `--strategy=smart` (медленнее).

Проверка:

```bash
docker run --rm -v "${PWD}/data:/data" iboates/osmium:latest \
  fileinfo -e /data/osm/novi-sad.osm.pbf
```

Записать в `data/README.md`: число nodes/ways/relations, bbox, дата.

### Шаг 7.4 — PMTiles

Образ: `ghcr.io/onthegomap/planetiler:latest`.

```bash
docker run --rm \
  -e JAVA_TOOL_OPTIONS="-Xmx4g" \
  -v "${PWD}/data:/data" \
  ghcr.io/onthegomap/planetiler:latest \
  --osm-path=/data/osm/novi-sad.osm.pbf \
  --output=/data/tiles/novi-sad.pmtiles \
  --download \
  --maxzoom=14 \
  --force
```

`--download` тянет Natural Earth / lake shapefiles для воды и стран — один раз в кэш образа/volume. Для города это нормально (Дунай должен быть на карте).

Для Нови-Сада 4 GB heap достаточно. Если контейнер убит OOM — `-Xmx8g`.

Проверка архива:

```bash
docker run --rm -v "${PWD}/data:/data" protomaps/go-pmtiles:latest \
  show /data/tiles/novi-sad.pmtiles
```

В метаданных / JSON слоёв должны быть как минимум:

- `building`
- `housenumber`
- `transportation`
- `water` или `waterway`
- `poi` (пригодится на этапе 2, сейчас не рисуем обязательно)

Имена слоёв — **OpenMapTiles**, не Protomaps basemaps. Превью должно использовать эти имена. Не подключать стиль Protomaps Light к OpenMapTiles-тайлам: слои не совпадут, здания «пропадут».

### Шаг 7.5 — Docker Compose

`docker-compose.yml`:

**postgis**

- образ `postgis/postgis:16-3.5`
- env из `.env`: `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB=zylos`
- volume `pgdata`
- порт `127.0.0.1:5432:5432` (не торчать в LAN без нужды)
- healthcheck: `pg_isready -U zylos`
- команда инициализации: `CREATE EXTENSION IF NOT EXISTS postgis;` (через docker-entrypoint-initdb.d)

На этом этапе таблиц приложения нет. Проверка: `docker compose exec postgis psql -U zylos -c "SELECT PostGIS_Version();"`

**tiles** (Caddy)

- образ `caddy:2-alpine`
- volume `./infra/caddy/Caddyfile` и `./infra/preview` + `./data/tiles`
- порт `8080:80`

Caddyfile (идея):

```
:80 {
  header {
    Access-Control-Allow-Origin *
    Access-Control-Expose-Headers Content-Range, Accept-Ranges, Content-Length, ETag
    Access-Control-Allow-Headers Range, If-Match
  }

  handle /tiles/* {
    root * /srv/tiles
    uri strip_prefix /tiles
    file_server
  }

  handle {
    root * /srv/preview
    file_server
  }
}
```

Caddy `file_server` отдаёт Range для статики сам. Отдельный `pmtiles_proxy` на этапе 1 не нужен: браузер читает один файл через протокол `pmtiles://`.

`.env.example`:

```
POSTGRES_USER=zylos
POSTGRES_PASSWORD=zylos
POSTGRES_DB=zylos
```

Пароль в git не коммитить (`.env`).

### Шаг 7.6 — превью карты

`infra/preview/index.html` — один HTML, без сборщика.

Зависимости с CDN (для этапа 1 допустимо):

- `maplibre-gl` (css + js)
- `pmtiles` (js, Protocol)

Логика:

1. `maplibregl.addProtocol('pmtiles', protocol.tile)`
2. Источник `pmtiles://http://localhost:8080/tiles/novi-sad.pmtiles`
3. Диагностические слои (не продуктовый стиль):

| id | source-layer | type | зачем |
|---|---|---|---|
| water | `water` | fill | Дунай, каналы |
| park | `landuse` / `park` / `landcover` | fill | контроль покрытия |
| roads | `transportation` | line | улицы |
| buildings | `building` | fill | главный критерий |
| housenumber | `housenumber` | symbol `text-field: ["get", "housenumber"]` | адреса OSM |
| poi-dot | `poi` | circle, только zoom ≥ 15 | опционально |

4. Центр `[19.845, 45.255]`, zoom 14, minZoom 10, maxZoom 18
5. Атрибуция: `© OpenStreetMap contributors`
6. На странице короткий текст: «Этап 1 — диагностика тайлов, не продукт»

Если слой `building` пустой на z14 — смотреть `minzoom` в тайлах. OpenMapTiles обычно отдаёт здания с z13–14. Не искать их на z11.

Не подключать Mapbox access token. Не грузить Google.

---

## 8. Как проверять глазами

Открыть `http://localhost:8080/` и пройти точки:

| Место | Координаты (lon, lat) | Что должно быть |
|---|---|---|
| Центр, Трг слободе | 19.845, 45.255 | плотная застройка, улицы |
| Петроварадинская крепость | 19.862, 45.252 | холм, контуры крепости/зданий |
| Лиман | 19.840, 45.238 | жилые кварталы |
| Мост через Дунай | 19.851, 45.261 | река + мост как transportation |

На каждой точке: zoom 15, здания не «каша линий», а полигоны.

Если крепость или Лиман пустые — extract обрезан не той relation (часто путают 9273976).

Автоматически те же точки проверяет `scripts/stage01-verify.ps1` после `docker compose up`. Список проверок и порогов: [STAGE-01-map-verify.md](STAGE-01-map-verify.md). Дунай на мосту (19.851, 45.261) — это палуба, не вода; заливка проверяется в русле (19.860, 45.260) и слоем `water-fill`.

---

## 9. `data/README.md` — шаблон

Заполнить скриптом в конце пайплайна:

```
# Extract Novi Sad

- OSM source: https://download.geofabrik.de/europe/serbia-latest.osm.pbf
- Downloaded at: <ISO-8601>
- Boundary: OSM relation 1649672 (Grad Novi Sad)
- License: ODbL, © OpenStreetMap contributors
- serbia-latest.osm.pbf: <bytes>
- novi-sad.osm.pbf: <bytes>
- novi-sad.pmtiles: <bytes>
- osmium fileinfo bbox: <...>
- planetiler maxzoom: 14
```

---

## 10. Типичные поломки

| Симптом | Что проверить |
|---|---|
| Качается вечно / 403 | URL Geofabrik, диск, антивирус; докачка `--continue-at -` |
| `novi-sad.osm.pbf` почти как Serbia | Не тот polygon или extract без `--polygon` |
| Нет Петроварадина | Взяли relation 9273976 вместо 1649672 |
| Planetiler OOM | `-Xmx4g` → 8g, закрыть другие контейнеры |
| Карта серая, в консоли CORS | Заголовки Caddy, превью и тайлы с одного origin `localhost:8080` |
| `Failed to fetch` / не грузятся тайлы | Нет HTTP 206 на Range; открыли html как `file://` |
| Зданий нет, дороги есть | Неверный `source-layer` (стиль Protomaps на тайлах OpenMapTiles) или zoom < 13 |
| Номеров домов нет | В OSM их мало на квартале; проверить другой квартал в центре; слой `housenumber` включается на высоком зуме |
| PostGIS unhealthy | пароль/volume с предыдущего запуска; `docker compose down -v` только если данные не жалко (на этапе 1 жалеть нечего) |
| Docker Desktop Windows: volume empty | монтировать `${PWD}` / полный путь `C:\Documents\Navigator\data`, не относительный из другой drive |

---

## 11. Как давать задачу AI

Режим: **Agent**. Модель: **GPT-5.6 Sol**.

Промпт (вставить как есть):

```
Сделай Этап 1 строго по Docs/STAGE-01-extract.md. Не пиши React-приложение, API, Valhalla, OTP, импорт в PostGIS.

Нужно:
- каркас репо, gitignore, .env.example
- скрипты пайплайна (sh + pipeline.ps1) через Docker: osmium, planetiler
- граница relation 1649672
- docker-compose: postgis 16 + caddy с Range и превью MapLibre
- диагностическая карта: слои OpenMapTiles building, housenumber, transportation, water
- атрибуция ODbL

Не используй Google Maps, Mapbox token, Overpass в runtime.
Когда скрипты готовы — запусти пайплайн, если сеть и Docker доступны.
Остановись и напиши, что проверить в браузере на localhost:8080.
```

После зелёного compose: глазами карта. Потом **Composer 2.5** — только если надо подчистить пути Windows/Linux.

Не просить Grok на этом этапе «сделать UI как 2ГИС»: он начнёт продукт раньше данных.

---

## 12. Оценка времени

| Работа | Ориентир |
|---|---|
| Скрипты + compose + превью | 1–2 часа агента |
| Качание Serbia PBF | 2–15 минут |
| osmium extract города | 1–5 минут |
| Planetiler | 2–10 минут на город |
| Ручная проверка карты | 10 минут |

Узкое место — первый download Geofabrik и Docker-образы (planetiler, osmium, postgis).

---

## 13. Выход в этап 2

Когда DoD закрыт, следующий этап: [STAGE-02-postgis.md](STAGE-02-postgis.md).

- те же `novi-sad.osm.pbf` и граница
- импорт зданий, адресов OSM, POI в уже живущий PostGIS
- RGZ Адресни регистар
- привязка точка-в-полигоне, `scripts/stage02-verify.sql`

Этап 2 **не** пересобирает PMTiles без нужды и **не** меняет relation id.

---

## 14. Юридическая пометка

Производные от OSM (extract, PMTiles) — под ODbL. В превью и позже в приложении должна остаться атрибуция. Не выкладывать extract как «свои данные без OSM».
