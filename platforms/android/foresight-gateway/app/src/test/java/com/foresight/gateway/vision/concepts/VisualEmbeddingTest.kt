package com.foresight.gateway.vision.concepts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VisualEmbeddingTest {
    @Test fun `normalizes serializes and restores embedding deterministically`() {
        val embedding = VisualEmbedding.normalized(floatArrayOf(3f, 4f))
        val restored = VisualEmbedding.fromBlob(embedding.toBlob())
        assertArrayEquals(embedding.values(), restored.values(), 0f)
        assertEquals(1f, embedding.cosineSimilarity(restored), 0.0001f)
    }

    @Test fun `rejects malformed nonfinite and dimension mismatched embeddings`() {
        assertThrows(IllegalArgumentException::class.java) { VisualEmbedding.normalized(floatArrayOf(Float.NaN, 1f)) }
        assertThrows(IllegalArgumentException::class.java) { VisualEmbedding.fromBlob(byteArrayOf(1, 2, 3)) }
        assertThrows(IllegalArgumentException::class.java) {
            VisualEmbedding.normalized(floatArrayOf(1f, 0f)).cosineSimilarity(VisualEmbedding.normalized(floatArrayOf(1f, 0f, 0f)))
        }
    }

    @Test fun `centroid is normalized`() {
        val centroid = VisualEmbedding.centroid(listOf(VisualEmbedding.normalized(floatArrayOf(1f, 0f)), VisualEmbedding.normalized(floatArrayOf(1f, 1f))))
        assertEquals(1f, centroid.cosineSimilarity(centroid), 0.0001f)
    }
}
