# Zylos Android — дизайн (ориентир 2GIS)

Общие **UI/UX-требования** нативного клиента `apps/android`. Продуктовый ориентир — **2GIS (Android)**: карта на весь экран, **строка поиска снизу**, карточка объекта выезжает **над** поиском, быстрые жесты, минимум «лишнего chrome».

> **Веб** (`apps/web`) держит поиск **сверху** — референс API и логики, не layout телефона.

Связанные документы:

- [MOBILE.md](../MOBILE.md) — функциональная спека Android
- [PERFORMANCE.md](../PERFORMANCE.md) — пороги U1–U5 (анимации, отзывчивость)
- [MOBILE-POI-ZOOM.md](MOBILE-POI-ZOOM.md) — плотность POI на карте
- `apps/web/src/theme/tokens.css` — референс токенов (веб не развиваем, но палитра общая)
- `apps/android/app/src/main/res/values/colors.xml` — канонические цвета Android v1

**Не копируем:** логотип, торговую марку, скриншоты и фирменные иллюстрации 2GIS. Берём **паттерны компоновки и поведения**, не брендбук конкурента.

---

## 1. Принципы

| Принцип | Как в 2GIS | В Zylos |
|---|---|---|
| **Карта — главный экран** | Нет «домашней» ленты; сразу карта | Один `MapActivity`, без drawer/tabs в v1 |
| **Контент снизу** | Карточка выезжает снизу, карта остаётся видимой | `BottomSheetBehavior`, не full-screen modal |
| **Поиск всегда доступен** | Нижняя панель / строка внизу экрана | Search card **снизу**, над system nav bar; dropdown **вверх** |
| **Минимум шагов** | Тап → результат; поиск → flyTo + карточка | Не добавлять промежуточные экраны без нужды |
| **Скорость = дизайн** | Анимации короткие, спиннеры точечные | U1–U3, debounce 150 ms — [PERFORMANCE.md](../PERFORMANCE.md) |
| **Светлая тема v1** | Светлая подложка, белые карточки | Тёмная тема — backlog |
| **sr-Latn UI** | Локальный язык интерфейса | Адреса из API — sr-Latn + sr-Cyrl в данных |

---

## 2. Компоновка экрана

Как в **2GIS Android**: нижний «док» с поиском; карточка объекта — `BottomSheet` **над** строкой поиска, не перекрывая её.

![Browse idle — карта на весь экран, search снизу](map-screen.svg)

Визуальный референс: [map-screen.svg](map-screen.svg) (idle). Полный набор кейсов — [screens/README.md](screens/README.md).

```text
┌─────────────────────────────────────┐
│  [ safe area / status bar ]         │
│                                     │
│           MapView (fullscreen)      │
│                                     │
│  [ ± zoom ] [ compass ]             │  ← controls справа, над search dock
│                                     │
│  ┌─ dropdown (optional) ───────┐    │  ← растёт ВВЕРХ от search (z=4)
│  │ hit / history rows          │    │
│  └─────────────────────────────┘    │
│  ╭─────────────────────────────╮    │  ← object sheet (z=5), над search
│  │ ─── handle                  │    │
│  │ Title / org list / fields   │    │
│  ╰─────────────────────────────╯    │
│  ┌─────────────────────────────┐    │  ← search chrome (z=6), всегда снизу
│  │  🔍  Pretraga…            × │    │
│  └─────────────────────────────┘    │
│  [ safe area / nav bar ]            │
└─────────────────────────────────────┘
```

| Зона | Правило |
|---|---|
| **Карта** | `match_parent`, под всем UI-слоем |
| **Search dock** | `layout_gravity=bottom`; отступы **12 dp** по бокам; **marginBottom** = system nav inset |
| **Search bar** | Закреплён **внизу**, не уезжает при открытии sheet; z выше карты |
| **Dropdown** | **Над** search card, max height **min(360 dp, 50 vh − search dock)**; скролл внутри |
| **Object sheet** | `BottomSheetBehavior`; `layout_marginBottom` = search dock + **8 dp** (`sheet_search_gap`); **3 шага** — [object-card/README.md](object-card/README.md) |
| **Snackbar / toast** | Над search dock + peek sheet |
| **Атрибуция OSM** | Нижний левый угол; сдвигается вверх на search dock + peek sheet |
| **My location FAB** | Правый край, **над** search dock (не под строкой поиска) |

