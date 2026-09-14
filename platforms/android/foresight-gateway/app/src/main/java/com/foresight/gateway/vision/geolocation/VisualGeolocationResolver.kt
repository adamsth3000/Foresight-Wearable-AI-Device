package com.foresight.gateway.vision.geolocation

import android.os.SystemClock
import android.util.Log
import com.foresight.gateway.sensors.HeadingState
import com.foresight.gateway.vision.GeoMath
import com.foresight.gateway.vision.SceneLocation
import com.foresight.gateway.vision.SceneLocationQuality
import com.foresight.gateway.vision.SceneLocationFreshnessPolicy
import com.foresight.gateway.vision.SceneLocationQualityPolicy
import com.foresight.gateway.vision.SceneEvidenceSnapshot
import com.foresight.gateway.voice.conversation.ConversationVisualFrame
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executor
import java.util.concurrent.Executors

enum class GeolocationConfidenceClass { CONFIRMED, STRONG, PLAUSIBLE, WEAK, UNRESOLVED }
enum class GeolocationEvidenceType { LIVE_IMAGE, OCR_TEXT, PHONE_LOCATION, HEADING, REVERSE_GEOCODE, ROAD_CONTEXT, GOOGLE_MAPS, GOOGLE_SEARCH, STREET_VIEW }
enum class ReverseGeocodePrecision { ROOFTOP, RANGE_INTERPOLATED, GEOMETRIC_CENTER, APPROXIMATE, UNKNOWN }
enum class GeographicConsistencyResult { CONSISTENT, OUTSIDE_ACCURACY_RADIUS, DISTANT, UNKNOWN }
enum class GeolocationQueryType { WHERE_AM_I, WHAT_STREET_AM_I_ON, WHAT_INTERSECTION_AM_I_AT, VIEWED_BUILDING, VIEWED_HOUSE_ADDRESS, OTHER }

