package com.foresight.gateway.vision

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoMath {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    fun distanceMeters(from: SceneLocation, to: SceneLocation): Double {
        val latitudeDelta = radians(to.latitude - from.latitude)
        val longitudeDelta = radians(to.longitude - from.longitude)
        val a = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
            cos(radians(from.latitude)) * cos(radians(to.latitude)) * sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
        return EARTH_RADIUS_METERS * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    fun initialBearingDegrees(from: SceneLocation, to: SceneLocation): Double {
        val longitudeDelta = radians(to.longitude - from.longitude)
        val y = sin(longitudeDelta) * cos(radians(to.latitude))
        val x = cos(radians(from.latitude)) * sin(radians(to.latitude)) -
            sin(radians(from.latitude)) * cos(radians(to.latitude)) * cos(longitudeDelta)
        return normalizeDegrees(Math.toDegrees(atan2(y, x)))
    }

    fun shortestAngularDeltaDegrees(fromDegrees: Double, toDegrees: Double): Double =
        ((toDegrees - fromDegrees + 540.0) % 360.0) - 180.0

    private fun radians(value: Double): Double = value * PI / 180.0
    private fun normalizeDegrees(value: Double): Double = (value % 360.0 + 360.0) % 360.0
}

enum class ContextAnnotationType { PLACE, BUSINESS, ADDRESS }

data class ContextAnnotation(
    val annotationId: String,
    val type: ContextAnnotationType,
    val label: String,
    val bearingDegrees: Double? = null,
    val distanceMeters: Double? = null,
    val normalizedScreenAnchor: Pair<Float, Float>? = null,
    val source: String,
)

data class PlaceCandidate(
    val candidateId: String,
    val location: SceneLocation,
)

data class DirectionalPlaceCandidate(
    val candidate: PlaceCandidate,
    val distanceMeters: Double,
    val bearingDegrees: Double,
    val headingDeltaDegrees: Double,
)

/** Pure, source-neutral directional ranking for future Maps candidates; it does not create screen anchors. */
object DirectionalPlaceCandidateFilter {
    fun rank(
        phoneLocation: SceneLocation,
        headingDegrees: Float,
        candidates: List<PlaceCandidate>,
    ): List<DirectionalPlaceCandidate> = candidates.map { candidate ->
        val bearing = GeoMath.initialBearingDegrees(phoneLocation, candidate.location)
        DirectionalPlaceCandidate(
            candidate = candidate,
            distanceMeters = GeoMath.distanceMeters(phoneLocation, candidate.location),
            bearingDegrees = bearing,
            headingDeltaDegrees = GeoMath.shortestAngularDeltaDegrees(headingDegrees.toDouble(), bearing),
        )
    }.sortedWith(compareBy<DirectionalPlaceCandidate> { kotlin.math.abs(it.headingDeltaDegrees) }.thenBy { it.distanceMeters })

    fun inFrontOfUser(rankedCandidates: List<DirectionalPlaceCandidate>, maximumHeadingDeltaDegrees: Double = 90.0): List<DirectionalPlaceCandidate> {
        require(maximumHeadingDeltaDegrees in 0.0..180.0)
        return rankedCandidates.filter { kotlin.math.abs(it.headingDeltaDegrees) <= maximumHeadingDeltaDegrees }
    }
}