Portrait — основной. Landscape: карта fullscreen, sheet **сбоку** (backlog v1.1; v1 — portrait only acceptable).

---

## 3. Design tokens (v1)

Единый источник для Android: `colors.xml`, `dimens.xml` (создать при отсутствии), `themes.xml`. Значения **совпадают** с `tokens.css`.

### 3.1. Цвета

| Token | Hex | Использование |
|---|---|---|
| `map_bg` | `#EFEBE3` | Фон за картой, splash |
| `paper` | `#FFFFFF` | Search card, sheet, dropdown |
| `ink` | `#1F1F1F` | Основной текст |
| `muted` | `#6A655C` | Подзаголовки, placeholder, вторичный текст |
| `line` | `#E4DDD3` | Разделители списков |
| `accent` | `#00B341` | CTA, ссылки, подсветка здания, launcher |
| `accent_soft` | `#E7F8EC` | Hover/selected row в поиске |
| `addr` | `#5B6B7A` | Badge «Adresa» |
| `danger` | `#B42318` | Ошибки |
| `sheet_handle` | `#D0CBC3` | Drag handle |
| `highlight_fill` | `#00B341` @ ~35% opacity | Fill выбранного здания |
| `highlight_outline` | `#008A32` | Контур здания |
| `toast_bg` | `#2B2B2B` | Snackbar фон |

**Запрещено** вводить вторую акцентную палитру на экране (Material `colorPrimary` = `accent`).

### 3.2. Радиусы и elevation

| Token | Значение | Элемент |
|---|---|---|
| `radius_search` | **14 dp** | Search card |
| `radius_dropdown` | **12 dp** | Dropdown списка |
| `radius_sheet` | **16 dp** (top corners) | Bottom sheet |
| `radius_pill` | **999 dp** | Toast, kind-badge |
| `elevation_search` | **8 dp** | Search + dropdown (тень как `--shadow-search`) |
| `elevation_sheet` | **12 dp** | Bottom sheet |

### 3.3. Размеры и отступы

| Token | Значение | Элемент |
|---|---|---|
| `touch_min` | **48 dp** | Минимальная зона тапа (кнопки, строки списка) |
| `search_height` | **48 dp** | Высота поля поиска |
| `search_dock_height` | **60 dp** | Search card + нижний margin (sheet `marginBottom`) |
| `sheet_search_gap` | **8 dp** | Зазор sheet над search dock |
| `sheet_step1_height` | **72 dp** | Шаг 1 — minimal |
| `sheet_step2_ratio` | **0.5** | Шаг 2 — half экрана |
| `sheet_step3_ratio` | **1.0** | Шаг 3 — full (до status bar) |
| `sheet_handle_w` | **36 dp** × **4 dp** | Drag handle |
| `margin_screen` | **12–16 dp** | Края экрана |
| `spacing_row` | **8–12 dp** | Padding строк списка |

### 3.4. Типографика

| Роль | Size | Weight | Пример |
|---|---|---|---|
| Sheet title | **16 sp** | SemiBold (650) | «Bulevar oslobođenja 47» |
| Body / org name | **14 sp** | SemiBold (600) | «Apoteka Benu» |
| Subtitle / category | **12 sp** | Regular | «Apoteka», «Adresa» |
| Search hit label | **15 sp** | Regular | Строка в dropdown |
| Caption / address list | **12 sp** | Regular | Адреса здания |
| Org field label | **13 sp** | Medium | «Telefon:» |
| Toast | **13 sp** | Regular | Snackbar |

Шрифт v1: **system sans** (`Roboto` / `Noto Sans`). Кастомный шрифт — backlog.

---

## 4. Компоненты

### 4.1. Search bar

**Позиция:** низ экрана (2GIS Android). Логика debounce/stale — как `SearchBar.tsx`; **layout — только mobile**, не копировать top-search веба.

- Иконка лупы слева, поле `inputType=text`, `imeOptions=actionSearch`
- Кнопка **×** при `query.length > 0`; `clear` не триггерит map click
- Spinner справа только при `loading && q.length ≥ 2`
- Placeholder: **«Pretraga…»**
- Тень + белый фон; не Material filled TextField с underline
- Debounce **150 ms** — не блокировать главный поток (U1)

### 4.2. Search dropdown

