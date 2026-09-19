# Этап 1. Что тестирует map verify

Связан с [STAGE-01-extract.md](STAGE-01-extract.md). Это не продуктовые тесты UI 2ГИС и не SQL этапа 2.

Скрипт проверяет **диагностическую карту** на `http://localhost:8080/`: тайлы отдаются, в них есть здания/улицы/номера, Дунай залит полигоном, MapLibre это рисует.

Красный verify = этап 1 не закрыт, даже если `docker compose` зелёный.

---

## 1. Как запустить

Нужны: Docker-сервисы `tiles` + `tile-api` (порт 8080), Node.js, Chrome или Edge.

```powershell
scripts/stage01-verify.ps1
```

```sh
scripts/stage01-verify.sh
```

Или напрямую: `node scripts/map-verify/verify.mjs`.

Пайплайн этапа 1 (`pipeline.ps1` / `pipeline.sh`) вызывает verify сам после `docker compose up`.

Переменные:

| Переменная | Зачем |
|---|---|
| `NAVIGATOR_PREVIEW_URL` | Базовый URL, по умолчанию `http://127.0.0.1:8080` |
| `BROWSER_PATH` | Путь к Chrome/Edge, если автопоиск не нашёл браузер |

Код **0** — все проверки зелёные. Код **≠ 0** — в конце список провалов; остальные всё равно печатаются.

---

## 2. Что входит и что нет

### Входит

- Файл `data/tiles/novi-sad.pmtiles` и HTTP Range
- Векторные тайлы `/mvt/novi-sad/{z}/{x}/{y}.mvt`
- Стиль `infra/preview/style.json` и страница превью
- Заливка Дуная `infra/preview/water-fill.geojson`
- Рендер MapLibre в headless Chrome/Edge: здания, дороги, вода, номера домов, атрибуция OSM

### Не входит

- PostGIS, RGZ, организации (`scripts/stage02-verify.sql`)
- Поиск, API, клик по зданию, маршруты
- Pixel-diff скриншотов и FPS (бюджеты T1–T4 из [PERFORMANCE.md](PERFORMANCE.md) — позже)
- Слой `water` в MVT на тайле моста: в OpenMapTiles его там нет, это **notice**, не fail. Заливку закрывает `water-fill`

---

## 3. Фикстуры (точки на карте)

Координаты — WGS84 lon/lat. Зум тайлов для MVT всегда **14** (maxzoom архива).

| ID | Место | lon, lat | Зачем |
|---|---|---|---|
| center | Трг слободе | 19.845, 45.255 | Плотная застройка и номера домов |
| petrovaradin | Крепость, правый берег | 19.866, 45.252 | Не та relation: `9273976` оставляет крепость пустой. Чуть восточнее точки из STAGE-01 (19.862), чтобы z14-тайл не совпал с центром |
| liman | Лиман | 19.840, 45.238 | Жилые кварталы, та же проверка границы |
| danube | Русло Дуная | 19.860, 45.260 | Полигон воды. **Не** точка моста |
| bridge | Мост (только дороги) | 19.851, 45.261 | Палуба моста — не вода. Проверяются линии `transportation` |

---

## 4. Проверки

Любой провал — `FAIL` и ненулевой exit. Пороги — минимумы; факт на зелёном прогоне выше.

### 4.1. HTTP и артефакты

| ID | Что | Порог |
|---|---|---|
| H5-size | `data/tiles/novi-sad.pmtiles` | файл есть, **1–15 MB** |
| H1-range | `GET /tiles/novi-sad.pmtiles` + `Range: bytes=0-16383` | **206** и `Content-Range: bytes 0-16383/…` |
| H3-preview | `GET /` | **200**, тело > 500 байт |
| H3-style | `GET /style.json` | **200**, MapLibre style v8 |
| H3-layer-buildings / roads / water-fill / housenumber | слои в style.json | все четыре есть |
| H3-attribution | `sources.noviSad.attribution` | содержит `OpenStreetMap` |
| H5-maxzoom | `sources.noviSad.maxzoom` | **≥ 14** |
| H4-water-fill | `GET /water-fill.geojson` | **200**, ≥ 1 feature |