data class GeolocationEvidence(
    val type: GeolocationEvidenceType,
    val capturedAtElapsedMillis: Long,
    val freshness: String,
    val quality: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class ReverseGeocodeAddress(
    val formattedAddress: String?, val streetNumber: String?, val route: String?, val locality: String?,
    val administrativeArea: String?, val postalCode: String?, val country: String?, val placeId: String?, val plusCode: String?,
    val precision: ReverseGeocodePrecision = ReverseGeocodePrecision.UNKNOWN,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

data class RoadContext(val roadName: String?, val intersectionDescription: String?)

data class PositionEstimate(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val quality: SceneLocationQuality,
    val provider: String?,
    val ageMillis: Long,
    val reverseGeocodePrecision: ReverseGeocodePrecision? = null,
    val formattedAddress: String? = null,
    val street: String? = null,
    val intersection: String? = null,
)

data class AddressCandidate(
    val formattedAddress: String?, val streetNumber: String?, val route: String?, val placeId: String?,
    val latitude: Double?, val longitude: Double?, val reverseGeocodePrecision: ReverseGeocodePrecision?,
    val distanceFromPhoneMeters: Double?, val bearingFromPhoneDegrees: Double?, val headingDeltaDegrees: Double?,
    val evidenceSources: Set<GeolocationEvidenceType>,
)

data class ViewedPlaceHypothesis(
    val placeName: String? = null, val address: String? = null, val candidateLocation: AddressCandidate? = null,
    val distanceFromPhoneMeters: Double? = null, val bearingFromPhoneDegrees: Double? = null, val headingDeltaDegrees: Double? = null,
    val visualSupport: Boolean, val ocrSupport: Boolean, val mapsSupport: Boolean, val streetViewSupport: Boolean,
    val confidence: GeolocationConfidenceClass,
)

data class GeolocationHypothesis(
    val hypothesisId: String,
    val placeName: String? = null,
    val formattedAddress: String? = null,
    val streetName: String? = null,
    val intersectionDescription: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationAccuracyMeters: Float? = null,
    val distanceMeters: Double? = null,
    val bearingDegrees: Double? = null,
    val headingDeltaDegrees: Double? = null,
    val visualSupport: Boolean = false,
    val ocrSupport: Boolean = false,
    val mapsSupport: Boolean = false,
    val streetViewSupport: Boolean = false,
    val confidenceClass: GeolocationConfidenceClass,
    val reasoningSummary: String? = null,
    val sources: List<GeolocationEvidence> = emptyList(),
)

data class VisualGeolocationResult(
    val bestHypothesis: GeolocationHypothesis?,
    val alternateHypotheses: List<GeolocationHypothesis> = emptyList(),
    val evidence: List<GeolocationEvidence> = emptyList(),
    val unresolvedReason: String? = null,
    val usedStreetView: Boolean = false,
    val usedMaps: Boolean = false,
    val usedSearch: Boolean = false,
    val usedOcr: Boolean = false,
    val usedHeading: Boolean = false,
    val positionEstimate: PositionEstimate? = null,
    val addressCandidates: List<AddressCandidate> = emptyList(),
    val viewedPlaceHypothesis: ViewedPlaceHypothesis? = null,
    val queryType: GeolocationQueryType = GeolocationQueryType.OTHER,
)

enum class VisualGeolocationQueryKind { NONE, LOCATION, VISUAL_PLACE }

object VisualGeolocationQueryPolicy {
    fun queryType(utterance: String): GeolocationQueryType {
        val value = utterance.lowercase()
        return when {
            "where am i" in value -> GeolocationQueryType.WHERE_AM_I
            "what street" in value || "street am i on" in value -> GeolocationQueryType.WHAT_STREET_AM_I_ON
            "what intersection" in value || "intersection am i at" in value -> GeolocationQueryType.WHAT_INTERSECTION_AM_I_AT
            "what address" in value && ("house" in value || "building" in value) -> GeolocationQueryType.VIEWED_HOUSE_ADDRESS
            listOf("building", "house", "business", "store", "museum", "place", "looking at", "this street").any(value::contains) -> GeolocationQueryType.VIEWED_BUILDING
            else -> GeolocationQueryType.OTHER
        }
    }
    fun classify(utterance: String): VisualGeolocationQueryKind {
        return when (queryType(utterance)) {
            GeolocationQueryType.VIEWED_BUILDING, GeolocationQueryType.VIEWED_HOUSE_ADDRESS -> VisualGeolocationQueryKind.VISUAL_PLACE
            GeolocationQueryType.WHERE_AM_I, GeolocationQueryType.WHAT_STREET_AM_I_ON, GeolocationQueryType.WHAT_INTERSECTION_AM_I_AT -> VisualGeolocationQueryKind.LOCATION
            GeolocationQueryType.OTHER -> VisualGeolocationQueryKind.NONE
        }
    }
    fun requiresImage(kind: VisualGeolocationQueryKind) = kind == VisualGeolocationQueryKind.VISUAL_PLACE
}

/** Parsed Google Geocoding response only; it deliberately contains no transport or Android UI concerns. */
object ReverseGeocodeParser {
    fun parse(body: String): ReverseGeocodeAddress? = runCatching {
        val root = JSONObject(body)
        val result = root.optJSONArray("results")?.optJSONObject(0) ?: return null
        val components = result.optJSONArray("address_components") ?: JSONArray()
        fun component(type: String): String? = (0 until components.length()).asSequence()
            .mapNotNull { components.optJSONObject(it) }
            .firstOrNull { candidate -> candidate.optJSONArray("types")?.let { types -> (0 until types.length()).any { types.optString(it) == type } } == true }
            ?.optString("long_name")?.ifBlank { null }
        val geometry = result.optJSONObject("geometry")?.optJSONObject("location")
        ReverseGeocodeAddress(
            formattedAddress = result.optString("formatted_address").ifBlank { null }, streetNumber = component("street_number"),
            route = component("route"), locality = component("locality"), administrativeArea = component("administrative_area_level_1"),
            postalCode = component("postal_code"), country = component("country"), placeId = result.optString("place_id").ifBlank { null },
            plusCode = result.optJSONObject("plus_code")?.optString("global_code")?.ifBlank { null },
            precision = result.optJSONObject("geometry")?.optString("location_type")?.let(::reversePrecision) ?: ReverseGeocodePrecision.UNKNOWN,
            latitude = geometry?.takeIf { it.has("lat") }?.optDouble("lat"),
            longitude = geometry?.takeIf { it.has("lng") }?.optDouble("lng"),
        )
    }.getOrNull()

    private fun reversePrecision(value: String): ReverseGeocodePrecision = when (value) {
        "ROOFTOP" -> ReverseGeocodePrecision.ROOFTOP
        "RANGE_INTERPOLATED" -> ReverseGeocodePrecision.RANGE_INTERPOLATED
        "GEOMETRIC_CENTER" -> ReverseGeocodePrecision.GEOMETRIC_CENTER
        "APPROXIMATE" -> ReverseGeocodePrecision.APPROXIMATE
        else -> ReverseGeocodePrecision.UNKNOWN
    }
}

object GeographicConsistencyPolicy {
    fun evaluate(accuracyMeters: Float?, distanceMeters: Double?): GeographicConsistencyResult = when {
        accuracyMeters == null || distanceMeters == null -> GeographicConsistencyResult.UNKNOWN
        distanceMeters <= accuracyMeters -> GeographicConsistencyResult.CONSISTENT
        distanceMeters > maxOf(100.0, accuracyMeters * 5.0) -> GeographicConsistencyResult.DISTANT
        else -> GeographicConsistencyResult.OUTSIDE_ACCURACY_RADIUS
    }
}

interface GoogleMapsGeolocationClient {
    fun reverseGeocode(location: SceneLocation): ReverseGeocodeAddress?
    fun streetViewMetadata(location: SceneLocation, headingDegrees: Float?): StreetViewMetadata
}

data class StreetViewMetadata(val available: Boolean, val panoId: String? = null, val captureDate: String? = null)

/** Direct, one-request Google Maps Platform client. It keeps no Maps or Street View content. */
class GoogleMapsPlatformGeolocationClient(private val apiKey: () -> String?) : GoogleMapsGeolocationClient {
    override fun reverseGeocode(location: SceneLocation): ReverseGeocodeAddress? {
        val key = apiKey()?.trim().orEmpty(); if (key.isBlank()) return null
        val encoded = URLEncoder.encode("${location.latitude},${location.longitude}", "UTF-8")
        val connection = (URL("https://maps.googleapis.com/maps/api/geocode/json?latlng=$encoded&key=$key").openConnection() as HttpURLConnection).apply {
            connectTimeout = 3_000; readTimeout = 4_000; requestMethod = "GET"
        }
        return try { if (connection.responseCode in 200..299) ReverseGeocodeParser.parse(connection.inputStream.bufferedReader().use { it.readText() }) else null } finally { connection.disconnect() }
    }

    override fun streetViewMetadata(location: SceneLocation, headingDegrees: Float?): StreetViewMetadata {
        val key = apiKey()?.trim().orEmpty(); if (key.isBlank()) return StreetViewMetadata(false)
        val encoded = URLEncoder.encode("${location.latitude},${location.longitude}", "UTF-8")
        val connection = (URL("https://maps.googleapis.com/maps/api/streetview/metadata?location=$encoded&radius=50&key=$key").openConnection() as HttpURLConnection).apply {
            connectTimeout = 3_000; readTimeout = 4_000; requestMethod = "GET"
        }
        return try {
            if (connection.responseCode !in 200..299) StreetViewMetadata(false) else JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).let { json ->
                StreetViewMetadata(json.optString("status") == "OK", json.optString("pano_id").ifBlank { null }, json.optString("date").ifBlank { null })
            }
        } finally { connection.disconnect() }
    }
}

object GeolocationRankingPolicy {
    fun confidence(visual: Boolean, ocr: Boolean, maps: Boolean, streetView: Boolean, conflicting: Boolean): GeolocationConfidenceClass = when {
        conflicting -> GeolocationConfidenceClass.WEAK
        visual && ocr && maps && streetView -> GeolocationConfidenceClass.CONFIRMED
        visual && maps && (ocr || streetView) -> GeolocationConfidenceClass.STRONG
        maps && (visual || ocr) -> GeolocationConfidenceClass.PLAUSIBLE
        maps -> GeolocationConfidenceClass.WEAK
        else -> GeolocationConfidenceClass.UNRESOLVED
    }
}

interface VisualGeolocationResolver {
    fun resolve(utterance: String, evidence: SceneEvidenceSnapshot?, location: SceneLocation?, frame: ConversationVisualFrame?, onComplete: (VisualGeolocationResult) -> Unit)
}

object NoVisualGeolocationResolver : VisualGeolocationResolver {
    override fun resolve(utterance: String, evidence: SceneEvidenceSnapshot?, location: SceneLocation?, frame: ConversationVisualFrame?, onComplete: (VisualGeolocationResult) -> Unit) =
        onComplete(VisualGeolocationResult(null, unresolvedReason = "resolver_unconfigured"))
}

class DefaultVisualGeolocationResolver(
    private val maps: GoogleMapsGeolocationClient,
    private val streetViewAcquisition: StreetViewAcquisitionCoordinator? = null,
    private val executor: Executor = Executors.newSingleThreadExecutor { Thread(it, "ForesightGeoResolve") },
    private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
) : VisualGeolocationResolver {
    override fun resolve(utterance: String, evidence: SceneEvidenceSnapshot?, location: SceneLocation?, frame: ConversationVisualFrame?, onComplete: (VisualGeolocationResult) -> Unit) {
        val kind = VisualGeolocationQueryPolicy.classify(utterance)
        val queryType = VisualGeolocationQueryPolicy.queryType(utterance)
        if (kind == VisualGeolocationQueryKind.NONE) { onComplete(VisualGeolocationResult(null, unresolvedReason = "not_geographic")); return }
        executor.execute {
            val now = elapsedRealtimeMillis(); val ocr = evidence?.ocrObservation?.regions?.isNotEmpty() == true; val heading = evidence?.heading?.takeIf(HeadingState::isAvailable)
            val availableLocation = location?.takeIf { it.ageMillis(now) <= SceneLocationFreshnessPolicy.MAPS_FRESH_MAX_AGE_MILLIS }
            Log.i(TAG, "FORESIGHT_GEO_RESOLVE_START")
            Log.i(TAG, "FORESIGHT_GEO_QUERY_TYPE type=$queryType")
            if (availableLocation == null) { Log.i(TAG, "FORESIGHT_GEO_RESOLVE_UNRESOLVED reason=no_fresh_location"); onComplete(VisualGeolocationResult(null, unresolvedReason = "no_fresh_location", usedOcr = ocr, usedHeading = heading != null)); return@execute }
            val reverse = maps.reverseGeocode(availableLocation)
            if (reverse != null) Log.i(TAG, "FORESIGHT_REVERSE_GEOCODE_SUCCESS")
            val road = RoadContext(reverse?.route, null); if (road.roadName != null) Log.i(TAG, "FORESIGHT_ROAD_CONTEXT_READY")
            val street = maps.streetViewMetadata(availableLocation, heading?.headingDegrees)
            Log.i(TAG, "FORESIGHT_STREETVIEW_METADATA available=${street.available} candidateCount=${if (street.available) 1 else 0}")
            val signals = buildList {
                add(GeolocationEvidence(GeolocationEvidenceType.PHONE_LOCATION, availableLocation.capturedAtElapsedMillis, "fresh", SceneLocationQualityPolicy.classify(availableLocation.horizontalAccuracyMeters).name))
                if (frame != null) add(GeolocationEvidence(GeolocationEvidenceType.LIVE_IMAGE, frame.capturedAtElapsedMillis, "fresh"))
                if (ocr) add(GeolocationEvidence(GeolocationEvidenceType.OCR_TEXT, now, "current"))
                if (heading != null) add(GeolocationEvidence(GeolocationEvidenceType.HEADING, now, "current", "PHONE_HEADING_PROXY"))
                if (reverse != null) add(GeolocationEvidence(GeolocationEvidenceType.REVERSE_GEOCODE, now, "current"))
                if (road.roadName != null) add(GeolocationEvidence(GeolocationEvidenceType.ROAD_CONTEXT, now, "current"))
                if (street.available) add(GeolocationEvidence(GeolocationEvidenceType.STREET_VIEW, now, "historical", metadata = mapOf("captureDate" to (street.captureDate ?: "unknown"))))
            }
            val visual = frame != null; val mapsSupport = reverse != null
            val position = PositionEstimate(availableLocation.latitude, availableLocation.longitude, availableLocation.horizontalAccuracyMeters, SceneLocationQualityPolicy.classify(availableLocation.horizontalAccuracyMeters), availableLocation.provider, availableLocation.ageMillis(now), reverse?.precision, reverse?.formattedAddress, reverse?.route, road.intersectionDescription)
            Log.i(TAG, "FORESIGHT_POSITION_ESTIMATE accuracyMeters=${position.accuracyMeters ?: "unknown"} quality=${position.quality} reverseGeocodePrecision=${position.reverseGeocodePrecision ?: "UNKNOWN"} ageMs=${position.ageMillis}")
            val candidate = reverse?.let { address ->
                val addressLocation = if (address.latitude != null && address.longitude != null) SceneLocation(address.latitude, address.longitude, null, null, availableLocation.capturedAtElapsedMillis, "reverse_geocode") else null
                val distance = addressLocation?.let { GeoMath.distanceMeters(availableLocation, it) }
                val consistency = GeographicConsistencyPolicy.evaluate(availableLocation.horizontalAccuracyMeters, distance)
                Log.i(TAG, "FORESIGHT_GEO_CANDIDATE_DISTANCE distanceMeters=${distance?.toInt() ?: "unknown"} accuracyMeters=${availableLocation.horizontalAccuracyMeters ?: "unknown"} consistency=$consistency")
                AddressCandidate(address.formattedAddress, address.streetNumber, address.route, address.placeId, address.latitude, address.longitude, address.precision, distance, null, null, setOf(GeolocationEvidenceType.REVERSE_GEOCODE, GeolocationEvidenceType.PHONE_LOCATION))
            }
            val confidence = GeolocationRankingPolicy.confidence(visual, ocr, mapsSupport, false, false)
            val viewed = if (queryType in setOf(GeolocationQueryType.VIEWED_BUILDING, GeolocationQueryType.VIEWED_HOUSE_ADDRESS)) ViewedPlaceHypothesis(visualSupport = visual, ocrSupport = ocr, mapsSupport = mapsSupport, streetViewSupport = false, confidence = GeolocationConfidenceClass.UNRESOLVED) else null
            val hypothesis = reverse?.let { address -> GeolocationHypothesis("phone-position-reverse-geocode", formattedAddress = address.formattedAddress, streetName = address.route, intersectionDescription = road.intersectionDescription, latitude = availableLocation.latitude, longitude = availableLocation.longitude, locationAccuracyMeters = availableLocation.horizontalAccuracyMeters, visualSupport = visual, ocrSupport = ocr, mapsSupport = true, confidenceClass = confidence, reasoningSummary = "Phone-position reverse-geocode context only.", sources = signals) }
            Log.i(TAG, "FORESIGHT_VIEWED_PLACE_EVIDENCE visual=$visual ocr=$ocr heading=${heading != null} maps=$mapsSupport streetView=false")
            Log.i(TAG, "FORESIGHT_GEO_PRECISION_DECISION source=PHONE_POSITION reason=${if (queryType == GeolocationQueryType.WHERE_AM_I) "current_position_query" else "position_context"}")
            Log.i(TAG, "FORESIGHT_GEO_EVIDENCE visual=$visual ocr=$ocr location=true heading=${heading != null} reverseGeocode=${reverse != null} maps=$mapsSupport streetView=${street.available}")
            Log.i(TAG, "FORESIGHT_GEO_CANDIDATE_RANK candidateCount=${if (hypothesis == null) 0 else 1}")
            val complete = {
                Log.i(TAG, "FORESIGHT_GEO_RESOLVE_COMPLETE confidence=${hypothesis?.confidenceClass ?: GeolocationConfidenceClass.UNRESOLVED} usedStreetView=false")
                onComplete(VisualGeolocationResult(hypothesis, evidence = signals, unresolvedReason = if (hypothesis == null) "reverse_geocode_unavailable" else null, usedMaps = mapsSupport, usedOcr = ocr, usedHeading = heading != null, positionEstimate = position, addressCandidates = listOfNotNull(candidate), viewedPlaceHypothesis = viewed, queryType = queryType))
            }
            streetViewAcquisition?.acquireIfEligible(kind, frame, true, heading?.headingDegrees, street) { complete() } ?: complete()
        }
    }
    private companion object { const val TAG = "ForesightGeo" }
}
