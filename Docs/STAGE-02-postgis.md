# Этап 2. PostGIS: здания, адреса, организации

Связан с [PLAN.md](PLAN.md) и [STAGE-01-extract.md](STAGE-01-extract.md).

Цель: в том же PostGIS, который уже крутится после этапа 1, лежат **здания**, **адреса** и **организации** Нови-Сада. Адрес и POI привязаны к контуру здания (точка-в-полигоне). Поиск, API и UI приложения ещё не пишем.

Вход этапа 1, без которого не начинать:

- `data/osm/novi-sad.osm.pbf`
- `data/boundary/grad-novi-sad.geojson` (relation **1649672**)
- `docker compose` → сервис `postgis` healthy, БД `zylos`

Этап **не** пересобирает PMTiles и **не** меняет relation id.

Модели: **Opus 5 Thinking** — схема таблиц и SQL conflation. **Composer 2.5** — скрипты импорта. **GPT-5.6 Sol** — если ломаются osm2pgsql / ogr2ogr / Docker. Карту и SQL-отчёт смотрит человек.

---

## 1. Зачем этот этап

PMTiles умеют рисовать здания, но не отвечают на вопрос «кто в этом доме». 2ГИС начинается со справочника: здание → адреса → организации.

OpenStreetMap даёт контуры и часть POI. Официальные кућни бројеви — из **RGZ Адресни регистар**. Связка — PostGIS, не MapLibre.

Без этого этапа поиск (этап 3) и клик по зданию (этап 5) не на чем строить.

---

## 2. Что входит и что нет

### Входит

- SQL-схема: `category`, `building`, `address`, `organization`
- Импорт из `data/osm/novi-sad.osm.pbf`: здания, OSM-адреса (`addr:*`), POI
- Скачивание RGZ (улицы + кућни бројеви), обрезка тем же GeoJSON, что и OSM
- Трансформация CRS в **EPSG:4326**
- Привязка: адрес ∈ здание, организация → адрес и/или здание
- Справочник категорий (маппинг OSM-тегов)
- Идемпотентный повторный импорт
- Скрипт проверки `scripts/stage02-verify.sql` с **жёсткими порогами**
- Запись в `data/README.md`: счётчики, дата RGZ, доля адресов внутри зданий

### Не входит

- Meilisearch, Fastify, OpenAPI, React, Capacitor
- Админка editorial (`source = editorial` в схеме есть, строк editorial нет)
- Valhalla, OTP, GTFS
- Кадастр GeoSrbija Buildings (только если OSM-контуров катастрофически мало — см. §10)
- Скрейп PlanPlus / 011info / NSmart / Google
- Публичный Overpass
- Пересборка тайлов
- Дедуп организаций «одна аптека в OSM дважды» вручную (кроме уникальности `source + source_id`)

---

## 3. Критерии выполнения (Definition of Done)

Этап закрыт, только если выполнены **все** пункты ниже. «Compose зелёный» недостаточно. Скрипт `scripts/stage02-verify.sql` должен завершаться с кодом **0**.

### 3.1. Предусловия

| # | Критерий | Как проверить |
|---|---|---|
| P1 | Этап 1 закрыт | Есть `data/osm/novi-sad.osm.pbf`, PostGIS отвечает |
| P2 | Тот же extract | `novi-sad.osm.pbf` не пересобран «для удобства» из другого bbox; relation по-прежнему 1649672 |
| P3 | Нет Overpass в импорте | В скриптах нет `overpass-api.de` / `overpass.kumi.systems` |

### 3.2. Схема

| # | Критерий | Как проверить |
|---|---|---|
| S1 | Таблицы существуют | `\dt` показывает `category`, `building`, `address`, `organization` |
| S2 | Геометрия 4326 | `Find_SRID('public','building','geom')` = 4326 (и то же для `address.geom`) |
| S3 | Индексы | GIST на `building.geom`, `address.geom`, `organization.geom`; unique на `(osm_type, osm_id)` у зданий; unique на `(source, source_id)` у address и organization |
| S4 | Категории заполнены | `SELECT count(*) FROM category` ≥ 30; есть как минимум `cafe`, `restaurant`, `pharmacy`, `bank`, `school`, `hospital`, `shop` |

