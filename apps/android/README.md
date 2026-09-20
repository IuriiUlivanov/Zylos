# Zylos Android

Нативный клиент. Текущий этап: [STAGE-14-android-transit.md](../../Docs/mobile/STAGE-14-android-transit.md) (org-пины — [STAGE-13-android-poi.md](../../Docs/mobile/STAGE-13-android-poi.md), поиск — [STAGE-12-android-search.md](../../Docs/mobile/STAGE-12-android-search.md), здание — [STAGE-11-android-building.md](../../Docs/mobile/STAGE-11-android-building.md), карта — [STAGE-10-android.md](../../Docs/mobile/STAGE-10-android.md)). UI/UX — [MOBILE-DESIGN.md](../../Docs/mobile/design/MOBILE-DESIGN.md) (ориентир 2GIS). Веб не развиваем. API — существующий Fastify `/v1`.

## Сборка

Нужны JDK 17+ (JBR из Android Studio подходит) и Android SDK. Gradle 8.14.4.

```powershell
# Офлайн-тайлы (Docker + extract этапа 1)
..\..\scripts\build-mbtiles.ps1

$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd apps\android
.\gradlew :app:assembleDebug :app:testDebugUnitTest
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

`novi-sad.mbtiles` копируется в assets на `preBuild` из `data/tiles/`. В git файла нет.

## API с устройства

| Где | Base URL |
|---|---|
| Эмулятор | `http://10.0.2.2:3000` (default `BuildConfig.API_URL`) |
| Телефон в LAN | `docker compose -f docker-compose.yml -f docker-compose.mobile.yml up -d api` и `-Pzylos.apiUrl=http://<LAN-IP>:3000` |

Debug APK разрешает HTTP (`usesCleartextTraffic=true`). `network_security_config.xml` явно белит `10.0.2.2` / `localhost`; LAN-IP телефона покрывается cleartext debug, без записи каждого RFC1918 домена.

Карта в Airplane Mode рисуется из MBTiles. Справочник зданий и org идёт в API: без сети тап показывает `Nema veze sa serverom`, карта жива.

## Здание и карточка (STAGE-11)

Тап по карте (не по строке поиска **внизу**) → `GET /v1/buildings/at` → `GET /v1/buildings/:id`. Контур — GeoJSON из PostGIS. Object sheet **над** search dock, 8 dp зазор: **3 шага** (высота search / 50% / 100% контентной зоны), открытие на шаге 2; свайп не закрывает с шага 1 — только **×**. Тап по org → `GET /v1/orgs/:id`. Макеты — [object-card](../../Docs/mobile/design/object-card/README.md).

```powershell
# API (хост)
docker compose up -d postgis api
..\..\scripts\stage11-verify.ps1
```

## Поиск (STAGE-12)

Строка **снизу** (2GIS Android). Autocomplete `GET /v1/search` (debounce 150 ms, min 2 символа, `limit=10`, geo из камеры). Dropdown растёт **вверх**. Выбор hit → `easeTo` 800 ms, zoom ≥ 16, маркер `selected-marker`, sheet building / org / peek. Room `search_history` — последние 10 уникальных запросов (офлайн только история). Спека: [STAGE-12-android-search.md](../../Docs/mobile/STAGE-12-android-search.md).

```powershell
docker compose up -d postgis meilisearch api
..\..\scripts\stage12-verify.ps1
```

Org-пины по zoom — [STAGE-13-android-poi.md](../../Docs/mobile/STAGE-13-android-poi.md), [MOBILE-POI-ZOOM.md](../../Docs/mobile/design/MOBILE-POI-ZOOM.md). Маршрут transit — [STAGE-14-android-transit.md](../../Docs/mobile/STAGE-14-android-transit.md).

## Org-пины и Search Multi (STAGE-13)

Browse z15+: `GET /v1/orgs?bbox=` (debounce 300/250/200 ms, limit 40…200). Слой `org-pins` поверх MBTiles. Mobile Style JSON — `infra/preview/style-mobile.json` (`poi-dot` / `poi-label` по rank). Категорийный поиск (≥3 org одной категории) или кнопка **Prikaži sve na karti** → Search Multi (≤15 пинов, `fitBounds`, browse скрыты).

```powershell
docker compose up -d postgis meilisearch api
..\..\scripts\stage13-verify.ps1
```

## Маршрут transit (STAGE-14)

Вкладки **Pretraga | Ruta**. `POST /v1/route` mode=`transit` через OTP2 (JGSP GTFS). Walk — пунктир, автобус — сплошная линия + номер. Timeout клиента 8 с.

```powershell
# GTFS: распаковать zip в data/gtfs/jgsp/ (agency.txt, routes.txt, …)
docker compose up -d postgis otp api
..\..\scripts\stage14-verify.ps1
```

Телефон в LAN: `docker compose -f docker-compose.yml -f docker-compose.mobile.yml up -d api otp`.

Центр камеры: 19.845, 45.255; zoom 14; pitch ≤ 60°. Атрибуция OSM на карте.
