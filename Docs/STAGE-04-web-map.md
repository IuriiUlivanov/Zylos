# Этап 4. Веб-карта MapLibre: тайлы, поиск, POI, bottom sheet

Связан с [PLAN.md](PLAN.md), [STAGE-03-meilisearch.md](STAGE-03-meilisearch.md), [ARCHITECTURE.md](ARCHITECTURE.md), [PERFORMANCE.md](PERFORMANCE.md), [MOBILE.md](mobile/MOBILE.md).

Цель: появляется **`apps/web`** — React + MapLibre + Vite. Карта на весь экран, поле поиска сверху, подсказки из `GET /v1/search`, выбор результата → flyTo + маркер + **bottom sheet** с краткой карточкой. Клик по POI из тайлов (слой `poi`) открывает такой же sheet с данными из MVT. Продуктовый экран «как 2ГИС» на минимальном уровне, без клика по зданию и без маршрутов.

Вход этапа 3, без которого не начинать:

- `scripts/stage03-verify.ps1` (или `.sh`) завершился с кодом **0**
- `docker compose` → `postgis`, `meilisearch`, `api`, `tiles` running
- `GET /v1/search?q=apotek` возвращает ≥ 5 hits
- `data/tiles/novi-sad.pmtiles` и превью этапа 1 на `:8080` работают

Этап **не** добавляет эндпоинты `/orgs/:id`, `/buildings/at`, `/buildings/:id`, **не** поднимает Valhalla/OTP/Photon, **не** пересобирает PMTiles и **не** трогает SQL PostGIS.

Модели: **Grok 4.6** — каркас UI, MapLibre, bottom sheet «как 2ГИС». **Composer 2.5** — Vite, хуки поиска, verify-скрипт, мелкие итерации. **Opus 5 Thinking** — если путаются слои стиля / hit-test POI. **GPT-5.6 Sol** — если CORS, proxy Vite или compose для web. Замеры T1/T2/L2 — человек в Chrome DevTools или `stage04-benchmark.mjs`.

---

## 1. Зачем этот этап

Этапы 1–3 доказали данные и поиск в curl. Пользователь 2ГИС видит **карту и строку поиска**, а не JSON.

Без этого этапа нельзя проверить end-to-end SLO поиска (S1 с RTT), бюджеты карты (T1, T2, T4) и отзывчивость UI (U1–U3). Клик по зданию (этап 5) и полная карточка организации из PostGIS строятся поверх уже работающей карты и sheet.

---

## 2. Что входит и что нет

### Входит

- Приложение `apps/web`: React 18, TypeScript, Vite, MapLibre GL JS
- Полноэкранная карта с тем же стилем, что `infra/preview/style.json` (OpenMapTiles-слои, заливка Дуная `water-fill.geojson`)
- Подключение тайлов с сервиса `tiles` (`:8080`): MVT `/mvt/novi-sad/{z}/{x}/{y}.mvt`, не прокси через API
- Строка поиска сверху (safe area), debounce **150 ms** → `GET /v1/search`
- Выпадающий список подсказок; отмена устаревших ответов (S3)
- Выбор hit → `flyTo`, маркер, bottom sheet: `label`, `kind`, для org — `category_slug` / `category_name`
- Клик по точке POI на карте (слой `poi` / `poi-dot`) → sheet с `name` из properties тайла
- Состояние URL: `?q=`, `?sel=` (id hit из Meilisearch) для шаринга и «назад»
- Центр по умолчанию **19.845, 45.255** (Трг слободе), zoom **14**
- Минимальные дизайн-токены `apps/web/src/theme/tokens.css` (цвета, радиусы, высота sheet peek)
- Атрибуция OSM на карте (из стиля)
- Скрипты `scripts/stage04-verify.ps1` / `.sh` и `scripts/stage04-benchmark.mjs`
- Пайплайн `scripts/pipeline-stage04.ps1` / `.sh`: build → verify → benchmark
- Переменные в `.env.example`: `VITE_API_URL`, `VITE_TILES_URL`
- CORS в `api` уже разрешает `http://localhost:5173` (из этапа 3; если нет — добавить)

### Не входит