Поля — не меньше этого набора (имена можно уточнить, смысл нельзя):

**building**

- `id` UUID/bigserial
- `osm_type` (`n`/`w`/`r`), `osm_id`
- `geom` polygon/multipolygon, NOT NULL
- `name` nullable
- `building_levels` nullable int
- `height_m` nullable numeric

**address**

- `source` = `osm` | `rgz`
- `source_id` (для OSM: `n123`/`w456`; для RGZ: јединствени адресни код)
- `street`, `housenumber` (housenumber NOT NULL)
- `street_sr_cyrl` nullable
- `postcode` nullable
- `rg_id` nullable (заполнен у RGZ)
- `geom` point NOT NULL
- `building_id` nullable FK → building

**organization**

- `source` = `osm` | `editorial`
- `source_id`
- `name` NOT NULL
- `category_id` FK → category
- `tags` text[] (сырые OSM-ключи полезные: `cuisine`, `brand`, …)
- `phones` text[]
- `website` nullable
- `hours` nullable (`opening_hours`)
- `floor` nullable
- `geom` point NOT NULL (центроид POI или точка входа)
- `address_id` nullable FK
- `building_id` nullable FK

**category**

- `slug`, `name_sr`, `name_ru`, `name_en`, `osm_key`, `osm_value`, `icon`

### 3.3. Объёмы данных (жёсткие пороги)

Пороги — **минимумы** для Grad Novi Sad. Если скрипт даёт меньше — импорт сломан или обрезан не той границей, этап не закрыт.

| # | Метрика | Минимум | Зачем |
|---|---|---|---|
| C1 | `building` | **10 000** | Город не может дать сотни зданий |
| C2 | `address` where source = `osm` | **2 000** | OSM `addr:housenumber` в городе есть |
| C3 | `address` where source = `rgz` | **20 000** | Официальный регистр по городу — десятки тысяч точек |
| C4 | `organization` where source = `osm` | **1 500** | amenity/shop/office после фильтра |
| C5 | Доля RGZ-адресов с `building_id IS NOT NULL` | **≥ 50%** | Conflation работает; 100% нереально (нет контура) |
| C6 | Доля организаций с `building_id IS NOT NULL` | **≥ 40%** | Иначе клик по зданию пустой |
| C7 | Зданий с ≥1 организацией | **≥ 300** | Есть «дома с содержимым» |
| C8 | Invalid geometry | **0** | `ST_IsValid(geom)` для building; чинить `ST_MakeValid` на импорте |
| C9 | Организации без `name` | **0** | Безымянные POI не импортировать (или имя = категория, тогда критерий: 0 NULL name) |
| C10 | Точки вне полигона города | **0** у address/organization | `ST_Contains` по загруженной границе; допуск 5 м `ST_DWithin` |

Граница города должна лежать в таблице `city_boundary` (один полигон relation 1649672) — для проверок C10.

Если **C3 провален из-за недоступности RGZ** (логин, 403, пустой GPKG) — не подделывать цифры. Зафиксировать блокер в `data/README.md` и **не закрывать этап**. Обход: только OSM-адреса, это **не** DoD.

### 3.4. Качество связки (фикстуры)

SQL должен вернуть строки. Координаты — WGS84 lon/lat.

| # | Фикстура | Ожидание |
|---|---|---|
| F1 | Центр, Трг слободе `19.845, 45.255` | В радиусе 80 м есть ≥ 5 зданий и ≥ 1 organization |
| F2 | Петроварадин `19.862, 45.252` | ≥ 1 building (крепость/застройка не пустая — значит не relation 9273976) |
| F3 | Лиман `19.840, 45.238` | ≥ 20 buildings в радиусе 200 м |
| F4 | Известный POI OSM | Зафиксировать в `infra/postgis/fixtures.sql` 3 объекта с `osm_id` после первого успешного импорта (кафе / аптека / банк в центре). Повторный импорт находит те же `source_id` |
| F5 | Адрес с номером | Существует address с `housenumber` похожим на `\d+` и непустым `street`, внутри здания в центре |
| F6 | Здание с несколькими орг. | Хотя бы одно `building_id`, у которого `count(organization) ≥ 2` (ТЦ / факультет / рынок) |

