package com.foresight.gateway.vision.concepts

/**
 * Boundary for a future on-device image embedding backend. This module supplies no production
 * implementation or model asset; callers own image decoding and provider lifecycle.
 */
interface ImageEmbeddingProvider : AutoCloseable {
    val backendId: String
    val modelId: String
    val embeddingDimension: Int
    fun embed(request: ImageEmbeddingRequest): Result<VisualEmbedding>
    override fun close() = Unit
}

data class ImageEmbeddingRequest(
    val imageBytes: ByteArray,
    val sourceFrameId: Long? = null,
    val boundingBox: NormalizedVisualRegion? = null,
) {
    init { require(imageBytes.isNotEmpty()) { "Embedding input cannot be empty." } }
}