- `GET /v1/orgs/:id`, `GET /v1/buildings/at`, `GET /v1/buildings/:id` — **этап 5**
- Полная карточка: телефоны, часы, сайт, список организаций в здании — этап 5
- Подсветка контура здания GeoJSON из PostGIS — этап 5
- `GET /v1/orgs` bbox-пины всех POI города — этап 5 (на этапе 4 только маркер выбранного hit)
- Photon fallback, маршруты, Valhalla, OTP, GTFS
- Capacitor, PWA manifest, офлайн-тайлы
- Админка editorial
- Тёмная тема, i18n ru/en (достаточно sr-Latn в UI v1)
- `packages/shared` — типы search-hit дублировать локально в `apps/web/src/types/search.ts` до OpenAPI
- Pixel-diff «как 2ГИС» — только функциональные пороги и ручная приёмка «похоже ли»
- Google Maps / Mapbox SDK

---

## 3. Критерии выполнения (Definition of Done)

Этап закрыт, только если выполнены **все** пункты. «Карта открылась в браузере» недостаточно. `scripts/stage04-verify` должен завершаться с кодом **0**.

### 3.1. Предусловия

| # | Критерий | Как проверить |
|---|---|---|
| P1 | Этап 3 закрыт | `pipeline-stage03` → exit 0 |
| P2 | Сервисы up | `docker compose ps` → postgis, meilisearch, api, tiles running |
| P3 | Тайлы живы | `curl -I -H "Range: bytes=0-16383" http://127.0.0.1:8080/tiles/novi-sad.pmtiles` → **206** |
| P4 | API search | `curl "http://127.0.0.1:3000/v1/search?q=apotek"` → ≥ 5 hits |

### 3.2. Сборка и dev-сервер

| # | Критерий | Как проверить |
|---|---|---|
| W1 | `npm run build` в `apps/web` | exit 0, `dist/index.html` существует |
| W2 | `npm run dev` | Vite на `http://127.0.0.1:5173`, карта без fatal MapLibre error |
| W3 | Env | `VITE_API_URL`, `VITE_TILES_URL` документированы в `.env.example` |
| W4 | Нет секретов во фронте | В `dist` нет `MEILI_MASTER_KEY`, `DATABASE_URL` |

### 3.3. Карта (тайлы)

| # | Критерий | Порог | ID |
|---|---|---|---|
| M1 | Здания в центре после load | `queryRenderedFeatures` слой `buildings` > 0 на z15 | — |
| M2 | Дороги видны | слой `roads` > 0 | — |
| M3 | Атрибуция OSM | контрол карты содержит `OpenStreetMap` | — |
| M4 | Дунай | z14 над 19.860, 45.260 — `water-fill` или `water` > 0 | — |
| M5 | Стиль | источник `noviSad`, maxzoom **14**; не Protomaps Basemaps | — |
| M6 | Range | Network: запросы MVT/PMTiles с **206** или 200+Range, не один GET на весь архив | T3 частично |

Пороги **T1, T2, T4** — см. §3.7 и [PERFORMANCE.md](PERFORMANCE.md) §4.

### 3.4. Поиск и UI

| # | Критерий | Как проверить |
|---|---|---|
| S1 | Debounce 150 ms | В коде константа; verify не шлёт > 1 req на символ без паузы |
| S2 | `q.length < 2` | нет запроса в API; список пуст |
| S3 | Stale ignore | Быстрый ввод «apo» → «apotek»: в DOM финально hits от «apotek» |
| S4 | `q=apotek` | ≥ 5 строк в dropdown, ≥ 1 с текстом категории аптеки |
| S5 | Выбор hit | карта `flyTo` (center в ±0.01° от hit), маркер виден |
| S6 | Bottom sheet | после выбора — peek с `label` hit; анимация **≤ 250 ms** (U2) |
| S7 | Ошибка API 503 | mock или stop meili → сообщение «поиск недоступен», карта жива |
| S8 | URL | `?q=apotek&sel=org:…` — перезагрузка восстанавливает поиск и sheet |

### 3.5. Клик по POI (тайлы)

| # | Критерий | Как проверить |
|---|---|---|
| Poi1 | Клик по `poi-dot` / `poi-label` | `queryRenderedFeatures` на z16+ в центре → sheet с `name` из properties |
| Poi2 | Клик по пустому месту | sheet закрывается или остаётся прежний hit — поведение зафиксировано в коде и не падает |
| Poi3 | Без API | POI-карточка **не** вызывает `/orgs/:id` (его ещё нет) |

