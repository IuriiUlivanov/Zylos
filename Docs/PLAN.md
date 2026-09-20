# Zylos — план реализации (клон 2ГИС)

Первый релиз: только город **Нови-Сад** (включая Петроварадин и Сремску-Каменицу как часть Grad Novi Sad, OSM relation `1649672`).

Платформы: **Android (Kotlin + MapLibre Native)** — основной клиент v1. Веб (`apps/web`) — референс API и Style JSON, **не развиваем**. Разработка с помощью AI. Где возможно — открытые данные.

Документы:

- Архитектура: [ARCHITECTURE.md](ARCHITECTURE.md)
- Производительность: [PERFORMANCE.md](PERFORMANCE.md)
- Мобильный клиент: [mobile/README.md](mobile/README.md), [MOBILE.md](mobile/MOBILE.md)
- Этап 1: [STAGE-01-extract.md](STAGE-01-extract.md)
- Что тестирует карта этапа 1: [STAGE-01-map-verify.md](STAGE-01-map-verify.md)
- Этап 2: [STAGE-02-postgis.md](STAGE-02-postgis.md)
- Этап 3: [STAGE-03-meilisearch.md](STAGE-03-meilisearch.md)
- Этап 4: [STAGE-04-web-map.md](STAGE-04-web-map.md)
- Этап 5: [STAGE-05-building-click.md](STAGE-05-building-click.md)
- Этап 10 (вместо Capacitor): [STAGE-10-android.md](mobile/STAGE-10-android.md)
- Этап 11 (Android, здание + sheet): [STAGE-11-android-building.md](mobile/STAGE-11-android-building.md)
- Этап 12 (Android, поиск): [STAGE-12-android-search.md](mobile/STAGE-12-android-search.md)
- Этап 13 (Android, org-пины): [STAGE-13-android-poi.md](mobile/STAGE-13-android-poi.md)
- Этап 14 (Android, transit): [STAGE-14-android-transit.md](mobile/STAGE-14-android-transit.md)

---

## Главный вывод

Карту, адреса, здания и маршруты можно собрать из открытых источников. **Справочник организаций как в 2ГИС открытыми данными не закрывается.**

OpenStreetMap даёт улицы, здания, остановки и часть POI. Телефоны, часы работы, теги и «кто сидит в этом здании» заполнены неравномерно. Без своей базы организаций и простой админки приложение останется картой, а не 2ГИС.

Парсить PlanPlus, Google Maps и NSmart нельзя: лицензии и условия использования это закрывают.

---

## Продукт v1: что должно работать

Интерфейс как у 2ГИС Android: карта на весь экран, поиск **снизу**, карточка объекта над поиском ([mobile/design/MOBILE-DESIGN.md](mobile/design/MOBILE-DESIGN.md)). Веб — search сверху, референс контракта.

| Функция | v1 | Откуда данные | Сложность |
|---|---|---|---|
| Карта города, улицы, реки, парки | Обязательно | OSM extract → PMTiles | Низкая |
| Организации: тип, имя, теги, адрес, телефон, сайт, часы | Обязательно, с дырами | OSM POI + своя БД + ручное наполнение | Высокая |
| Поиск адреса и организации | Обязательно | Meilisearch + Photon/Nominatim | Средняя |
| Здание: контур, адрес, список организаций внутри | Обязательно | OSM buildings + RGZ адреса + привязка POI | Средняя |
| Маршруты walk / bike / car | Обязательно | Valhalla на OSM | Средняя |
| Общественный транспорт: линии, остановки, trip planner | Обязательно, без live GPS | GTFS JGSP + OpenTripPlanner 2 | Высокая |
| Android | Нативный клиент v1 | Kotlin + MapLibre GL Native | Средняя |
| Веб | Референс, не продукт | React + MapLibre (заморожен после этапа 5) | — |

---

## Открытые источники под Нови-Сад

