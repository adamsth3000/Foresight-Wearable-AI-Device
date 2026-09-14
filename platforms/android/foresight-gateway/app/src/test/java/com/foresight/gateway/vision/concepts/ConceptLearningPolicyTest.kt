package com.foresight.gateway.vision.concepts

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class ConceptLearningPolicyTest {
    @Test fun `candidate becomes supported with repeated consistent embeddings`() {
        assertEquals(VisualConceptState.SUPPORTED, policy().evaluate(examples(2), emptyList()).state)
    }

    @Test fun `candidate becomes learned only with corroboration or explicit user confirmation`() {
        assertEquals(VisualConceptState.SUPPORTED, policy().evaluate(examples(3), listOf(evidence(ConceptEvidenceSource.VLM_PROPOSAL))).state)
        assertEquals(VisualConceptState.LEARNED, policy().evaluate(examples(3), listOf(evidence(ConceptEvidenceSource.DETECTOR_LABEL))).state)
        assertEquals(VisualConceptState.LEARNED, policy().evaluate(emptyList(), listOf(evidence(ConceptEvidenceSource.USER_CONFIRMED))).state)
    }

    private fun policy() = ConceptLearningPolicy()
    private fun examples(count: Int) = List(count) { index -> VisualConceptExample("e$index", "bottle", VisualEmbedding.normalized(floatArrayOf(1f, 0.02f * index, 0f)), observedAt = Instant.EPOCH, confidence = 0.9f) }
    private fun evidence(source: ConceptEvidenceSource) = ConceptEvidence("proof-$source", "bottle", source, "red insulated bottle", 0.9f, Instant.EPOCH)
}