`fixtures.sql` появляется **после** первого успешного прогона: агент выбирает реальные `osm_id` из БД и вписывает их. Пока файла нет — F4 не закрыт.

### 3.5. Повторяемость и артефакты

| # | Критерий | Как проверить |
|---|---|---|
| R1 | Повторный импорт | Второй запуск не дублирует строки (unique + truncate/upsert). Counts ±1% от первого прогона |
| R2 | PMTiles не трогали | mtime/size `data/tiles/novi-sad.pmtiles` как после этапа 1 |
| R3 | Атрибуция | В `data/README.md`: OSM ODbL **и** RGZ / data.gov.rs |
| R4 | Отчёт | `data/README.md` содержит таблицу counts C1–C7 и дату RGZ-файла |
| R5 | Команда одной кнопкой | `scripts/pipeline-stage02.ps1` (и `.sh`) делает: schema → OSM → RGZ → link → verify |
| R6 | Verify красный = fail | Нарочно сломанный порог в копии скрипта падает; боевой скрипт падает, если C1–C10 не выполнены |

### 3.6. Чек-лист закрытия этапа (для человека)

Отметить все пункты, иначе этап открыт:

- [ ] `docker compose exec postgis psql -U zylos -f /verify/stage02-verify.sql` → success
- [ ] C1–C10 в отчёте выше минимума
- [ ] F1–F3 глазами по координатам (можно `ST_AsGeoJSON` + geojson.io или превью этапа 1 + сверка counts)
- [ ] F4–F6 есть в `fixtures.sql` и проходят
- [ ] RGZ импортирован, не «потом»
- [ ] В git есть схема, скрипты, seed категорий, verify; нет GPKG/PBF/CSV регистра

---

## 4. Константы

