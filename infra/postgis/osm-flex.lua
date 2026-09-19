-- Zylos Stage 2 - osm2pgsql flex style
-- Docs/STAGE-02-postgis.md section 5 (OSM import rules).
-- Writes ONLY into osm_staging.{building_src,address_src,poi_src}.

local SRID = 4326

local amenity_whitelist = {
  cafe = true, restaurant = true, fast_food = true, pharmacy = true, bank = true,
  school = true, hospital = true, clinic = true, dentists = true, theatre = true,
  cinema = true, library = true, place_of_worship = true, fuel = true, parking = true,
  police = true, post_office = true, townhall = true, community_centre = true,
  bar = true, pub = true, ice_cream = true, kindergarten = true, university = true,
  college = true, doctors = true, veterinary = true,
  -- Named businesses/institutions beyond the §5 example list; load_osm.sql
  -- maps the same values. Unlisted amenity values are still skipped.
  nightclub = true, marketplace = true, driving_school = true, car_rental = true,
  car_wash = true, bureau_de_change = true, courthouse = true, embassy = true,
  fire_station = true, social_facility = true, arts_centre = true,
  food_court = true, internet_cafe = true, coworking_space = true,
  language_school = true, music_school = true, bus_station = true,
  nursing_home = true,
}

local tourism_whitelist = {
  hotel = true, museum = true, attraction = true, gallery = true,
  guest_house = true, hostel = true,
}

local leisure_whitelist = {
  sports_centre = true, stadium = true, fitness_centre = true,
}

local function first_tag(tags, keys)
  for _, key in ipairs(keys) do
    local value = tags[key]
    if value and value ~= '' then
      return value
    end
  end
  return nil
end

local function has_nonempty_name(tags)
  local name = tags.name
  return name ~= nil and name ~= ''
end

local function is_poi(tags)
  if not has_nonempty_name(tags) then
    return false
  end

  -- Street furniture is never a directory entry, even if another key is set.
  if tags.amenity == 'bench' or tags.amenity == 'waste_basket' then
    return false
  end

  -- Spec §5: import if name is set AND any one of the directory keys matches.
  -- Do not return early on an unmatched amenity — a named shop/office may
  -- also carry amenity=atm / amenity=yes.
  if tags.amenity and amenity_whitelist[tags.amenity] then
    return true
  end
  if tags.shop and tags.shop ~= 'vacant' then
    return true
  end
  if tags.office then
    return true
  end
  if tags.tourism and tourism_whitelist[tags.tourism] then
    return true
  end
  if tags.healthcare then
    return true
  end
  if tags.craft then
    return true
  end
  if tags.leisure and leisure_whitelist[tags.leisure] then
    return true
  end

  return false
end

local function area_to_point(geom)
  if not geom then
    return nil
  end
  -- pole_of_inaccessibility is an interior label point (osm2pgsql >= 1.7).
  -- SQL load_osm.sql may still apply ST_PointOnSurface if needed.
  return geom:pole_of_inaccessibility()
end

local building_src = osm2pgsql.define_table{
  name = 'building_src',
  schema = 'osm_staging',
  ids = { type = 'any', id_column = 'osm_id', type_column = 'osm_type' },
  columns = {
    { column = 'name', type = 'text' },
    { column = 'name_sr_latn', type = 'text' },
    { column = 'name_sr', type = 'text' },
    { column = 'building_levels', type = 'text' },
    { column = 'height', type = 'text' },
    { column = 'geom', type = 'geometry', projection = SRID },
  },
}

local address_src = osm2pgsql.define_table{
  name = 'address_src',
  schema = 'osm_staging',
  ids = { type = 'any', id_column = 'osm_id', type_column = 'osm_type' },
  columns = {
    { column = 'street', type = 'text' },
    { column = 'housenumber', type = 'text' },
    { column = 'postcode', type = 'text' },
    { column = 'street_sr_cyrl', type = 'text' },
    { column = 'geom', type = 'point', projection = SRID },
  },
}