Фикстура POI: центр города z16, клик в точке с известным POI из MVT (verify использует координату из `infra/postgis/fixtures.sql` или фиксированную «Apoteka»-область).

### 3.6. UI-фикстуры (функциональные)

Verify (Playwright или Puppeteer, как `map-verify` этапа 1) открывает `http://127.0.0.1:5173`.

| # | Сценарий | Ожидание |
|---|---|---|
| F1 | Открытие `/` | карта + поле `#search` (или `data-testid=search-input`) |
| F2 | Ввод `apotek`, wait 300 ms | ≥ 5 `[data-testid=search-hit]` |
| F3 | Клик первый hit org | `[data-testid=bottom-sheet]` visible, текст содержит label hit |
| F4 | `?q=futo` reload | dropdown ≥ 3 hits |
| F5 | z15 center | rendered buildings > 0 (как M1) |
| F6 | F7 из stage03 | Те же 3 POI из fixtures — каждый находится поиском в top 5 и открывается sheet |

### 3.7. Производительность (обязательные SLO этапа)

Из [PERFORMANCE.md](PERFORMANCE.md) — **требовать на этом этапе**:

| ID | Порог | Как мерить |
|---|---|---|
| L1 | gzip JS+CSS ≤ **350 KB** без MapLibre; ≤ **900 KB** с MapLibre | `vite build` + gzip размер `dist/assets/*` |
| L2 | TTI: карта + поле поиска ≤ **3.5 s** Web-mid 4G | Lighthouse или `stage04-benchmark.mjs` (throttle 4G) |
| L3 | Нет экрана логина | assert на `/` |
| L4 | Glyphs не блокируют T2 > 10 s | timeout на load glyphs в benchmark |
| T1 | Улицы видны ≤ **1.5 s** | first paint roads layer |
| T2 | Здания z14 центр ≤ **2.5 s** | buildings layer |
| T4 | Pan/zoom без фризов **> 100 ms**; **≥ 30 FPS** среднее за жест | Performance panel, 5 s pan |
| U1 | Клавиша в поиск ≤ **16 ms** | Performance: input handler |
| U2 | Sheet open ≤ **250 ms** | animation end |
| U3 | Long task при жесте карты **< 50 ms** | во время pan |
| S1 | e2e search p95 ≤ **200 ms** при RTT 50 ms | 30× `apotek` из браузера через `fetch` к API (не только server p95 этапа 3) |
| S3 | Stale responses ignored | тест F3 + быстрый backspace |

На Slow 3G: T1 ≤ 4 s, T2 ≤ 6 s — notice, не блокер dev, но регрессию фиксировать.

### 3.8. Повторяемость

| # | Критерий | Как проверить |
|---|---|---|
| R1 | `pipeline-stage04` | build → verify → benchmark → exit 0 |
| R2 | PostGIS / Meilisearch / PMTiles | counts и mtime как после этапа 3 |
| R3 | Preview :8080 | `stage01-verify` по-прежнему зелёный (web не ломает tiles) |
| R4 | Verify красный = fail | Сломанный порог в копии скрипта падает |

### 3.9. Чек-лист закрытия (для человека)

- [ ] `docker compose up -d postgis meilisearch api tiles`
- [ ] `cd apps/web && npm run dev` — карта интерактивна
- [ ] `scripts/pipeline-stage04.ps1` → exit 0
- [ ] T1, T2, L1, S1 e2e в отчёте benchmark
- [ ] Визуально: layout «карта + поиск сверху + sheet снизу» похож на 2ГИС (ручная приёмка)
- [ ] В git: `apps/web/`, скрипты verify; **нет** `.env` с секретами

---

## 4. Константы

| Параметр | Значение |
|---|---|
| Dev URL web | `http://127.0.0.1:5173` |
| API (dev) | `http://127.0.0.1:3000` → `VITE_API_URL` |
| Tiles (dev) | `http://127.0.0.1:8080` → `VITE_TILES_URL` |
| MVT template | `${VITE_TILES_URL}/mvt/novi-sad/{z}/{x}/{y}.mvt` |
| Water fill | `${VITE_TILES_URL}/water-fill.geojson` |
| Default center | lon **19.845**, lat **45.255** |
| Default zoom | **14** |
| Search debounce | **150 ms** |
| Search min length | **2** (как API этапа 3) |
| Fly duration | **800 ms** (можно 600–1000) |
| Sheet peek height | **88 px** (+ safe-area) |
| MapLibre | **5.x** (та же major, что превью этапа 1) |

