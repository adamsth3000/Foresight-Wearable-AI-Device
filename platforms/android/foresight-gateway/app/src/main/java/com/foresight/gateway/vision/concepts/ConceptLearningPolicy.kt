package com.foresight.gateway.vision.concepts

/** Explicit policy boundary: similarity alone cannot make a concept trusted. */
class ConceptLearningPolicy(private val config: Config = Config()) {
    data class Config(
        val supportedExampleCount: Int = 2,
        val learnedExampleCount: Int = 3,
        val consistencyThreshold: Float = 0.86f,
        val corroboratingEvidenceConfidence: Float = 0.7f,
    ) {
        init {
            require(supportedExampleCount >= 2 && learnedExampleCount >= supportedExampleCount)
            require(consistencyThreshold in -1f..1f && corroboratingEvidenceConfidence in 0f..1f)
        }
    }

    data class PromotionDecision(val state: VisualConceptState, val reason: String)

    fun evaluate(examples: Collection<VisualConceptExample>, evidence: Collection<ConceptEvidence>): PromotionDecision {
        if (evidence.any { it.source == ConceptEvidenceSource.USER_CONFIRMED && it.confidence >= config.corroboratingEvidenceConfidence }) {
            return PromotionDecision(VisualConceptState.LEARNED, "explicit user confirmation")
        }
        val consistent = consistentExampleCount(examples)
        if (consistent >= config.learnedExampleCount && evidence.any {
                it.source != ConceptEvidenceSource.VLM_PROPOSAL && it.confidence >= config.corroboratingEvidenceConfidence
            }) {
            return PromotionDecision(VisualConceptState.LEARNED, "consistent examples with corroborating evidence")
        }
        if (consistent >= config.supportedExampleCount) return PromotionDecision(VisualConceptState.SUPPORTED, "repeated consistent examples")
        return PromotionDecision(VisualConceptState.CANDIDATE, "insufficient corroborated evidence")
    }

    private fun consistentExampleCount(examples: Collection<VisualConceptExample>): Int {
        if (examples.isEmpty()) return 0
        val centroid = VisualEmbedding.centroid(examples.map { it.embedding })
        return examples.count { it.embedding.cosineSimilarity(centroid) >= config.consistencyThreshold }
    }
}
