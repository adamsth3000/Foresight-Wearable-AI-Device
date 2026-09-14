package com.foresight.gateway.vision.geolocation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualGeolocationResolverTest {
    @Test fun `reverse geocode parser exposes structured address fields`() {
        val parsed = ReverseGeocodeParser.parse("""{"results":[{"formatted_address":"ignored","place_id":"place","plus_code":{"global_code":"code"},"address_components":[{"long_name":"12","types":["street_number"]},{"long_name":"Main Street","types":["route"]},{"long_name":"Town","types":["locality"]}]}]}""")
        assertNotNull(parsed)
        assertEquals("12", parsed?.streetNumber)
        assertEquals("Main Street", parsed?.route)
        assertEquals("place", parsed?.placeId)
    }

    @Test fun `missing or malformed reverse geocode is safe`() {
        assertEquals(null, ReverseGeocodeParser.parse("{"))
        assertEquals(null, ReverseGeocodeParser.parse("{\"results\":[]}"))
    }

    @Test fun `reverse geocode parser retains precision and geometry`() {
        val parsed = ReverseGeocodeParser.parse("""{"results":[{"geometry":{"location_type":"ROOFTOP","location":{"lat":1.0,"lng":2.0}},"address_components":[]}]}""")
        assertEquals(ReverseGeocodePrecision.ROOFTOP, parsed?.precision)
        assertEquals(1.0, parsed?.latitude)
        assertEquals(2.0, parsed?.longitude)
    }

    @Test fun `reverse geocode parser recognizes documented precision classes`() {
        listOf("RANGE_INTERPOLATED" to ReverseGeocodePrecision.RANGE_INTERPOLATED, "GEOMETRIC_CENTER" to ReverseGeocodePrecision.GEOMETRIC_CENTER, "APPROXIMATE" to ReverseGeocodePrecision.APPROXIMATE).forEach { (wire, expected) ->
            assertEquals(expected, ReverseGeocodeParser.parse("""{"results":[{"geometry":{"location_type":"$wire"},"address_components":[]}]}""")?.precision)
        }
    }

    @Test fun `distance consistency protects precise phone position from distant candidate`() {
        assertEquals(GeographicConsistencyResult.CONSISTENT, GeographicConsistencyPolicy.evaluate(8f, 6.0))
        assertEquals(GeographicConsistencyResult.OUTSIDE_ACCURACY_RADIUS, GeographicConsistencyPolicy.evaluate(8f, 30.0))
        assertEquals(GeographicConsistencyResult.DISTANT, GeographicConsistencyPolicy.evaluate(8f, 400.0))
    }

    @Test fun `query policy requires imagery for visual places only`() {
        assertEquals(VisualGeolocationQueryKind.LOCATION, VisualGeolocationQueryPolicy.classify("Where am I?"))
        assertEquals(VisualGeolocationQueryKind.VISUAL_PLACE, VisualGeolocationQueryPolicy.classify("What building is this?"))
        assertTrue(VisualGeolocationQueryPolicy.requiresImage(VisualGeolocationQueryKind.VISUAL_PLACE))
        assertFalse(VisualGeolocationQueryPolicy.requiresImage(VisualGeolocationQueryKind.LOCATION))
        assertEquals(VisualGeolocationQueryKind.NONE, VisualGeolocationQueryPolicy.classify("Why is the sky blue?"))
    }

    @Test fun `maps alone cannot become confirmed`() {
        assertEquals(GeolocationConfidenceClass.WEAK, GeolocationRankingPolicy.confidence(false, false, true, false, false))
        assertEquals(GeolocationConfidenceClass.STRONG, GeolocationRankingPolicy.confidence(true, true, true, false, false))
        assertEquals(GeolocationConfidenceClass.WEAK, GeolocationRankingPolicy.confidence(true, true, true, true, true))
    }

    @Test fun `query type keeps current position separate from viewed house`() {
        assertEquals(GeolocationQueryType.WHERE_AM_I, VisualGeolocationQueryPolicy.queryType("Where am I?"))
        assertEquals(GeolocationQueryType.WHAT_STREET_AM_I_ON, VisualGeolocationQueryPolicy.queryType("What street am I on?"))
        assertEquals(GeolocationQueryType.WHAT_INTERSECTION_AM_I_AT, VisualGeolocationQueryPolicy.queryType("What intersection am I at?"))
        assertEquals(GeolocationQueryType.VIEWED_BUILDING, VisualGeolocationQueryPolicy.queryType("What building am I looking at?"))
        assertEquals(GeolocationQueryType.VIEWED_HOUSE_ADDRESS, VisualGeolocationQueryPolicy.queryType("What address is this house?"))
    }
}