---

## 5. Компоновка экрана

Паттерн 2ГИС ([ARCHITECTURE.md](ARCHITECTURE.md) §6, [MOBILE.md](mobile/MOBILE.md) §2.2):

```text
┌─────────────────────────────────────┐
│  [ 🔍  Поиск...              ] [×]  │  ← SearchBar, z-index над картой
├─────────────────────────────────────┤
│                                     │
│           MapLibre full bleed       │
│              + marker               │
│                                     │
├─────────────────────────────────────┤
│  ═══  Bottom sheet (peek)           │  ← label, kind badge, category
│  Apoteka Benu · Аптека              │
└─────────────────────────────────────┘
```

**SearchBar**

- Input с `autocomplete="off"`, `enterkeyhint="search"`
- Dropdown под полем, max **15** hits (как S2 API)
- Иконка/бейдж `kind`: address vs organization
- Loading: тонкий индикатор, не блокирует карту

**BottomSheet** (v1 этапа 4 — только **peek** и **closed**; half/full — этап 5+)

- Peek: заголовок (`label`), подзаголовок (`category_name` или адрес)
- Свайп вниз / кнопка × → closed
- Для POI из тайла: `name` + «Из карты OpenStreetMap»

**MapView**

- При mount: patch `style.json` URLs (как `infra/preview/index.html`)
- Слой `selected-marker` (GeoJSON point или symbol)
- `cursor: pointer` на POI layers

---

## 6. Поиск (клиент)

Псевдологика — реализовать в `useSearch.ts`:

```text
onInput(q):
  if q.length < 2 → clear hits, abort fetch
  debounce 150ms
  abortController.abort previous
  GET ${VITE_API_URL}/v1/search?q=&limit=15&lat=&lon=
    lat/lon = текущий center карты (geo-bias)
  if response.id !== latestRequestId → ignore
  setHits(json.hits)
```

Ошибки:

| Ответ | UI |
|---|---|
| 200, hits [] | «Ничего не найдено» |
| 503 | «Поиск временно недоступен» |
| network | «Нет связи с сервером» |

Выбор hit:

```text
setSelectedHit(hit)
map.flyTo({ center: [hit.lon, hit.lat], zoom: max(current, 16) })
setMarker([hit.lon, hit.lat])
openSheet('peek')
history.replaceState ?q=&sel=hit.id
```

---

## 7. POI click (тайлы)

```text
map.on('click', (e) => {
  features = map.queryRenderedFeatures(e.point, { layers: ['poi-dot', 'poi-label'] })
  if features[0]:
    showSheet({ title: features[0].properties.name || 'POI', source: 'tile' })
  else if !clickedMarker:
    optional: closeSheet()
})
```

Не вызывать PostGIS. Телефон/часы для tile-POI не показывать — их нет в MVT.

---

## 8. Структура файлов

```text
apps/web/
  package.json
  vite.config.ts
  tsconfig.json
  index.html
  src/
    main.tsx
    App.tsx
    theme/
      tokens.css
    components/
      MapView.tsx
      SearchBar.tsx
      SearchDropdown.tsx
      BottomSheet.tsx
      KindBadge.tsx
    hooks/
      useSearch.ts
      useMapStyle.ts
    lib/
      api.ts              ← fetch search, base URL
      mapStyle.ts         ← load + patch infra/preview/style.json
    types/
      search.ts           ← SearchHit зеркало ответа /v1/search
scripts/
  stage04-verify.mjs      ← Playwright/Puppeteer
  stage04-verify.ps1
  stage04-verify.sh
  stage04-benchmark.mjs   ← L1, T1, T2, S1 e2e
  pipeline-stage04.ps1
  pipeline-stage04.sh
.env.example              ← + VITE_API_URL, VITE_TILES_URL
```

Стиль: **не копировать** 400 строк `style.json` в web. Загружать с `${VITE_TILES_URL}/style.json` (Caddy отдаёт preview) или импортировать из `../../infra/preview/style.json` на этапе сборки и патчить `tiles`/`data` URL в runtime.

---

## 9. Vite и CORS

**vite.config.ts**

- `server.port`: 5173
- `server.host`: true (для теста с телефона в LAN — опционально)
- Proxy **не обязателен**, если CORS на API настроен на этапе 3

Проверка CORS:

```text
Origin: http://localhost:5173
GET http://127.0.0.1:3000/v1/search?q=apo
→ Access-Control-Allow-Origin включает 5173
```

---

## 10. Пайплайн (порядок)

1. `docker compose up -d postgis meilisearch api tiles`
2. `cd apps/web && npm ci && npm run build`
3. `npm run dev` (или preview `npm run preview` на :4173 для verify)
4. `node scripts/stage04-verify.mjs`
5. `node scripts/stage04-benchmark.mjs`
6. Запись в `data/README.md`: gzip bundle, T1/T2 p95, дата прогона

Не запускать `pipeline-stage03` / `pipeline-stage02` / пересборку PMTiles.

---

## 11. `stage04-verify` — обязательные проверки

Минимум ассертов (кроме таблиц §3):

```text
assert vite_build == ok
assert page_load_map_buildings > 0
assert search(apotek).hits >= 5
assert select_first_hit → sheet visible
assert url contains sel=
assert poi_click_center → sheet title non-empty
assert bundle_gzip <= 900KB (with maplibre)
-- benchmark optional gate: T2 <= 2500ms, S1_e2e <= 200ms
```

Любой провал → exit **1**.

---

## 12. Типичные поломки

| Симптом | Что делать |
|---|---|
| Пустая карта, 404 на MVT | `VITE_TILES_URL` без `:8080`; Caddy не up |
| CORS error на search | Добавить `5173` в `@fastify/cors` api |
| Stale hits мигают | Нет `requestId` / abort; проверить S3 |
| Здания есть на :8080, нет на :5173 | Другой стиль или неверный patch URL тайлов |
| Glyphs 10 s | CDN openfreemap тормозит — L4; не блокировать навсегда |
| Sheet не открывается | z-index; pointer-events на overlay карты |
| POI click всегда пуст | Неверные layer id; нужен z16+ |
| L1 > 900 KB | Тянут entire maplibre dist дважды; code-split |
| F6 fail | Meilisearch index устарел — `pipeline-stage03` |
| flyTo в Футог | hit без координат — баг index этапа 3 (M3) |

---

## 13. Как давать задачу AI

Сначала **Grok 4.6** (layout + MapLibre + sheet). Потом **Composer 2.5** (search hook, verify, benchmark). CORS/Docker — **GPT-5.6 Sol**.

Промпт:

```
Сделай Этап 4 строго по Docs/STAGE-04-web-map.md.

Вход: зелёный stage03-verify, docker compose (postgis, meilisearch, api, tiles), PMTiles на :8080.
Не добавляй /orgs/:id, /buildings/at, /buildings/:id, Valhalla, OTP, Photon, Capacitor, админку. Не пересобирай PMTiles, не меняй schema PostGIS.

Нужны: apps/web (React+Vite+MapLibre), поиск с debounce 150ms → GET /v1/search, bottom sheet peek, клик POI из тайлов, URL ?q=&sel=, tokens.css, stage04-verify и pipeline-stage04 с порогами F1–F6, M1–M6, L1/L2/T1/T2/U2.

Запусти build и verify. В конце выведи gzip bundle, результат F3 и F6 по fixtures.
```

Визуальную приёмку «похоже на 2ГИС» — человек; Grok для первого прохода layout.

---

## 14. Оценка времени

| Работа | Ориентир |
|---|---|
| Vite scaffold + MapView + style patch | 2–3 часа |
| SearchBar + useSearch + dropdown | 2–3 часа |
| BottomSheet + marker + URL state | 2–3 часа |
| POI click | 1 час |
| verify + benchmark | 2–3 часа |
| Полировка tokens / safe-area | 1–2 часа |

---

## 15. Выход в этап 5

Этап 5 (клик по зданию) стартует только при зелёном stage04. Подробно: [STAGE-05-building-click.md](STAGE-05-building-click.md).

Этап 5 добавит:

- `GET /v1/buildings/at`, `GET /v1/buildings/:id`, `GET /v1/orgs/:id`, `GET /v1/orgs` bbox
- `map.on('click')` → точка → здание; подсветка GeoJSON контура
- Sheet: адреса, список организаций; переход org → полная карточка
- Пороги B1–B7, B4 из [PERFORMANCE.md](PERFORMANCE.md)

Клиент этапа 4 **не удалять** — расширять `MapView`, `BottomSheet`, `api.ts`.
