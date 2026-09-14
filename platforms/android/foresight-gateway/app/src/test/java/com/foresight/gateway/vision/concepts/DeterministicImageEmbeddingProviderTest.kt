package com.foresight.gateway.vision.concepts

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class DeterministicImageEmbeddingProviderTest {
    @Test fun `test provider is repeatable and returns normalized embeddings`() {
        val provider = DeterministicImageEmbeddingProvider(8)
        val request = ImageEmbeddingRequest("red insulated bottle".encodeToByteArray())
        assertArrayEquals(provider.embed(request).getOrThrow().values(), provider.embed(request).getOrThrow().values(), 0f)
    }
}

/** Test-only stand-in proving the provider contract without a model binary or inference library. */
private class DeterministicImageEmbeddingProvider(
    override val embeddingDimension: Int,
) : ImageEmbeddingProvider {
    override val backendId = "deterministic-test"
    override val modelId = "none"

    override fun embed(request: ImageEmbeddingRequest): Result<VisualEmbedding> = runCatching {
        val values = FloatArray(embeddingDimension)
        request.imageBytes.forEachIndexed { index, byte ->
            values[index % embeddingDimension] += ((byte.toInt() and 0xff) - 127.5f) / 127.5f
        }
        if (values.all { it == 0f }) values[0] = 1f
        VisualEmbedding.normalized(values)
    }
}
