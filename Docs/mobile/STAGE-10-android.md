# Этап 10. Нативный Android: офлайн-карта MapLibre Native

Связан с [MOBILE.md](MOBILE.md), [PLAN.md](../PLAN.md) (пункт 10 — Capacitor **заменён**), [ARCHITECTURE.md](../ARCHITECTURE.md), [PERFORMANCE.md](../PERFORMANCE.md).

Цель: появиться **`apps/android`** — Kotlin + MapLibre GL Native. На экране карта Нови-Сада из локального **MBTiles**, тот же Style JSON что `infra/preview/style.json` (`fill-extrusion`, glyphs). Веб (`apps/web`) **не трогаем**. Бэкенд остаётся Fastify `/v1` — **не** переписывать на Go/Rust. Pelias, Google Places, Capacitor — не поднимать.

Вход, без которого не начинать:

- Этапы 1–5 закрыты: extract, PostGIS, Meilisearch, веб-карта как референс контракта, клик по зданию в API
- `data/osm/novi-sad.osm.pbf` на диске
- `infra/preview/style.json` содержит слой `buildings-3d` (`fill-extrusion`)
- `GET /v1/search`, `/buildings/at`, `/orgs/:id` отвечают локально (для DTO; UI поиска — следующие этапы)

Модели: **Grok 4.6** — каркас Android и карта. **GPT-5.6 Sol** — Planetiler → MBTiles. **Composer 2.5** — Gradle, verify. **Opus 5** — если путается контракт `/v1` или лицензии.

---

## 1. Зачем этот этап

Этапы 4–5 доказали карту и справочник в браузере. Продукт v1 — **телефон**. Capacitor вокруг веба не даёт офлайн MBTiles и нативный FPS MapLibre Native.

После этапа 10 можно писать тап по зданию и поиск (этапы 4–5 в [MOBILE.md](MOBILE.md) §6) уже в `apps/android`, не развивая `apps/web`.

---

## 2. Что входит и что нет

### Входит

- Скрипты `scripts/build-mbtiles.ps1` / `.sh`: тот же PBF → Planetiler → `data/tiles/novi-sad.mbtiles` (OpenMapTiles, **maxzoom 14**, как PMTiles)
- Verify размера **≤ 30 MB** и слоёв `building`, `housenumber`, `transportation`
- Проект `apps/android`: AGP 8+, JDK 17 (или JBR), `minSdk 24`, `targetSdk 35`
- MapLibre GL Native (`org.maplibre.gl:android-sdk`), `MapView` на весь экран
- Стиль: копия `infra/preview/style.json` + `water-fill.geojson`; источник тайлов `mbtiles://` после копирования файла из assets на диск
- Камера: центр **19.845, 45.255**, zoom **14**; rotate 360°; pitch **≤ 60°**
- Поисковая строка как хром UI **снизу экрана** ([design/MOBILE-DESIGN.md](design/MOBILE-DESIGN.md) §2), sr-Latn, **без** запросов к API
- Adaptive icon, splash, атрибуция OSM на карте
- Kotlin DTO, зеркало JSON `apps/api/src/types` (`search`, `building`, `org`) — форма не выдумывать
- `BuildConfig.API_URL` (debug: `http://10.0.2.2:3000` для эмулятора)
- `docker-compose.mobile.yml`: API слушает не только `127.0.0.1`, чтобы дебажить с телефона по LAN (HTTP; HTTPS — stage/prod)
- Скрипты `scripts/stage10-verify.ps1` / `.sh`

### Не входит

- Тап по зданию, bottom sheet, `GET /v1/buildings/at` в UI — [STAGE-11-android-building.md](STAGE-11-android-building.md)
- Autocomplete / Room / `GET /v1/search` в UI — этап 5
- `POST /v1/route`, OTP, GTFS — этапы 2.2 / 5.2; можно параллельно, **не блокер** карты
- Retrofit/Hilt/Room как рабочий стек сети (зависимости — когда появится UI поиска)
- `packages/shared` OpenAPI codegen
- Правки `apps/web`, новые фичи веба
- Переписывание `apps/api` на Go/Rust
- Google Play Console, keystore, политика конфиденциальности (ручные шаги)
- iOS
- maxzoom 16 в MBTiles (раздует APK; overzoom MapLibre с 14)

---

## 3. Критерии выполнения (Definition of Done)

Этап закрыт, только если `scripts/stage10-verify.ps1` (или `.sh`) → exit **0**. «Проект открылся в Android Studio» недостаточно.

### 3.1. Предусловия

| # | Критерий | Как проверить |
|---|---|---|
| P1 | Extract на месте | `data/osm/novi-sad.osm.pbf` существует |
| P2 | Стиль-источник | `infra/preview/style.json` содержит `"type": "fill-extrusion"` |
| P3 | API типы зафиксированы | `apps/api/src/types/{search,building,org}.ts` без изменения формы в этом этапе |

### 3.2. MBTiles

| # | Критерий | Порог |
|---|---|---|
| M1 | Файл собран | `data/tiles/novi-sad.mbtiles` |
| M2 | Размер | **1–30 MB** |
| M3 | Слои OpenMapTiles | metadata/json содержит `building`, `housenumber`, `transportation` |
| M4 | Идемпотентность | повторный `build-mbtiles` без `-Force` не пересобирает |
| M5 | PMTiles не тронут | mtime `data/tiles/novi-sad.pmtiles` без изменений |

### 3.3. Android-проект

| # | Критерий | Как проверить |
|---|---|---|
| A1 | Модуль | `apps/android/app/build.gradle`, `applicationId` `rs.zylos.novisad` |
| A2 | MapLibre | зависимость `org.maplibre.gl:android-sdk` |
| A3 | Жесты | `setMaxPitchPreference(60)`, rotate/tilt/zoom/scroll включены |
| A4 | Офлайн-путь | карта читает локальный MBTiles (`mbtiles://`), не PMTiles через API |
| A5 | Иконки | adaptive `mipmap-anydpi-v26` + PNG fallback + `branding/icon-512.png` |
| A6 | DTO | Kotlin-модели search / building / org совпадают по полям с API |
| A7 | Сборка | `./gradlew :app:testDebugUnitTest` (нужны JDK 17 и Android SDK) |

Пороги T1/T2 offline ≤ 800 ms и T4 ≥ 30 FPS — **приёмка на устройстве** после первой установки; verify проверяет, что код идёт по локальному файлу, не подменяет замер.

---

## 4. Сборка

```powershell
# MBTiles (нужен Docker + extract)
.\scripts\build-mbtiles.ps1

# Проверки этапа
.\scripts\stage10-verify.ps1

# APK (Android SDK)
cd apps/android
.\gradlew :app:assembleDebug
```

Эмулятор: API с хоста — `http://10.0.2.2:3000`. Телефон в LAN:

```powershell
docker compose -f docker-compose.yml -f docker-compose.mobile.yml up -d api
```

`ZYLOS_API_URL=http://<LAN-IP>:3000` при сборке, либо `gradle.properties`.

---

## 5. Пути, которые меняет этап

- `apps/android/**`
- `scripts/build-mbtiles.ps1`, `scripts/build-mbtiles.sh`
- `scripts/stage10-verify.ps1`, `scripts/stage10-verify.sh`
- `docker-compose.mobile.yml`
- `.gitignore` (mbtiles, Gradle)
- Документы: этот файл, правки ссылок в PLAN / ARCHITECTURE / MOBILE / правило Cursor — **без** развития веба

Не менять: `apps/web/**`, схему PostGIS, контракт JSON `/v1`, `data/tiles/novi-sad.pmtiles`.
