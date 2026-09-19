-- =============================================================================
-- Zylos - Stage 2 - category dictionary
-- Docs/STAGE-02-postgis.md section 5 ("Организации") and DoD S4.
--
-- Idempotent: INSERT ... ON CONFLICT (slug) DO UPDATE. Safe to re-run.
--   psql -v ON_ERROR_STOP=1 -U zylos -d zylos -f infra/postgis/seed_categories.sql
-- Run after infra/postgis/schema.sql.
--
-- WILDCARD CONVENTION (importers must implement it):
--   osm_value = '*'  -> catch-all for that key. shop=*, office=*, healthcare=*
--                       and craft=* have thousands of long-tail values; the
--                       importer first looks up the exact (key, value) pair and
--                       falls back to (key, '*') when there is no exact row.
--   osm_key = '*' AND osm_value = '*'  -> slug `other`, the last resort for a
--                       named POI whose key is not mapped at all.
--   Resolution order in the importer:
--       1. exact   (amenity, cafe) -> slug cafe
--       2. per-key (shop,    '*')  -> slug shop
--       3. global  ('*',     '*')  -> slug other
--   DoD: the share of organizations resolved to `other` must stay below 35%
--   (scripts/stage02-verify.sql), so keep adding explicit rows instead of
--   letting the catch-alls absorb everything.
--
-- Key priority when a POI carries several of them (importer contract):
--   amenity > shop > healthcare > tourism > leisure > craft > office
--
-- name_sr is Serbian Latin (sr-Latn), the primary product language.
-- icon values are maki icon names.
-- =============================================================================