### 4.2. Содержимое тайлов (MVT)

Декодируется gzip-MVT. Считаются полигоны `building`, линии `transportation`, точки `housenumber`.

| ID | Тайл / точка | Порог |
|---|---|---|
| C-center-http | z14 над центром | HTTP **200**, тело не пустое |
| C-center-building | то же | **≥ 15** полигонов зданий |
| C-center-roads | то же | **≥ 50** линий |
| C-center-housenumber | то же | **≥ 20** номеров |
| C-petrovaradin-http / -building / -roads | z14 над крепостью | HTTP 200; зданий **≥ 3**; дорог **≥ 20** |
| C-liman-http / -building / -roads | z14 над Лиманом | HTTP 200; зданий **≥ 15**; дорог **≥ 30** |
| C-bridge-roads | z14 над мостом 19.851, 45.261 | **≥ 20** линий `transportation` |
| C-danube-fill | точка 19.860, 45.260 | лежит **внутри** полигона `water-fill.geojson` |
| C-danube-extent | все фичи water-fill | ширина bbox **≥ 0.1°** (Дунай через город, не лужа) |

Пустой Петроварадин или Лиман на этих порогах почти всегда значит extract по relation **9273976** вместо **1649672**.

### 4.3. Рендер MapLibre

Headless Chrome/Edge открывает превью, `jumpTo` по фикстурам, считает `queryRenderedFeatures`. Нужен браузер (иначе R0 красный).

| ID | Вид | Порог |
|---|---|---|
| R0-browser | найден Chrome/Edge | путь есть |
| R-center-buildings | z15 центр | слой `buildings` **> 0** |
| R-center-roads | z15 центр | слой `roads` **> 0** |
| R-attribution | контрол карты | текст содержит `OpenStreetMap` |
| R-petrovaradin-buildings | z15 крепость | `buildings` **> 0** |
| R-liman-buildings | z15 Лиман | `buildings` **> 0** |
| R-danube-fill | z14 русло | `water-fill` или `water` **> 0** |
| R-housenumber-source | z16 центр | в источнике тайлов housenumber **> 0** (rendered может быть меньше из‑за коллизий подписей) |
| R-no-fatal-error | ошибка MapLibre | fail только если есть ошибка **и** здания в центре не нарисовались (обрыв glyphs CDN сам по себе этап не валит) |

---

## 5. Файлы

```
scripts/stage01-verify.ps1      ← Windows
scripts/stage01-verify.sh       ← Linux / Git Bash
scripts/map-verify/verify.mjs   ← сами проверки
scripts/map-verify/package.json
infra/preview/index.html        ← window.__zylosMap для рендера
infra/preview/style.json
infra/preview/water-fill.geojson
```

`node_modules` в git не кладётся. Первый запуск обёрток делает `npm ci`.

---

## 6. Типичные поломки

| Симптом | Что сломано |
|---|---|
| H1-range не 206 | Caddy / file_server, открыли html как `file://` |
| H3-layer-* missing | не тот style.json или стиль Protomaps на тайлах OpenMapTiles |
| C-petrovaradin-building / R-petrovaradin пустые | relation 9273976, нет правого берега |
| C-danube-fill fail | нет или обрезан `water-fill.geojson`; Дунай снова только `waterway` |
| R0-browser fail | нет Chrome/Edge; задать `BROWSER_PATH` |
| R-center-buildings = 0 при живых MVT | слой `buildings` не в стиле, неверный `source-layer`, зум < 13 |

Этап 2 (`stage02-verify.sql`) эту карту не проверяет: там здания и организации в PostGIS, не в PMTiles.
