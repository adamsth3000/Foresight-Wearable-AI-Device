package com.foresight.gateway.voice.conversation.comparison

import org.json.JSONArray
import org.json.JSONObject

internal sealed interface StreetViewComparisonDecodeResult {
    data class Valid(val result: StreetViewVisualComparisonResult) : StreetViewComparisonDecodeResult
    data class Failure(val type: String) : StreetViewComparisonDecodeResult
}

internal object StreetViewComparisonResponseDecoder {
    fun decode(body: String, candidateIds: Set<String>): StreetViewComparisonDecodeResult {
        val root = runCatching { JSONObject(body) }.getOrElse { return StreetViewComparisonDecodeResult.Failure("malformed_response") }
        val text = root.optString("output_text").takeIf { it.isNotBlank() }
            ?: textFromSteps(root.optJSONArray("steps"))
            ?: return StreetViewComparisonDecodeResult.Failure("missing_output")
        val payload = runCatching { JSONObject(text) }.getOrElse { return StreetViewComparisonDecodeResult.Failure("malformed_json") }
        if (!REQUIRED_FIELDS.all(payload::has)) return StreetViewComparisonDecodeResult.Failure("missing_field")

        val outcome = runCatching { StreetViewComparisonOutcome.valueOf(payload.getString("outcome")) }
            .getOrElse { return StreetViewComparisonDecodeResult.Failure("unsupported_outcome") }
        if (outcome !in setOf(StreetViewComparisonOutcome.MATCHED, StreetViewComparisonOutcome.INCONCLUSIVE, StreetViewComparisonOutcome.NO_MATCH)) {
            return StreetViewComparisonDecodeResult.Failure("unsupported_outcome")
        }
        val confidence = runCatching { StreetViewComparisonConfidence.valueOf(payload.getString("confidence")) }
            .getOrElse { return StreetViewComparisonDecodeResult.Failure("unsupported_confidence") }
        val selected = if (payload.isNull("selectedCandidateId")) null else payload.optString("selectedCandidateId").ifBlank { null }
        if (outcome == StreetViewComparisonOutcome.MATCHED && selected == null) return StreetViewComparisonDecodeResult.Failure("matched_without_candidate")
        if (selected != null && selected !in candidateIds) return StreetViewComparisonDecodeResult.Failure("unknown_candidate")
        val rationale = payload.optString("rationale").ifBlank { null }
        return StreetViewComparisonDecodeResult.Valid(
            StreetViewVisualComparisonResult(outcome, selected, confidence, rationale),
        )
    }

    private fun textFromSteps(steps: JSONArray?): String? = steps?.let { values ->
        for (index in 0 until values.length()) {
            val step = values.optJSONObject(index) ?: continue
            if (step.optString("type") != "model_output") continue
            val content = step.optJSONArray("content") ?: continue
            for (contentIndex in 0 until content.length()) {
                val item = content.optJSONObject(contentIndex) ?: continue
                if (item.optString("type") == "text") item.optString("text").trim().takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        null
    }

    private val REQUIRED_FIELDS = setOf("outcome", "selectedCandidateId", "confidence", "rationale")
}