INSERT INTO public.category (slug, name_sr, name_ru, name_en, osm_key, osm_value, icon)
VALUES
  -- ---------------------------------------------------------------- amenity --
  ('cafe',             'Kafić',                      'Кафе',                    'Cafe',                'amenity',    'cafe',             'cafe'),
  ('restaurant',       'Restoran',                   'Ресторан',                'Restaurant',          'amenity',    'restaurant',       'restaurant'),
  ('fast_food',        'Brza hrana',                 'Фастфуд',                 'Fast food',           'amenity',    'fast_food',        'fast-food'),
  ('bar',              'Bar',                        'Бар',                     'Bar',                 'amenity',    'bar',              'bar'),
  ('pub',              'Pab',                        'Паб',                     'Pub',                 'amenity',    'pub',              'beer'),
  ('ice_cream',        'Sladoledžinica',             'Мороженое',               'Ice cream',           'amenity',    'ice_cream',        'ice-cream'),
  ('pharmacy',         'Apoteka',                    'Аптека',                  'Pharmacy',            'amenity',    'pharmacy',         'pharmacy'),
  ('bank',             'Banka',                      'Банк',                    'Bank',                'amenity',    'bank',             'bank'),
  ('school',           'Škola',                      'Школа',                   'School',              'amenity',    'school',           'school'),
  ('kindergarten',     'Vrtić',                      'Детский сад',             'Kindergarten',        'amenity',    'kindergarten',     'playground'),
  ('university',       'Univerzitet',                'Университет',             'University',          'amenity',    'university',       'college'),
  ('college',          'Viša škola',                 'Колледж',                 'College',             'amenity',    'college',          'college'),
  ('hospital',         'Bolnica',                    'Больница',                'Hospital',            'amenity',    'hospital',         'hospital'),
  ('clinic',           'Klinika',                    'Клиника',                 'Clinic',              'amenity',    'clinic',           'doctor'),
  ('doctors',          'Ordinacija',                 'Врачебный кабинет',       'Doctor''s office',    'amenity',    'doctors',          'doctor'),
  ('dentist',          'Stomatolog',                 'Стоматология',            'Dentist',             'amenity',    'dentists',         'dentist'),
  ('veterinary',       'Veterinarska stanica',       'Ветеринария',             'Veterinary',          'amenity',    'veterinary',       'veterinary'),
  ('theatre',          'Pozorište',                  'Театр',                   'Theatre',             'amenity',    'theatre',          'theatre'),
  ('cinema',           'Bioskop',                    'Кинотеатр',               'Cinema',              'amenity',    'cinema',           'cinema'),
  ('library',          'Biblioteka',                 'Библиотека',              'Library',             'amenity',    'library',          'library'),
  ('place_of_worship', 'Bogomolja',                  'Храм',                    'Place of worship',    'amenity',    'place_of_worship', 'place-of-worship'),
  ('fuel',             'Benzinska stanica',          'АЗС',                     'Fuel station',        'amenity',    'fuel',             'fuel'),
  ('parking',          'Parking',                    'Парковка',                'Parking',             'amenity',    'parking',          'parking'),
  ('police',           'Policija',                   'Полиция',                 'Police',              'amenity',    'police',           'police'),
  ('post_office',      'Pošta',                      'Почта',                   'Post office',         'amenity',    'post_office',      'post'),
  ('townhall',         'Gradska kuća',               'Мэрия',                   'Town hall',           'amenity',    'townhall',         'town-hall'),
  ('community_centre', 'Društveni centar',           'Общественный центр',      'Community centre',    'amenity',    'community_centre', 'community'),
  -- amenity values outside the STAGE-02 section 5 example list that are still
  -- unmistakably businesses/institutions. They are mapped explicitly (instead
  -- of being swept into `other`) because load_osm.sql imports exactly the
  -- amenity values that appear here - see the allow-list in that file.
  ('nightclub',        'Noćni klub',                 'Ночной клуб',             'Nightclub',           'amenity',    'nightclub',        'bar'),
  ('marketplace',      'Pijaca',                     'Рынок',                   'Marketplace',         'amenity',    'marketplace',      'shop'),
  ('food_court',       'Food court',                 'Фудкорт',                 'Food court',          'amenity',    'food_court',       'restaurant'),
  ('internet_cafe',    'Internet kafe',              'Интернет-кафе',           'Internet cafe',       'amenity',    'internet_cafe',    'cafe'),
  ('coworking_space',  'Kovorking',                  'Коворкинг',               'Coworking space',     'amenity',    'coworking_space',  'suitcase'),
  ('driving_school',   'Auto škola',                 'Автошкола',               'Driving school',      'amenity',    'driving_school',   'car'),
  ('language_school',  'Škola jezika',               'Языковая школа',          'Language school',     'amenity',    'language_school',  'school'),
  ('music_school',     'Muzička škola',              'Музыкальная школа',       'Music school',        'amenity',    'music_school',     'school'),
  ('car_rental',       'Rent a car',                 'Аренда авто',             'Car rental',          'amenity',    'car_rental',       'car-rental'),
  ('car_wash',         'Auto perionica',             'Автомойка',               'Car wash',            'amenity',    'car_wash',         'car'),
  ('bureau_de_change', 'Menjačnica',                 'Обмен валюты',            'Bureau de change',    'amenity',    'bureau_de_change', 'bank'),
  ('courthouse',       'Sud',                        'Суд',                     'Courthouse',          'amenity',    'courthouse',       'town-hall'),
  ('embassy',          'Ambasada',                   'Посольство',              'Embassy',             'amenity',    'embassy',          'town-hall'),
  ('fire_station',     'Vatrogasna stanica',         'Пожарная часть',          'Fire station',        'amenity',    'fire_station',     'fire-station'),
  ('social_facility',  'Socijalna ustanova',         'Соцучреждение',           'Social facility',     'amenity',    'social_facility',  'community'),
  ('nursing_home',     'Dom za stare',               'Дом престарелых',         'Nursing home',        'amenity',    'nursing_home',     'community'),
  ('arts_centre',      'Kulturni centar',            'Центр искусств',          'Arts centre',         'amenity',    'arts_centre',      'art-gallery'),
  ('bus_station',      'Autobuska stanica',          'Автовокзал',              'Bus station',         'amenity',    'bus_station',      'bus'),

  -- ------------------------------------------------------------------- shop --
  -- shop=* catch-all. Required slug (DoD S4).
  ('shop',             'Prodavnica',                 'Магазин',                 'Shop',                'shop',       '*',                'shop'),
  ('supermarket',      'Supermarket',                'Супермаркет',             'Supermarket',         'shop',       'supermarket',      'grocery'),
  ('convenience',      'Prodavnica prehrane',        'Продуктовый магазин',     'Convenience store',   'shop',       'convenience',      'grocery'),
  ('bakery',           'Pekara',                     'Пекарня',                 'Bakery',              'shop',       'bakery',           'bakery'),
  ('butcher',          'Mesara',                     'Мясная лавка',            'Butcher',             'shop',       'butcher',          'slaughterhouse'),
  ('greengrocer',      'Piljarnica',                 'Овощи и фрукты',          'Greengrocer',         'shop',       'greengrocer',      'grocery'),
  ('confectionery',    'Poslastičarnica',            'Кондитерская',            'Confectionery',       'shop',       'confectionery',    'confectionery'),
  ('alcohol',          'Prodavnica pića',            'Алкогольный магазин',     'Alcohol shop',        'shop',       'alcohol',          'alcohol-shop'),
  ('kiosk',            'Kiosk',                      'Киоск',                   'Kiosk',               'shop',       'kiosk',            'shop'),
  ('clothes',          'Odeća',                      'Одежда',                  'Clothing store',      'shop',       'clothes',          'clothing-store'),
  ('shoes',            'Obuća',                      'Обувь',                   'Shoe shop',           'shop',       'shoes',            'shoe'),
  ('jewelry',          'Zlatara',                    'Ювелирный магазин',       'Jewelry',             'shop',       'jewelry',          'jewelry-store'),
  ('hairdresser',      'Frizerski salon',            'Парикмахерская',          'Hairdresser',         'shop',       'hairdresser',      'hairdresser'),
  ('beauty',           'Kozmetički salon',           'Салон красоты',           'Beauty salon',        'shop',       'beauty',           'hairdresser'),
  ('electronics',      'Elektronika',                'Электроника',             'Electronics',         'shop',       'electronics',      'shop'),
  ('mobile_phone',     'Mobilni telefoni',           'Мобильные телефоны',      'Mobile phones',       'shop',       'mobile_phone',     'mobile-phone'),
  ('computer',         'Računari',                   'Компьютеры',              'Computer shop',       'shop',       'computer',         'shop'),
  ('furniture',        'Nameštaj',                   'Мебель',                  'Furniture',           'shop',       'furniture',        'furniture'),
  ('hardware',         'Gvožđara',                   'Хозяйственные товары',    'Hardware',            'shop',       'hardware',         'hardware'),
  ('doityourself',     'Građevinski materijal',      'Стройматериалы',          'DIY store',           'shop',       'doityourself',     'hardware'),
  ('florist',          'Cvećara',                    'Цветы',                   'Florist',             'shop',       'florist',          'florist'),
  ('books',            'Knjižara',                   'Книжный магазин',         'Bookshop',            'shop',       'books',            'library'),
  ('stationery',       'Papirnica',                  'Канцтовары',              'Stationery',          'shop',       'stationery',       'shop'),
  ('optician',         'Optika',                     'Оптика',                  'Optician',            'shop',       'optician',         'optician'),
  ('chemist',          'Drogerija',                  'Бытовая химия',           'Chemist',             'shop',       'chemist',          'pharmacy'),
  ('sports',           'Sportska oprema',            'Спорттовары',             'Sports shop',         'shop',       'sports',           'shop'),
  ('toys',             'Igračke',                    'Игрушки',                 'Toy shop',            'shop',       'toys',             'toy'),
  ('pet',              'Prodavnica za ljubimce',     'Зоомагазин',              'Pet shop',            'shop',       'pet',              'veterinary'),
  ('bicycle',          'Biciklistička radnja',       'Велосипеды',              'Bicycle shop',        'shop',       'bicycle',          'bicycle'),
  ('car',              'Auto salon',                 'Автосалон',               'Car dealership',      'shop',       'car',              'car'),
  ('car_repair',       'Auto servis',                'Автосервис',              'Car repair',          'shop',       'car_repair',       'car-repair'),
  ('mall',             'Tržni centar',               'Торговый центр',          'Shopping mall',       'shop',       'mall',             'shop'),
  ('department_store', 'Robna kuća',                 'Универмаг',               'Department store',    'shop',       'department_store', 'shop'),
  ('laundry',          'Perionica veša',             'Прачечная',               'Laundry',             'shop',       'laundry',          'laundry'),

  -- ----------------------------------------------------------------- office --
  ('office',           'Kancelarija',                'Офис',                    'Office',              'office',     '*',                'suitcase'),
  ('office_company',   'Firma',                      'Компания',                'Company office',      'office',     'company',          'suitcase'),
  ('office_lawyer',    'Advokatska kancelarija',     'Адвокат',                 'Lawyer',              'office',     'lawyer',           'suitcase'),
  ('office_insurance', 'Osiguranje',                 'Страхование',             'Insurance',           'office',     'insurance',        'suitcase'),
  ('office_estate',    'Agencija za nekretnine',     'Агентство недвижимости',  'Estate agent',        'office',     'estate_agent',     'suitcase'),
  ('office_government','Državna ustanova',           'Госучреждение',           'Government office',   'office',     'government',       'town-hall'),

  -- ---------------------------------------------------------------- tourism --
  ('hotel',            'Hotel',                      'Отель',                   'Hotel',               'tourism',    'hotel',            'lodging'),
  ('hostel',           'Hostel',                     'Хостел',                  'Hostel',              'tourism',    'hostel',           'lodging'),
  ('guest_house',      'Pansion',                    'Гостевой дом',            'Guest house',         'tourism',    'guest_house',      'lodging'),
  ('museum',           'Muzej',                      'Музей',                   'Museum',              'tourism',    'museum',           'museum'),
  ('gallery',          'Galerija',                   'Галерея',                 'Art gallery',         'tourism',    'gallery',          'art-gallery'),
  ('attraction',       'Znamenitost',                'Достопримечательность',   'Attraction',          'tourism',    'attraction',       'attraction'),

  -- ------------------------------------------------------------- healthcare --
  ('healthcare',       'Zdravstvena ustanova',       'Медицинское учреждение',  'Healthcare',          'healthcare', '*',                'heart'),
  ('healthcare_lab',   'Laboratorija',               'Лаборатория',             'Medical laboratory',  'healthcare', 'laboratory',       'heart'),
  ('physiotherapist',  'Fizioterapeut',              'Физиотерапия',            'Physiotherapist',     'healthcare', 'physiotherapist',  'heart'),

  -- ------------------------------------------------------------------ craft --
  ('craft',            'Zanatska radnja',            'Мастерская',              'Craft workshop',      'craft',      '*',                'hardware'),

  -- ---------------------------------------------------------------- leisure --
  ('sports_centre',    'Sportski centar',            'Спортивный центр',        'Sports centre',       'leisure',    'sports_centre',    'fitness-centre'),
  ('stadium',          'Stadion',                    'Стадион',                 'Stadium',             'leisure',    'stadium',          'stadium'),
  ('fitness_centre',   'Teretana',                   'Фитнес-центр',            'Fitness centre',      'leisure',    'fitness_centre',   'fitness-centre'),

  -- ------------------------------------------------------------------ other --
  ('other',            'Ostalo',                     'Прочее',                  'Other',               '*',          '*',                'marker')

