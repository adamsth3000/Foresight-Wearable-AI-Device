package com.foresight.gateway.voice.conversation

import java.util.Locale

class ForesightContextRenderer {
    fun render(snapshot: ForesightContextSnapshot): String = buildString {
        appendLine("FORESIGHT CURRENT CONTEXT")
        appendLine("capture: ${if (snapshot.captureActive) "active" else "inactive"}")
        appendLine("mode: ${snapshot.visualizationMode}")
        appendLine("vision_observation_age_ms: ${snapshot.observationAgeMillis ?: "unavailable"}")
        appendLine("objects:")
        objectSummaries(snapshot.observedObjects).ifEmpty { listOf("unavailable") }
            .forEach { appendLine("- $it") }
        appendLine("selected_target: ${snapshot.selectedVisualContext?.label ?: snapshot.selectedVisualContext?.targetId ?: "unavailable"}")
        appendLine(
            "visual_concept_memory: " +
                if (snapshot.visualConceptMemory == ContextAvailability.AVAILABLE) "available, no match supplied" else "unavailable",
        )
        val location = snapshot.sceneEvidence?.location
        appendLine(
            "location: " + (location?.let {
                "phone_position latitude=${it.latitude}, longitude=${it.longitude}, " +
                    "accuracy_m=${it.horizontalAccuracyMeters ?: "unknown"}, " +
                    "age_ms=${it.ageMillis(snapshot.timestampElapsedMillis)}, provider=${it.provider ?: "unknown"}"
            } ?: "unavailable"),
        )
        val ocrRegions = snapshot.sceneEvidence?.ocrObservation?.regions.orEmpty()
        if (ocrRegions.isEmpty()) {
            appendLine("ocr: unavailable")
        } else {
            appendLine("ocr:")
            ocrRegions.forEach { region -> appendLine("- OCR result (may contain recognition errors): \"${region.text}\"") }
        }
        appendLine("scene_heading: ${snapshot.sceneEvidence?.heading?.headingDegrees?.let { "%.0f degrees (phone heading proxy)".format(Locale.US, it) } ?: "unavailable"}")
        appendLine("scene_location: ${location?.let { "available (phone position only)" } ?: "unavailable"}")
        snapshot.geolocation?.let { result ->
            appendLine("GEOLOCATION RESOLVER")
            appendLine("geo_query_type: ${result.queryType}")
            result.positionEstimate?.let { position ->
                appendLine("PHONE POSITION: latitude=${position.latitude}, longitude=${position.longitude}, accuracy_m=${position.accuracyMeters ?: "unknown"}, age_ms=${position.ageMillis}, quality=${position.quality}, provider=${position.provider ?: "unknown"}")
                appendLine("phone_reverse_geocode: ${position.formattedAddress ?: "unavailable"}; route=${position.street ?: "unavailable"}; precision=${position.reverseGeocodePrecision ?: "UNKNOWN"}")
            }
            val viewed = result.viewedPlaceHypothesis
            appendLine("VIEWED PLACE: ${if (viewed == null) "not requested" else "confidence=${viewed.confidence}; visual=${viewed.visualSupport}; ocr=${viewed.ocrSupport}; heading=${result.usedHeading}; maps=${viewed.mapsSupport}; street_view=${viewed.streetViewSupport}"}")
            appendLine("street_view_reference: ${if (result.usedStreetView) "available historical reference" else "not used"}")
            appendLine("geolocation_limit: The phone position is not necessarily the location of the object or building the camera is viewing. Do not replace a precise phone-position estimate with a distant Maps place or intersection without supporting evidence.")
        }
        appendLine("concept_matches:")
        snapshot.sceneEvidence?.visualConceptMatches?.ifEmpty { null }?.forEach { match ->
            appendLine("- ${match.name}, confidence ${format(match.confidence)}")
        } ?: appendLine("- unavailable")
        append("camera_pose: unavailable")
    }

    fun inlineSummary(snapshot: ForesightContextSnapshot): String =
        objectSummaries(snapshot.observedObjects).joinToString().ifBlank { "no current detected objects" }

    private fun objectSummaries(objects: List<ObservedObject>): List<String> = objects
        .groupBy { it.label.trim().lowercase(Locale.US) }
        .filterKeys(String::isNotBlank)
        .toSortedMap()
        .map { (label, matches) ->
            val confidence = matches.map(ObservedObject::confidence)
            "$label x${matches.size}, confidence ${format(confidence.min())}-${format(confidence.max())}"
        }

    private fun format(value: Float): String = "%.2f".format(Locale.US, value)
}