- Строка hit: **kind-badge** (ORG / ADR) + `label` + subtitle (категория или «Adresa»)
- Разделитель `line` между строками; tap highlight — `accent_soft`
- Пусто: «Ništa nije pronađeno»; ошибка сети — `danger`
- Секция **«Nedavno»** при фокусе на пустом поле (Room history)
- Dropdown **не** BottomSheet; overlay **над** search card, список растёт **вверх**
- При открытой клавиатуре — search dock прижат к `imeInsets`; dropdown между клавиатурой и полем

### 4.3. Bottom sheet (карточка объекта)

Полная спека поведения и макеты: **[object-card/README.md](object-card/README.md)**.

Material `BottomSheetBehavior` с **тремя якорями** (шагами):

| Шаг | Высота | Содержимое |
|---|---|---|
| **1 — minimal** | **72 dp** | Кратко: здание — **адрес**; org — **название + тип**. **×** справа |
| **2 — half** | **50%** контентной зоны | Полная карточка; скролл body при переполнении |
| **3 — full** | **100%** контентной зоны | То же; максимум места под длинный контент |

| Token | Значение |
|---|---|
| `sheet_search_gap` | **8 dp** — зазор между низом sheet и search dock |
| `sheet_step1_height` | **72 dp** |
| `sheet_step2_ratio` | **0.5** |
| `sheet_step3_ratio` | **1.0** (до status bar) |

- Нижний край sheet **всегда** на `search_dock_height + sheet_search_gap` от низа экрана; search dock **не скрывается**
- При открытии (тап по зданию / выбор hit) — dropdown и клавиатура закрываются; карточка стартует с **шага 1**
- Handle сверху (`sheet_handle`); **×** в header (шаги 2–3 — как раньше; шаг 1 — inline справа)
- Свайп **вверх** / тап по handle → +1 шаг (макс. 3); свайп **вниз** → −1 шаг (мин. 1)
- **×** — закрыть; снять подсветку / маркер. С шага 1 свайп вниз **не** закрывает карточку
- Анимация смены шага / open / close **≤ 250 ms** (U2)

### 4.4. Карточка здания

- Заголовок: первый address label или «Zgrada»
- Список org: имя **14 sp semi** + категория **12 sp muted**
- Строка org — full width, min height **48 dp**
- Пустой список: «Nema organizacija u zgradi»

### 4.5. Карточка организации

- Имя + категория в header sheet
- Поля label/value в две колонки (label **92 dp** — как `org-card__row` в CSS)
- Телефон / сайт — кликабельные (`accent`, `ACTION_VIEW`)
- Null-поля **не** показывать (не «—»)

### 4.6. Карта — controls

| Control | Размещение | Стиль |
|---|---|---|
| Zoom ± | Правый край, вертикально | Белые круги 48 dp, elevation 4 |
| Compass | Над zoom | Появляется при bearing ≠ 0 |
| My location | Правый край, над search dock | FAB 48 dp, `accent` icon |
| Attribution | Нижний левый, над search dock | Мелкий текст |

Controls и attribution сдвигаются вверх на `search_dock_height` + `peekHeight` sheet.

### 4.7. Feedback

| Ситуация | Паттерн |
|---|---|
| 404 здание | Haptic + Snackbar «Nema zgrade na ovoj tački» |
| 422 вне города | Snackbar «Van grada Novi Sad» |
| Сеть | Snackbar «Nema veze sa serverom» |
| Loading sheet | «Učitavam…» в body, не blocking dialog |

Toast/Snackbar: pill, `toast_bg`, **2.5 s** auto-dismiss.

---

## 5. Карта (визуал)

| Элемент | Правило |
|---|---|
| **Подложка** | Светлая MVT/MBTiles; `fill-extrusion` зданий — как `infra/preview/style.json` |
| **Выбранное здание** | GeoJSON PostGIS: fill `highlight_fill` + outline `highlight_outline`; не из MVT |
| **Маркер поиска** | Source `selected-marker`; отличим от org-pins |
| **Org-pins browse** | Круг/иконка категории; подписи только z ≥ 17 — [MOBILE-POI-ZOOM.md](MOBILE-POI-ZOOM.md) |
| **Маршрут ОТ** | Автобус — сплошная цветная линия + номер; пеший участок — **dash** |
| **FlyTo** | duration **800 ms**, zoom ≥ **16** при выборе hit |

Не перегружать карту: лимиты B5, rank-фильтры POI — обязательны для «ощущения 2GIS».