ON CONFLICT (slug) DO UPDATE
SET name_sr   = EXCLUDED.name_sr,
    name_ru   = EXCLUDED.name_ru,
    name_en   = EXCLUDED.name_en,
    osm_key   = EXCLUDED.osm_key,
    osm_value = EXCLUDED.osm_value,
    icon      = EXCLUDED.icon;


-- -----------------------------------------------------------------------------
-- Self-check (DoD S4): >= 30 rows and the mandatory slugs are present.
-- -----------------------------------------------------------------------------
DO $seed_check$
DECLARE
  n_total   int;
  required  text[] := ARRAY['cafe', 'restaurant', 'pharmacy', 'bank', 'school',
                            'hospital', 'shop', 'other'];
  missing   text[];
BEGIN
  SELECT count(*) INTO n_total FROM public.category;

  SELECT array_agg(s ORDER BY s) INTO missing
  FROM unnest(required) AS s
  WHERE NOT EXISTS (SELECT 1 FROM public.category c WHERE c.slug = s);

  IF n_total < 30 THEN
    RAISE EXCEPTION 'seed_categories.sql: category has % rows, DoD S4 requires >= 30', n_total;
  END IF;

  IF missing IS NOT NULL THEN
    RAISE EXCEPTION 'seed_categories.sql: required slugs missing: %', array_to_string(missing, ', ');
  END IF;

  RAISE NOTICE 'seed_categories.sql applied: % categories, all required slugs present', n_total;
END
$seed_check$;