| Параметр | Значение |
|---|---|
| PBF | `data/osm/novi-sad.osm.pbf` |
| Граница | `data/boundary/grad-novi-sad.geojson`, relation `1649672` |
| БД | `zylos`, user из `.env`, порт `127.0.0.1:5432` |
| CRS приложения | EPSG:4326 |
| RGZ CRS на входе | Часто EPSG:32634 или 8682 — **определить `ogrinfo`**, затем `ST_Transform` |
| Обрезка RGZ | Тем же полигоном, не фильтром «општина = Нови Сад» (иначе выпадет Петроварадин) |
| Источник RGZ | [data.gov.rs Адресни регистар](https://data.gov.rs/sr/datasets/adresni-registar/) — GPKG кућни бројеви + улицы |
| Запасной URL | `https://download.geosrbija.rs/download-api/opendata-proxy/export?category=ar&layer=kucni_broj_ar&geometry=true&fileName=kucni_br_gpkg&format=gpkg` |
| Лицензии | OSM ODbL; RGZ — открытые данные портала, атрибуция |
| Допуск точки к зданию | Сначала `ST_Contains`; иначе `ST_DWithin(..., 3.0)` в метрах через `geography` |

Не фильтровать RGZ по одной општине: Grad Novi Sad = Нови-Сад **и** Петроварадин (Сремска-Каменица и др.).

---

## 5. Правила импорта OSM

Инструмент: **osm2pgsql flex** или **osmium tags-filter + ogr2ogr**. Не invent свой PBF-парсер на 2000 строк Python, если flex закрывает задачу.

### Здания

Брать `building=*`, кроме `building=no`. Ways и relations (мультиполигоны). `ST_MakeValid`. Выкинуть площадь < 8 м² (мусор). Имя: `name`, иначе `name:sr-Latn` / `name:sr`.

### Адреса OSM

Узлы и контуры с `addr:housenumber`. Точка: у узла — координата, у здания — `ST_PointOnSurface`. Не импортировать без номера.

### Организации

Импортировать, если есть **непустое имя** и один из ключей:

| Ключ | Примеры values |
|---|---|
| `amenity` | cafe, restaurant, fast_food, pharmacy, bank, school, hospital, clinic, dentists, theatre, cinema, library, place_of_worship, fuel, parking, police, post_office, townhall, community_centre, bar, pub, ice_cream, kindergarten, university, college, doctors, veterinary |
| `shop` | любой, кроме `vacant` |
| `office` | любой с name |
| `tourism` | hotel, museum, attraction, gallery, guest_house, hostel |
| `healthcare` | любой с name |
| `craft` | любой с name |
| `leisure` | sports_centre, stadium, fitness_centre (с name) |

Не тащить скамейки, деревья, `amenity=bench`, `amenity=waste_basket`.

Поля: `phone`/`contact:phone`, `website`/`contact:website`, `opening_hours`, `addr:street` + `addr:housenumber` как текст (плюс связь через точку).

`source = 'osm'`, `source_id = 'w'||osm_id` и т.д.

### Категории

Файл `infra/postgis/seed_categories.sql`: slug + osm_key/value. POI без маппинга → категория `other`, но в verify доля `other` **< 35%**.

---

## 6. Правила RGZ и conflation

1. Скачать GPKG (и при необходимости улицы) в `data/rgz/` (gitignore).
2. `ogr2ogr` clip `-clipsrc data/boundary/grad-novi-sad.geojson`.
3. Перепроецировать в 4326.
4. Upsert в `address` с `source='rgz'`.
5. Не удалять OSM-адреса: это другой `source`. Дубли «Булавар 1» с двух источников допустимы.
6. Canonical для продукта позже (этап 3–5) можно предпочесть RGZ, если точка в 8 м от OSM-адреса. На этапе 2 достаточно хранить оба.

**Привязка address → building** (порядок):

1. `ST_Contains(building.geom, address.geom)`
2. иначе ближайшее здание в 3 м (`geography`)
3. иначе `building_id` NULL

Если точка попала в два полигона (наложение OSM) — брать с большей площадью.

**Привязка organization → address / building**:

1. Если у POI есть `addr:housenumber` + street: нормализовать и матчить RGZ/OSM-адрес в 30 м (ILIKE номер + similarity улицы). Не invent fuzzy на 50 правил — простой `lower(unaccent(street))` + номер.
2. Иначе `ST_Contains` здания по точке POI.
3. Иначе здание в 15 м.
4. `geom` организации всегда точка (центроид входа / node).

---

## 7. Структура файлов

```
infra/postgis/
  init.sql                 ← уже есть, PostGIS extension
  schema.sql               ← таблицы этапа 2
  seed_categories.sql
  link_buildings.sql       ← conflation
  fixtures.sql             ← появляется после 1-го импорта
scripts/
  import-osm.sh
  download-rgz.sh
  import-rgz.sh
  stage02-verify.sql
  pipeline-stage02.sh
  pipeline-stage02.ps1
data/
  rgz/                     ← gitignore сырьё
  boundary/                ← без изменений
```

`.gitignore` дополнить: `data/rgz/*`, `!data/rgz/.gitkeep`.

---

## 8. Пайплайн (порядок)

1. `docker compose up -d postgis` (не down `-v`)
2. Применить `schema.sql` + `seed_categories.sql` (идемпотентно)
3. Загрузить `city_boundary` из GeoJSON
4. Импорт OSM → staging → application tables
5. Скачать RGZ, если файла нет
6. Clip + import RGZ
7. `link_buildings.sql`
8. `stage02-verify.sql` — exit 1 при провале любого порога
9. Дописать counts в `data/README.md`
10. Если `fixtures.sql` пустой — выбрать 3 OSM id из центра, закоммитить, прогнать verify ещё раз

Не запускать этап 1 pipeline заново «на всякий случай».

---

## 9. `stage02-verify.sql` — обязательные проверки

Скрипт через `DO $$ ... $$` или `\if` + `psql` variables. Любой провал: `RAISE EXCEPTION`.

Минимум ассертов:

```sql
-- псевдокод порогов, реализовать точно
assert count(building) >= 10000;
assert count(address) FILTER (source='osm') >= 2000;
assert count(address) FILTER (source='rgz') >= 20000;
assert count(organization) >= 1500;
assert rgz_linked_pct >= 50;
assert org_linked_pct >= 40;
assert buildings_with_org >= 300;
assert invalid_buildings = 0;
assert orgs_outside_city = 0;
assert addresses_outside_city = 0;
assert category_other_pct < 35;
-- F1: buildings near trg slobode
assert count(building) near (19.845, 45.255, 80m) >= 5;
assert count(organization) near same >= 1;
-- F2 petrovaradin, F3 liman
```

В конце `NOTICE` с таблицей метрик (чтобы вставить в README).

---

## 10. Типичные поломки

| Симптом | Что делать |
|---|---|
| 200 зданий | Импорт только polygon relations / забыли ways |
| 0 RGZ | Не тот слой GPKG, CRS 0, clip в градусах vs метрах |
| Петроварадин пустой | Clip по имени општине «Нови Сад» — брать полигон 1649672 |
| 5% адресов в зданиях | Забыли `ST_Transform`, lon/lat перепутаны, здания в 3857 |
| Дубли после 2-го запуска | Нет unique / truncate staging |
| osm2pgsql стёр чужие таблицы | Не использовать `--slim --drop` на всю БД вслепую; flex в схему `osm_staging` |
| OOM | Городской PBF ~7 MB, памяти мало не должно быть; если взяли serbia-latest целиком — стоп |
| RGZ 401/логин | Официальный data.gov.rs; не логиниться в чужие кабинеты скриптом. Этап не закрывать |
| Зданий мало, адреса есть | Тогда (и только тогда) рассмотреть GeoSrbija Buildings как fallback — отдельный мини-шаг, не молча |

---

## 11. Как давать задачу AI

Сначала **Opus 5 Thinking** (схема + `link_buildings.sql` + пороги verify). Потом **Composer 2.5** (скрипты скачивания/импорта). Docker чинит **GPT-5.6 Sol**.

Промпт:

```
Сделай Этап 2 строго по Docs/STAGE-02-postgis.md.

Вход: data/osm/novi-sad.osm.pbf, data/boundary/grad-novi-sad.geojson, docker compose postgis.
Не пересобирай PMTiles, не пиши React/API/Meilisearch, не ходи в Overpass, не скрейпи PlanPlus.

Нужны: schema.sql, seed категорий, импорт OSM, RGZ clip полигоном 1649672 (Нови-Сад и Петроварадин), SQL-привязка точка-в-полигоне, scripts/stage02-verify.sql с RAISE EXCEPTION на порогах из §3.3.

Запусти импорт и verify. В конце выведи counts C1–C10 и напиши, какие фикстуры F4 записал.
```

Ревью SQL conflation — снова Opus, не той сессией Composer, что писала импорт.

---

## 12. Оценка времени

| Работа | Ориентир |
|---|---|
| Схема + seed + verify | 1–2 часа |
| OSM import | 10–30 минут на отладку flex/ogr |
| RGZ download + clip | 10–40 минут (файл на всю Сербию тяжёлый) |
| Conflation SQL | 1–2 часа |
| Фикстуры и повторный прогон | 30 минут |

---

## 13. Выход в этап 3

Этап 3 (Meilisearch) стартует только при зелёном verify. Индексировать:

- address: `street + housenumber`
- organization: `name`, category slug, tags
- не индексировать геометрию как текст

API и карта 2ГИС — этапы 4–5, они читают те же таблицы, не вторую схему.
