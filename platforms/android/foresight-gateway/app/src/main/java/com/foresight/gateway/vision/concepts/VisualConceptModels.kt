package com.foresight.gateway.vision.concepts

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import kotlin.math.sqrt

enum class VisualConceptState { CANDIDATE, SUPPORTED, LEARNED }
enum class VisualConceptType { OBJECT, PRODUCT, PLACE, OTHER }
enum class VisualConceptRelationType { IS_A, VARIANT_OF, RELATED_TO }
enum class ConceptEvidenceSource { USER_CONFIRMED, EMBEDDING_MATCH, DETECTOR_LABEL, VLM_PROPOSAL, OCR, LOCATION_CONTEXT }

/** Source-neutral image-space region. It does not imply a stored image crop. */
data class NormalizedVisualRegion(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f })
        require(left <= right && top <= bottom)
    }
}

data class VisualConcept(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val definition: String? = null,
    val state: VisualConceptState = VisualConceptState.CANDIDATE,
    val type: VisualConceptType = VisualConceptType.OTHER,
    val parentConceptId: String? = null,
    val confidence: Float = 0f,
    val provenance: String,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant,
    val exampleCount: Int = 0,
) {
    init {
        require(id.isNotBlank() && name.isNotBlank() && provenance.isNotBlank())
        require(confidence.isFinite() && confidence in 0f..1f)
        require(exampleCount >= 0)
    }
}

data class VisualConceptExample(
    val id: String,
    val conceptId: String,
    val embedding: VisualEmbedding,
    val sourceFrameId: Long? = null,
    val observedAt: Instant,
    val boundingBox: NormalizedVisualRegion? = null,
    val confidence: Float,
    /** Opaque private reference only; no crop bytes are persisted by this foundation. */
    val cropReference: String? = null,
) {
    init {
        require(id.isNotBlank() && conceptId.isNotBlank())
        require(confidence.isFinite() && confidence in 0f..1f)
    }
}

data class ConceptEvidence(
    val id: String,
    val conceptId: String,
    val source: ConceptEvidenceSource,
    val value: String,
    val confidence: Float,
    val observedAt: Instant,
) {
    init {
        require(id.isNotBlank() && conceptId.isNotBlank() && value.isNotBlank())
        require(confidence.isFinite() && confidence in 0f..1f)
    }
}

data class VisualConceptRelation(
    val id: String,
    val sourceConceptId: String,
    val targetConceptId: String,
    val type: VisualConceptRelationType,
) {
    init { require(id.isNotBlank() && sourceConceptId.isNotBlank() && targetConceptId.isNotBlank()) }
}

/** Immutable, L2-normalized embedding with deterministic binary serialization. */
class VisualEmbedding private constructor(private val coordinates: FloatArray) {
    val dimension: Int get() = coordinates.size

    fun values(): FloatArray = coordinates.copyOf()

    fun cosineSimilarity(other: VisualEmbedding): Float {
        require(dimension == other.dimension) { "Embedding dimensions differ: $dimension and ${other.dimension}." }
        return coordinates.indices.sumOf { index -> (coordinates[index] * other.coordinates[index]).toDouble() }
            .toFloat().coerceIn(-1f, 1f)
    }

    fun toBlob(): ByteArray = ByteBuffer.allocate(HEADER_BYTES + dimension * Float.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(MAGIC)
        .putInt(VERSION)
        .putInt(dimension)
        .also { buffer -> coordinates.forEach(buffer::putFloat) }
        .array()

    override fun equals(other: Any?): Boolean = other is VisualEmbedding && coordinates.contentEquals(other.coordinates)
    override fun hashCode(): Int = coordinates.contentHashCode()
    override fun toString(): String = "VisualEmbedding(dimension=$dimension)"

    companion object {
        private const val MAGIC = 0x46564345 // FVCE: Foresight Visual Concept Embedding
        private const val VERSION = 1
        private const val HEADER_BYTES = Int.SIZE_BYTES * 3
        private const val NORMALIZATION_TOLERANCE = 0.001f

        fun normalized(values: FloatArray): VisualEmbedding {
            validateFinite(values)
            val magnitude = sqrt(values.sumOf { coordinate -> (coordinate * coordinate).toDouble() }).toFloat()
            require(magnitude.isFinite() && magnitude > 0f) { "Embedding magnitude must be positive and finite." }
            return VisualEmbedding(FloatArray(values.size) { index -> values[index] / magnitude })
        }

        fun fromNormalized(values: FloatArray): VisualEmbedding {
            validateFinite(values)
            val magnitude = sqrt(values.sumOf { coordinate -> (coordinate * coordinate).toDouble() }).toFloat()
            require(kotlin.math.abs(magnitude - 1f) <= NORMALIZATION_TOLERANCE) { "Embedding must be L2 normalized." }
            return VisualEmbedding(values.copyOf())
        }

        fun fromBlob(blob: ByteArray): VisualEmbedding {
            require(blob.size >= HEADER_BYTES) { "Embedding blob is truncated." }
            val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
            require(buffer.int == MAGIC && buffer.int == VERSION) { "Unsupported embedding blob." }
            val dimension = buffer.int
            require(dimension > 0 && blob.size == HEADER_BYTES + dimension * Float.SIZE_BYTES) { "Malformed embedding blob dimensions." }
            return fromNormalized(FloatArray(dimension) { buffer.float })
        }

        fun centroid(embeddings: Collection<VisualEmbedding>): VisualEmbedding {
            require(embeddings.isNotEmpty()) { "Cannot calculate an empty embedding centroid." }
            val first = embeddings.first()
            val sum = FloatArray(first.dimension)
            embeddings.forEach { embedding ->
                require(embedding.dimension == first.dimension) { "Embedding dimensions differ." }
                embedding.coordinates.indices.forEach { index -> sum[index] += embedding.coordinates[index] }
            }
            return normalized(sum)
        }

        private fun validateFinite(values: FloatArray) {
            require(values.isNotEmpty()) { "Embedding cannot be empty." }
            require(values.all { it.isFinite() }) { "Embedding values must be finite." }
        }
    }
}