---

## 6. Иконки категорий

- Slug из `category_slug` → монохромная иконка **24 dp** в списках, **20 dp** on pin
- Набор v1: pharmacy, cafe, restaurant, bank, shop, hospital, school, bus_stop — минимум
- Fallback: generic «pin» / первая буква категории в circle
- Хранить: `apps/android/app/src/main/res/drawable/` или vector asset set

---

## 7. Доступность

- TalkBack: `contentDescription` на search, zoom, my location, close, каждая org row
- Touch target **≥ 48 dp** — даже для × и handle
- Contrast текста на `paper` **≥ WCAG AA** (`ink` on `paper` ✓)
- Не полагаться только на цвет kind-badge — есть текст «ORG» / «ADR»

---

## 8. Локализация UI (sr-Latn v1)

Все строки — `strings.xml`. Таблица ключевых текстов:

| Ключ | Текст |
|---|---|
| search_hint | Pretraga… |
| nema_zgrade | Nema zgrade na ovoj tački |
| van_grada | Van grada Novi Sad |
| nema_veze | Nema veze sa serverom |
| nista_nadjeno | Ništa nije pronađeno |
| pretraga_nedostupna | Pretraga privremeno nedostupna |
| nedavno | Nedavno |
| adresa | Adresa |
| organizacija | Organizacija |

ru/en — после v1; не хардкодить в Kotlin.

---

## 9. Чеклист приёмки «похоже на 2GIS»

Ручная проверка на Phone-mid (1080×2340, 4 GB) после каждого UI-этапа:

- [ ] Карта открывается сразу, без onboarding
- [ ] Search card **внизу** экрана, над nav bar inset; dropdown открывается вверх
- [ ] Тап по дому → sheet снизу ≤ 350 ms (B4), контур зелёный
- [ ] Поиск «apotek» → dropdown ≤ 15 строк, flyTo + sheet
- [ ] Sheet: 3 шага (minimal / half / full); свайп вверх/вниз; × закрывает; зазор 8 dp над search
- [ ] Org card: телефон и сайт кликабельны
- [ ] Airplane Mode: карта жива, search/sheet показывают «Nema veze…»
- [ ] Нет Material default purple/teal theme
- [ ] FPS pan/zoom ≥ 30 (T4) с открытым peek sheet

---

## 10. Антипаттерны (не делать)

- Search bar **сверху** на Android (это паттерн веба, не 2GIS mobile)
- Tab bar / navigation drawer для v1
- Full-screen Activity на каждую карточку org
- AlertDialog для ошибок сети вместо Snackbar
- Список org в отдельном RecyclerView **вне** sheet
- Google Maps SDK widgets / Mapbox UI
- Тяжёлые blur/glass эффекты на search (GPU карты важнее)
- Разные цвета подсветки здания в web и Android
- Копирование фирменных иллюстраций 2GIS

---

## 11. Где править код

| Артеfact | Путь |
|---|---|
| Цвета | `apps/android/app/src/main/res/values/colors.xml` |
| Размеры | `apps/android/app/src/main/res/values/dimens.xml` |
| Тема | `apps/android/app/src/main/res/values/themes.xml` |
| Layout map + sheet | `apps/android/app/src/main/res/layout/activity_map.xml` |
| Search layouts | `item_search_hit.xml`, `item_search_history.xml` |
| CSS-референс | `apps/web/src/theme/tokens.css` (read-only для агента) |

Новый UI-компонент **обязан** использовать токены из §3, не inline `#hex` в Kotlin/XML.

---

## 12. Связь с этапами

| Этап | UI-объём |
|---|---|
| [STAGE-10-android.md](../STAGE-10-android.md) | Карта, search chrome-placeholder, атрибуция |
| [STAGE-11-android-building.md](../STAGE-11-android-building.md) | Bottom sheet building/org |
| [STAGE-12-android-search.md](../STAGE-12-android-search.md) | Search bar + dropdown + marker |
| [STAGE-13-android-poi.md](../STAGE-13-android-poi.md) | Org-pins, Search Multi |
| Transit (5.2) | Route sheet, линии на карте |

При расхождении этаповой спеки и этого файла — **этот файл** задаёт визуал; этап — поведение и API.

---

*Документ v1.1. Ориентир 2GIS Android (search снизу); палитра — `colors.xml` / `tokens.css`. Веб: search сверху, только референс логики.*
