package rs.zylos.novisad.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiDtoContractTest {
    @Test
    fun searchHitFieldsMatchFastifyJson() {
        val hit = SearchHit(
            id = "n1",
            kind = SearchKind.organization,
            label = "Apoteka",
            lat = 45.25,
            lon = 19.84,
            building_id = "b1",
            name = "Apoteka",
            category_slug = "pharmacy",
        )
        assertEquals("organization", hit.kind.name)
        assertEquals("b1", hit.building_id)
        assertEquals("pharmacy", hit.category_slug)
    }

    @Test
    fun moshiParsesSearchResponseContract() {
        val json = """
            {
              "query": "apotek",
              "hits": [{
                "id": "org:osm:n1",
                "kind": "organization",
                "label": "Apoteka Benu",
                "lat": 45.255,
                "lon": 19.845,
                "building_id": "550e8400-e29b-41d4-a716-446655440000",
                "name": "Apoteka Benu",
                "category_slug": "pharmacy"
              }, {
                "id": "addr:rgz:1",
                "kind": "address",
                "label": "Bulevar oslobođenja 47",
                "lat": 45.25,
                "lon": 19.84,
                "building_id": null
              }],
              "processingTimeMs": 12
            }
        """.trimIndent()
        val body = ApiJson.moshi.adapter(SearchResponse::class.java).fromJson(json)!!
        assertEquals("apotek", body.query)
        assertEquals(2, body.hits.size)
        assertEquals(SearchKind.organization, body.hits[0].kind)
        assertEquals("pharmacy", body.hits[0].category_slug)
        assertEquals(SearchKind.address, body.hits[1].kind)
        assertNull(body.hits[1].building_id)
        assertEquals(12, body.processingTimeMs)
    }

    @Test
    fun buildingAndOrgFieldNamesStaySnakeCase() {
        val building = BuildingAtResponse(id = "id", label = "label")
        val org = OrgPin(id = "id", name = "name", category_slug = "cafe", lon = 19.84, lat = 45.25)
        assertEquals("id", building.id)
        assertEquals("cafe", org.category_slug)
    }

    @Test
    fun moshiParsesBuildingAtAndDetailJson() {
        val at = ApiJson.moshi.adapter(BuildingAtResponse::class.java).fromJson(
            """{"id":"550e8400-e29b-41d4-a716-446655440000","label":"Bulevar oslobođenja 12"}""",
        )!!
        assertEquals("550e8400-e29b-41d4-a716-446655440000", at.id)
        assertEquals("Bulevar oslobođenja 12", at.label)

        val detailJson = """
            {
              "id": "550e8400-e29b-41d4-a716-446655440000",
              "name": null,
              "centroid": { "lon": 19.845, "lat": 45.255 },
              "geometry": {
                "type": "Polygon",
                "coordinates": [[[19.84,45.25],[19.85,45.25],[19.85,45.26],[19.84,45.26],[19.84,45.25]]]
              },
              "addresses": [{
                "id": "addr:rgz:123",
                "label": "Bulevar oslobođenja 12",
                "street": "Bulevar oslobođenja",
                "housenumber": "12",
                "source": "rgz"
              }],
              "organizations": [{
                "id": "org:osm:n123",
                "name": "Apoteka Benu",
                "category_slug": "pharmacy",
                "category_name": "Apoteka",
                "floor": null
              }]
            }
        """.trimIndent()
        val detail = ApiJson.moshi.adapter(BuildingDetailResponse::class.java).fromJson(detailJson)!!
        assertEquals("550e8400-e29b-41d4-a716-446655440000", detail.id)
        assertNull(detail.name)
        assertEquals(19.845, detail.centroid.lon, 0.0)
        assertEquals("Polygon", detail.geometry.type)
        assertTrue(detail.geometry.toGeoJsonObject().contains("\"coordinates\""))
        assertEquals("rgz", detail.addresses[0].source)
        assertEquals("org:osm:n123", detail.organizations[0].id)
        assertEquals("pharmacy", detail.organizations[0].category_slug)
        assertEquals(1, detail.organizations.size)
    }

    @Test
    fun moshiParsesEmptyOrganizationsAndOrgDetail() {
        val empty = ApiJson.moshi.adapter(BuildingDetailResponse::class.java).fromJson(
            """
            {
              "id": "empty",
              "name": null,
              "centroid": { "lon": 19.8, "lat": 45.2 },
              "geometry": { "type": "Polygon", "coordinates": [] },
              "addresses": [],
              "organizations": []
            }
            """.trimIndent(),
        )!!
        assertTrue(empty.organizations.isEmpty())

        val org = ApiJson.moshi.adapter(OrgDetailResponse::class.java).fromJson(
            """
            {
              "id": "org:osm:n123",
              "name": "Apoteka Benu",
              "source": "osm",
              "category_slug": "pharmacy",
              "category_name": "Apoteka",
              "phones": ["+381 21 123456"],
              "website": "https://example.rs",
              "hours": "Mo-Fr 08:00-20:00",
              "floor": null,
              "tags": ["brand:benu"],
              "address": {
                "label": "Bulevar oslobođenja 12",
                "street": "Bulevar oslobođenja",
                "housenumber": "12"
              },
              "building_id": "550e8400-e29b-41d4-a716-446655440000",
              "location": { "lon": 19.8452, "lat": 45.2551 }
            }
            """.trimIndent(),
        )!!
        assertEquals("org:osm:n123", org.id)
        assertEquals("osm", org.source)
        assertEquals(listOf("+381 21 123456"), org.phones)
        assertEquals("https://example.rs", org.website)
        assertEquals("Mo-Fr 08:00-20:00", org.hours)
        assertEquals("550e8400-e29b-41d4-a716-446655440000", org.building_id)
        assertEquals("pharmacy", org.category_slug)
    }

    @Test
    fun moshiAcceptsNullPhonesAndHours() {
        val org = ApiJson.moshi.adapter(OrgDetailResponse::class.java).fromJson(
            """
            {
              "id": "org:editorial:1",
              "name": "X",
              "source": "editorial",
              "category_slug": null,
              "category_name": null,
              "phones": null,
              "website": null,
              "hours": null,
              "floor": null,
              "tags": null,
              "address": null,
              "building_id": null,
              "location": { "lon": 19.84, "lat": 45.25 }
            }
            """.trimIndent(),
        )!!
        assertNull(org.phones)
        assertNull(org.hours)
        assertNull(org.website)
        assertEquals(19.84, org.location.lon, 0.0)
    }

    @Test
    fun apiClientNormalizesBaseUrl() {
        assertEquals("http://10.0.2.2:3000/", ApiClient.normalizeBaseUrl("http://10.0.2.2:3000"))
        assertEquals("http://10.0.2.2:3000/", ApiClient.normalizeBaseUrl("http://10.0.2.2:3000/"))
    }
}