| Источник | Что даёт | Лицензия / риск | Как брать |
|---|---|---|---|
| **OpenStreetMap** | Дороги, здания, POI, остановки, часть `opening_hours` / `phone` | ODbL: атрибуция, share-alike на производную БД | Geofabrik `serbia-latest.osm.pbf`, обрезка osmium по границе города |
| **RGZ Адресни регистар** | Официальные улицы и кућни бројеви как точки | Открытые данные [data.gov.rs](https://data.gov.rs/sr/datasets/adresni-registar/), нужна атрибуция | CSV/GPKG по општине Нови Сад, еженедельное обновление |
| **GeoSrbija Buildings** | Кадастровые контуры зданий (если OSM дырявый) | INSPIRE / закон о РГЗ, проверить условия скачивания | WFS или download.geosrbija.rs; fallback, если OSM достаточен |
| **GTFS jgsp-novi-sad** | ~124 линии, ~1000 остановок, расписание | Лицензия не указана — запросить у JGSP письмом | Сначала официальный фид; BusMaps только как ориентир наличия |
| **NSmart / online.nsmart.rs** | Live прибытие автобусов | ToS запрещает автоматический сбор — не скрейпить | v1 без realtime; позже партнёрство или GTFS-RT, если отдадут |
| **Wikidata / Wikipedia** | Описания музеев, вузов, памятников | CC, с атрибуцией | Опционально для карточек landmarks |

Ссылки:

- OSM extract Сербии: <http://download.geofabrik.de/europe/serbia.html>
- Граница города: [OSM relation Grad Novi Sad 1649672](https://www.openstreetmap.org/relation/1649672)
- Населённый пункт: [OSM relation Novi Sad 9273976](https://www.openstreetmap.org/relation/9273976)
- Адресный регистр: <https://data.gov.rs/sr/datasets/adresni-registar/>
- OSM wiki по адресам RGZ: <https://wiki.openstreetmap.org/wiki/Serbia/Projekti/Adresni_registar>
- Транспорт OSM: <https://wiki.openstreetmap.org/wiki/Serbia/Javni_prevoz> (для Нови-Сада открытых данных на вики нет)
- Линии JGSP: <http://www.gspns.co.rs/mreza>, <https://online.nsmart.rs/sr/prikaz-svih-linija>

---

## Рекомендуемый стек под AI

Один extract, один API (Fastify), клиент v1 — нативный Android. Геосервисы — готовые open-source, свой роутер не писать. Веб не развиваем. Бэкенд не переписывать на Go/Rust.

### Клиент (Android; веб заморожен)

- **Android:** Kotlin, MapLibre GL Native, офлайн MBTiles
- Карта, поиск, bottom sheet, карточка организации, экран маршрута
- Веб (`apps/web`) не развиваем; не Capacitor, не Flutter, не второй набор JSON-типов
- Бэкенд: Fastify `/v1` (не Go/Rust)

### Сервер

- Fastify + PostgreSQL 16 / PostGIS + Meilisearch
- Свой API для организаций, зданий, избранного, админки

### Геостек в Docker

- **Valhalla** — пешком, велосипед, авто
- **OpenTripPlanner 2** — общественный транспорт (OSM + GTFS)
- **Photon** или **Nominatim** — геокодер
- **Caddy** (или nginx) раздаёт **PMTiles** (Protomaps)

Не использовать публичный Overpass в проде: нет стабильного поиска, нет зданий как карточек, нет своего каталога, таймауты публичных серверов. Нужен локальный extract и своя БД.

---

## Модель данных (минимум)

| Сущность | Поля v1 | Связи |
|---|---|---|
| `place` / `building` | `osm_id`, polygon, `height?`, `name`, `building:levels` | 1:N addresses, 1:N organizations |
| `address` | street (sr-Latn + sr-Cyrl), housenumber, postcode, `rg_id`, point | N:1 building по точке-в-полигоне |
| `organization` | name, category, tags[], phones[], website, hours, floor, source | N:1 address/building; `source = osm \| editorial` |
| `category` | slug, name_sr, name_ru, name_en, osm_mapping, icon | дерево: cafe → food, pharmacy → health |
| `transit_route` / `stop` | route_id, short_name, color, stops[], shape | импорт из GTFS, не дублировать вручную |
| `user` | не в критичном пути v1 | избранное и правки организаций — после каталога |

---

## Что подготовить до кода

AI без этих артефактов плывёт.

| Артефакт | Зачем | Формат |
|---|---|---|
| Product spec / user stories | Одинаковое понимание экранов: карта, поиск, карточка, маршрут, линия ОТ | Markdown в репо, 1–2 страницы |
| Bbox и extract script | Все сервисы режут одни и те же данные Нови-Сада | osmium extract + poly границы города |
| docker-compose геостека | AI поднимает карту/роутинг локально, не ходит в публичный OSM | valhalla, otp, photon, postgis, meilisearch, tiles |
| API OpenAPI | Клиент и сервер генерируются из контракта | `/places`, `/buildings/:id`, `/orgs`, `/search`, `/route` |
| Cursor rules + ADR | Чтобы агент не тащил Google Maps SDK и не скрейпил сайты | `.cursor/rules`: стек, лицензии, граница города |
| Админка editorial | Единственный путь закрыть дыры OSM по телефонам и тегам | Простая web-форма: создать/править организацию |
| Дизайн-токены | AI не выдумает третий UI на каждом экране | [mobile/design/MOBILE-DESIGN.md](mobile/design/MOBILE-DESIGN.md) — ориентир 2GIS, colors/dimens, иконки категорий |
| Acceptance fixtures | Проверки «нашёл аптеку», «построил автобус 7A», «здание с адресом» | Набор координат и OSM id в Нови-Саде |

---

## Порядок работ для AI

Идти слоями. Не начинать с мобильного клиента и не делать четыре режима маршрута, пока нет поиска адреса.

1. Скрипт: скачать OSM Serbia, обрезать Grad Novi Sad, собрать PMTiles — подробно в [STAGE-01-extract.md](STAGE-01-extract.md)
2. Импорт зданий, адресов OSM, POI в PostGIS; затем RGZ housenumbers — подробно в [STAGE-02-postgis.md](STAGE-02-postgis.md)
3. Meilisearch + `GET /v1/search` — подробно в [STAGE-03-meilisearch.md](STAGE-03-meilisearch.md)
4. Веб-карта MapLibre: тайл, поиск, клик по POI, карточка снизу
5. Клик по зданию → контур, адрес, список организаций с тем же адресом
6. Админка: ручное добавление телефона/часов поверх OSM
7. Valhalla: пешком, велосипед, авто; линия маршрута на карте
8. Достать GTFS у JGSP, импорт линий/остановок, карта маршрутов
9. OpenTripPlanner 2: A→B общественным транспортом + пешая доноска
10. Нативный Android (`apps/android`): MapLibre Native, офлайн MBTiles — [STAGE-10-android.md](mobile/STAGE-10-android.md)

---

## Какие модели AI на каких этапах

Оценка на сентябрь 2026, под разработку в Cursor. Принцип: **думать сильной моделью, писать объём быстрой, проверять другой**. Не отдавать одной модели и схему данных, и docker-compose, и карту.

В этом проекте в Cursor доступны как минимум:

| Модель | Роль | Сильные стороны | Слабые стороны |
|---|---|---|---|
| **Composer 2.5** (Fast) | Основной исполнитель | Дешёвый, быстрый, хорошо пишет код по готовому плану, многофайловые правки, UI-CRUD, Capacitor | Не планировать на нём архитектуру и лицензии; на капризном геостеке может «почти завести» и оставить ломаный compose |
| **Grok 4.6** | Карта, продукт, длинные сессии | Интерактивный UI, MapLibre, визуальная вёрстка, сквозная реализация по спеке, исследование открытых данных | Дороже Composer; для тонкой PostGIS/OTP-диагностики лучше подключать Opus |
| **Claude Opus 5** (Thinking) | Архитектор и ревьюер | Модель данных, conflation OSM+RGZ, OpenAPI, правила Cursor, разбор странных багов роутинга/GTFS | Дорогой пул Other Models; не гонять на нём сотни мелких правок |
| **GPT-5.6 Sol** | Терминал и геосервисы | Длинные цепочки команд, `docker compose`, Valhalla/OTP/osmium/PMTiles, логи контейнеров | Не первый выбор для визуального UI; тоже из пула Other Models |

Пул **Cursor Models** (Grok 4.6, Composer 2.5) расходуется щедрее. Opus и GPT-5.6 Sol идут из **Other Models** по цене API — их беречь на развилки и поломки.

### Привязка к этапам Zylos

| Этап | Модель | Зачем так |
|---|---|---|
| Спека экранов, ADR, `.cursor/rules`, лицензии ODbL/RGZ/GTFS | **Opus 5 Thinking** | Нужно жёстко запретить скрейп и Google Maps SDK; ошибки здесь дороже кода |
| OpenAPI и схема PostGIS (`building`, `address`, `organization`) | **Opus 5**, затем Composer пишет миграции | Пространственные связи и `source = osm \| editorial` нельзя импровизировать |
| OSM extract, poly границы, PMTiles, `docker-compose` геостека | **GPT-5.6 Sol** проектирует и поднимает, **Composer** потом чистит скрипты | Много терминала и конфигов Valhalla/OTP; Sol лучше держит длинные команды |
| Импорт OSM → PostGIS, затем RGZ housenumbers, точка-в-полигоне | **Opus 5** на SQL/conflation, **Composer** на обвязке пайплайна | Ошибки привязки «организация ↔ здание» ломают весь продукт |
| Meilisearch, Fastify CRUD `/search` `/orgs` `/buildings` | **Composer 2.5** | Типовой бэкенд по уже зафиксированному OpenAPI |
| Карта MapLibre, поиск, bottom sheet, карточка POI | **Grok 4.6** каркас UI, **Composer** итерации по мелочам | Grok сильнее на визуальном первом проходе «как 2ГИС» |
| Клик по зданию: контур, адрес, список организаций | **Grok 4.6** UI + **Opus 5** если путается hit-test/полигоны | Слой карты и SQL должны сойтись |
| Админка editorial | **Composer 2.5** | Простые формы поверх готовой модели |
| Valhalla: walk / bike / car, линия на карте | **GPT-5.6 Sol** сервис, **Grok 4.6** отрисовка маршрута | Сначала должен ответить роутер, потом UI |
| GTFS JGSP, линии и остановки на карте | **GPT-5.6 Sol** импорт, **Grok 4.6** слой карты | Валидировать фид инструментами (`gtfs-validator`), не «на глаз» моделью |
| OpenTripPlanner 2, trip planner ОТ | **Opus 5** + **GPT-5.6 Sol** | Самый ломкий кусок: граф, таймзоны, пешая доноска до остановки |
| Нативный Android, MapLibre Native, MBTiles | **Grok 4.6** карта, **Composer** Gradle | Не Capacitor; проверять на устройстве человеком |
| Ревью PR, регрессии, «почему маршрут едет через поле» | **Opus 5** или Bugbot; не той моделью, что писала код | Нужен другой взгляд |

### Как работать день за днём

1. **Plan mode + Opus 5** — короткий план на один этап (файлы, контракт, критерий «готово»).
2. **Agent + Composer 2.5 Fast** — реализация по этому плану.
3. **Grok 4.6** — экраны карты и визуальная проверка «похоже ли на 2ГИС».
4. **GPT-5.6 Sol** — когда падает Docker, Valhalla не билдит тайлы, OTP не ест GTFS.
5. **Opus 5** — ревью схемы, ETL и роутинга перед тем как считать этап закрытым.

Параллелить можно только после OpenAPI: один агент (Composer) пишет API, другой (Grok) — карту, оба против одного контракта. Пока контракта нет, параллель плодит две модели данных.

Режим **Auto** годится для переименований и мелкого CSS. Не для границы города, лицензий и PostGIS.

### Что моделям не отдавать

LLM не заменяет геоинструменты. Детерминированно, не «на глаз»:

- обрезка OSM — `osmium extract`
- проверка GTFS — MobilityData GTFS Validator
- сборка тайлов — Planetiler / tippecanoe / Protomaps
- проверка роутинга — реальные A→B по Нови-Саду (центр, Лиман, Петроварадин, мост)
- визуал карты — браузер / устройство, не скриншот в чате как единственная проверка

Человек остаётся на: письмо в JGSP за GTFS, приёмку админки справочника, поездку по 5–10 маршрутам в городе.

---

## Что сознательно не брать в первый релиз

Отложить:

- indoor-карты ТЦ
- отзывы и фото как у 2ГИС
- live GPS автобусов
- пробки
- 3D-здания
- навигация для грузовиков
- офлайн-роутинг на телефоне
- аккаунты и избранное
- другие города (Белград, Ниш)

Не использовать:

- Google Maps SDK
- платный Mapbox как единственную карту
- скрейп PlanPlus / 011info / NSmart
- публичный Overpass в проде
- Capacitor / Flutter / React Native вокруг веба (веб заморожен; клиент v1 — `apps/android`)

---

## Оценка трудоёмкости

Ориентир для AI-разработки, не замер.

| Блок | Доля работы | Почему |
|---|---|---|
| Данные и ETL | 30% | Качество 2ГИС = качество справочника и адресов |
| Роутинг + GTFS/OTP | 25% | Связка OSM + расписание капризная |
| Карта и UI | 15% | Паттерн 2ГИС; веб заморожен, UI на Android |
| API и поиск | 15% | Стандартный CRUD + геозапросы |
| Нативный Android | 15% | MapLibre Native + MBTiles |

Для одного города стек поднимается за дни. «Похожесть на 2ГИС» упирается в наполнение организаций.

---

## Первый конкретный шаг

Этапы 1–5 закрыты. Карта Android: [STAGE-10-android.md](mobile/STAGE-10-android.md). Тап по зданию: [STAGE-11-android-building.md](mobile/STAGE-11-android-building.md). Autocomplete: [STAGE-12-android-search.md](mobile/STAGE-12-android-search.md). Org-пины и Search Multi: [STAGE-13-android-poi.md](mobile/STAGE-13-android-poi.md). Следующий шаг — [STAGE-14-android-transit.md](mobile/STAGE-14-android-transit.md) (`POST /v1/route` + transit UI). GTFS JGSP — предусловие этапа 14.

---

## Зафиксированные решения v1

- Граница: Grad Novi Sad (OSM relation `1649672`)
- Карта: OpenStreetMap, лицензия ODbL
- Адреса: RGZ Адресни регистар
- Транспорт: GTFS JGSP по согласованию
- Стек: Android Kotlin + MapLibre Native; Fastify `/v1`; PostGIS, Meilisearch, Valhalla, OTP2, Protomaps; веб — референс
- AI: Opus 5 — схема и ревью; GPT-5.6 Sol — Docker/Valhalla/OTP; Grok 4.6 — карта и UI; Composer 2.5 — основной объём кода
