package com.foresight.gateway.vision.geolocation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreetViewReferenceProviderTest {
    @Test fun `only visual place questions are eligible for street view`() {
        assertEquals(VisualGeolocationQueryKind.VISUAL_PLACE, VisualGeolocationQueryPolicy.classify("What building is this?"))
        assertEquals(VisualGeolocationQueryKind.VISUAL_PLACE, VisualGeolocationQueryPolicy.classify("What business is this?"))
        assertEquals(VisualGeolocationQueryKind.VISUAL_PLACE, VisualGeolocationQueryPolicy.classify("What house am I looking at?"))
        assertEquals(VisualGeolocationQueryKind.LOCATION, VisualGeolocationQueryPolicy.classify("Where am I?"))
        assertEquals(VisualGeolocationQueryKind.NONE, VisualGeolocationQueryPolicy.classify("Why is the sky blue?"))
    }
    @Test fun `candidate generator returns current and offset headings`() {
        val candidates = StreetViewCandidateGenerator.generate(StreetViewMetadata(true, "pano", "2020"), 5f)
        assertEquals(listOf(5f, 335f, 35f), candidates.map { it.headingDegrees })
        assertEquals(3, candidates.size)
    }

    @Test fun `request uses pano and fixed static image parameters`() {
        val provider = GoogleStreetViewReferenceProvider(DirectAndroidMvpStreetViewAuthorizer { "key" }, object : AndroidApiIdentityProvider { override fun packageName() = "pkg"; override fun certificateSha1() = "cert" }, transport = object : StreetViewHttpTransport { override fun get(url: String, headers: Map<String, String>) = StreetViewHttpResponse(200, "image/jpeg", byteArrayOf(1)) })
        val url = provider.requestUrl(StreetViewCandidate("id", "pano", headingDegrees = 330f), "key")
        assertTrue(url.contains("pano=pano")); assertTrue(url.contains("size=512x512")); assertTrue(url.contains("heading=330.0")); assertTrue(url.contains("fov=80")); assertTrue(url.contains("pitch=0")); assertTrue(url.contains("return_error_code=true")); provider.close()
    }

    @Test fun `batch cleanup zeroes bytes`() {
        val bytes = byteArrayOf(1, 2); val batch = StreetViewReferenceBatch(mutableListOf(StreetViewReferenceImage(StreetViewCandidate("id", "pano", headingDegrees = 0f), bytes, "image/jpeg", 1, 1, 0)))
        batch.close(); assertEquals(listOf(0, 0), bytes.map { it.toInt() })
    }

    @Test fun `batch close is idempotent and access after close is rejected`() {
        val batch = StreetViewReferenceBatch(mutableListOf())
        batch.close(); batch.close()
        runCatching { batch.images() }.onSuccess { throw AssertionError("closed batch was readable") }
    }

    @Test fun `batch only transfers ownership once`() {
        val batch = StreetViewReferenceBatch(mutableListOf())
        batch.transferOwnership()
        runCatching { batch.transferOwnership() }.onSuccess { throw AssertionError("second transfer succeeded") }
        batch.close()
    }
}
