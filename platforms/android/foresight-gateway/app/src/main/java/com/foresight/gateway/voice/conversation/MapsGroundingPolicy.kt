package com.foresight.gateway.voice.conversation

import com.foresight.gateway.vision.SceneLocation
import com.foresight.gateway.vision.SceneLocationFreshnessPolicy

internal object MapsGroundingPolicy {
    enum class QueryKind { NONE, CURRENT_POSITION, GENERAL_LOCATION, VISUAL_PLACE }

    data class Decision(
        val queryKind: QueryKind,
        val mapsEnabled: Boolean,
        val searchEnabled: Boolean,
        val requiresVisualEvidence: Boolean,
        val reason: String,
    )

    fun requiresLocationRefresh(utterance: String, location: SceneLocation?, nowElapsedMillis: Long): Boolean =
        queryKind(utterance) != QueryKind.NONE &&
            (location == null || location.ageMillis(nowElapsedMillis) > SceneLocationFreshnessPolicy.SCENE_FRESH_MAX_AGE_MILLIS)

    fun decision(
        utterance: String,
        location: SceneLocation?,
        visualEvidenceAvailable: Boolean,
        nowElapsedMillis: Long,
    ): Decision {
        val kind = queryKind(utterance)
        if (kind == QueryKind.NONE) return Decision(kind, false, true, false, "non_geographic_query")
        if (kind == QueryKind.CURRENT_POSITION) return Decision(kind, false, false, false, "position_estimate_authoritative")
        if (location == null) return Decision(kind, false, true, kind == QueryKind.VISUAL_PLACE, "no_location")
        if (location.ageMillis(nowElapsedMillis) > SceneLocationFreshnessPolicy.MAPS_FRESH_MAX_AGE_MILLIS) {
            return Decision(kind, false, true, kind == QueryKind.VISUAL_PLACE, "stale_location")
        }
        if (kind == QueryKind.VISUAL_PLACE && !visualEvidenceAvailable) {
            return Decision(kind, false, true, true, "visual_place_requires_image")
        }
        return Decision(kind, true, true, kind == QueryKind.VISUAL_PLACE, "eligible_geographic_query")
    }

    private fun queryKind(utterance: String): QueryKind {
        val query = utterance.lowercase()
        if (listOf("building", "restaurant", "store", "monument", "place am i looking", "business", "statue").any(query::contains)) {
            return QueryKind.VISUAL_PLACE
        }
        if ("where am i" in query || "what street am i on" in query || "what intersection am i at" in query) return QueryKind.CURRENT_POSITION
        if (listOf("nearby", "where is", "ahead of me", "coffee shop", "museum", "street", "across the street", "entrance").any(query::contains)) {
            return QueryKind.GENERAL_LOCATION
        }
        return QueryKind.NONE
    }
}
