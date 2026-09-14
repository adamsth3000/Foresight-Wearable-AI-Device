package com.foresight.gateway.vision.concepts

/** Conservative nearest-concept matching over normalized embeddings; it never changes trust state. */
class ConceptRecognitionEngine(private val config: Config = Config()) {
    data class Config(
        val minimumSimilarity: Float = 0.82f,
        val supportSimilarity: Float = 0.78f,
        val maximumExamplesPerConcept: Int = 3,
    ) {
        init {
            require(minimumSimilarity in -1f..1f && supportSimilarity in -1f..1f)
            require(maximumExamplesPerConcept > 0)
        }
    }

    data class ConceptMatch(
        val conceptId: String,
        val name: String,
        val similarity: Float,
        val confidence: Float,
        val supportingExampleCount: Int,
    )

    fun recognize(
        query: VisualEmbedding,
        concepts: Collection<VisualConcept>,
        examples: Collection<VisualConceptExample>,
    ): Result<List<ConceptMatch>> = runCatching {
        val names = concepts.associateBy { it.id }
        examples.groupBy { it.conceptId }.mapNotNull { (conceptId, conceptExamples) ->
            val concept = names[conceptId] ?: return@mapNotNull null
            val similarities = conceptExamples.map { query.cosineSimilarity(it.embedding) }.sortedDescending()
            if (similarities.isEmpty()) return@mapNotNull null
            val topMean = similarities.take(config.maximumExamplesPerConcept).average().toFloat()
            val centroidSimilarity = query.cosineSimilarity(VisualEmbedding.centroid(conceptExamples.map { it.embedding }))
            val similarity = (topMean * 0.7f + centroidSimilarity * 0.3f).coerceIn(-1f, 1f)
            if (similarity < config.minimumSimilarity) return@mapNotNull null
            val support = similarities.count { it >= config.supportSimilarity }
            ConceptMatch(concept.id, concept.name, similarity, (similarity * (0.75f + 0.25f * support.coerceAtMost(3) / 3f)).coerceIn(0f, 1f), support)
        }.sortedWith(compareByDescending<ConceptMatch> { it.similarity }.thenBy { it.name }.thenBy { it.conceptId })
    }
}
