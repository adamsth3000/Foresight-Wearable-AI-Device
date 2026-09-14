package com.foresight.gateway.voice.conversation.comparison

data class StreetViewVisualComparisonRequest(
    val liveFrameJpeg: ByteArray,
    val references: List<StreetViewComparisonReference>,
) {
    init {
        require(references.isNotEmpty()) { "At least one Street View reference is required" }
        require(references.size <= MAX_REFERENCES) { "At most $MAX_REFERENCES Street View references are supported" }
    }

    companion object {
        const val MAX_REFERENCES = 3
    }
}

data class StreetViewComparisonReference(
    val candidateId: String,
    val headingDegrees: Float,
    val imageJpeg: ByteArray,
)

enum class StreetViewComparisonOutcome {
    MATCHED,
    INCONCLUSIVE,
    NO_MATCH,
    CANCELLED,
    FAILED,
}

enum class StreetViewComparisonConfidence {
    LOW,
    MEDIUM,
    HIGH,
}

data class StreetViewVisualComparisonResult(
    val outcome: StreetViewComparisonOutcome,
    val selectedCandidateId: String? = null,
    val confidence: StreetViewComparisonConfidence? = null,
    val rationale: String? = null,
)
