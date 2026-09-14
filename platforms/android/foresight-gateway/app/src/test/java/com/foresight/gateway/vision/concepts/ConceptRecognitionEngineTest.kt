package com.foresight.gateway.vision.concepts

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConceptRecognitionEngineTest {
    @Test fun `nearby examples rank their concept and unrelated vectors do not match`() {
        val bottle = concept("bottle", "red insulated bottle")
        val examples = listOf(example("one", bottle.id, floatArrayOf(1f, 0.02f, 0f)), example("two", bottle.id, floatArrayOf(0.98f, 0.08f, 0f)))
        val engine = ConceptRecognitionEngine()

        val match = engine.recognize(VisualEmbedding.normalized(floatArrayOf(1f, 0.03f, 0f)), listOf(bottle), examples).getOrThrow()
        assertEquals("bottle", match.single().conceptId)
        assertTrue(match.single().supportingExampleCount >= 2)
        assertTrue(engine.recognize(VisualEmbedding.normalized(floatArrayOf(0f, 0f, 1f)), listOf(bottle), examples).getOrThrow().isEmpty())
    }

    @Test fun `dimension mismatch is reported without a false match`() {
        val result = ConceptRecognitionEngine().recognize(VisualEmbedding.normalized(floatArrayOf(1f, 0f)), listOf(concept("b", "b")), listOf(example("e", "b", floatArrayOf(1f, 0f, 0f))))
        assertTrue(result.isFailure)
    }

    private fun concept(id: String, name: String) = VisualConcept(id, name, provenance = "test", firstSeenAt = Instant.EPOCH, lastSeenAt = Instant.EPOCH)
    private fun example(id: String, conceptId: String, values: FloatArray) = VisualConceptExample(id, conceptId, VisualEmbedding.normalized(values), observedAt = Instant.EPOCH, confidence = 0.9f)
}