local poi_src = osm2pgsql.define_table{
  name = 'poi_src',
  schema = 'osm_staging',
  ids = { type = 'any', id_column = 'osm_id', type_column = 'osm_type' },
  columns = {
    { column = 'name', type = 'text' },
    { column = 'amenity', type = 'text' },
    { column = 'shop', type = 'text' },
    { column = 'office', type = 'text' },
    { column = 'tourism', type = 'text' },
    { column = 'healthcare', type = 'text' },
    { column = 'craft', type = 'text' },
    { column = 'leisure', type = 'text' },
    { column = 'cuisine', type = 'text' },
    { column = 'brand', type = 'text' },
    { column = 'phone', type = 'text' },
    { column = 'website', type = 'text' },
    { column = 'opening_hours', type = 'text' },
    { column = 'level', type = 'text' },
    { column = 'addr_street', type = 'text' },
    { column = 'addr_housenumber', type = 'text' },
    { column = 'geom', type = 'point', projection = SRID },
  },
}

local function insert_building(object, geom)
  if not geom then
    return
  end
  building_src:insert({
    name = first_tag(object.tags, { 'name', 'name:sr-Latn', 'name:sr' }),
    name_sr_latn = object.tags['name:sr-Latn'],
    name_sr = object.tags['name:sr'],
    building_levels = object.tags['building:levels'],
    height = object.tags.height,
    geom = geom,
  })
end

local function insert_address(object, geom)
  if not geom then
    return
  end
  local housenumber = object.tags['addr:housenumber']
  if not housenumber or housenumber == '' then
    return
  end
  address_src:insert({
    street = first_tag(object.tags, { 'addr:street', 'addr:place' }),
    housenumber = housenumber,
    postcode = object.tags['addr:postcode'],
    street_sr_cyrl = object.tags['addr:street:sr'],
    geom = geom,
  })
end

local function insert_poi(object, geom)
  if not geom or not is_poi(object.tags) then
    return
  end
  poi_src:insert({
    name = object.tags.name,
    amenity = object.tags.amenity,
    shop = object.tags.shop,
    office = object.tags.office,
    tourism = object.tags.tourism,
    healthcare = object.tags.healthcare,
    craft = object.tags.craft,
    leisure = object.tags.leisure,
    cuisine = object.tags.cuisine,
    brand = object.tags.brand,
    phone = first_tag(object.tags, { 'phone', 'contact:phone' }),
    website = first_tag(object.tags, { 'website', 'contact:website' }),
    opening_hours = object.tags.opening_hours,
    level = object.tags.level,
    addr_street = object.tags['addr:street'],
    addr_housenumber = object.tags['addr:housenumber'],
    geom = geom,
  })
end

function osm2pgsql.process_node(object)
  insert_address(object, object:as_point())
  insert_poi(object, object:as_point())
end

function osm2pgsql.process_way(object)
  local tags = object.tags
  local is_building = tags.building and tags.building ~= 'no'
  local has_housenumber = tags['addr:housenumber'] and tags['addr:housenumber'] ~= ''
  local poi = is_poi(tags)

  if is_building and object.is_closed then
    insert_building(object, object:as_polygon())
  end

  if has_housenumber then
    if object.is_closed then
      insert_address(object, area_to_point(object:as_polygon()))
    else
      insert_address(object, object:as_point())
    end
  end

  if poi then
    if object.is_closed then
      insert_poi(object, area_to_point(object:as_polygon()))
    else
      insert_poi(object, object:as_point())
    end
  end
end

function osm2pgsql.process_relation(object)
  local tags = object.tags
  local is_building = tags.building and tags.building ~= 'no'
  local has_housenumber = tags['addr:housenumber'] and tags['addr:housenumber'] ~= ''
  local poi = is_poi(tags)
  local mp = object:as_multipolygon()

  if is_building and mp then
    insert_building(object, mp)
  end

  if has_housenumber and mp then
    insert_address(object, area_to_point(mp))
  end

  if poi and mp then
    insert_poi(object, area_to_point(mp))
  end
end
