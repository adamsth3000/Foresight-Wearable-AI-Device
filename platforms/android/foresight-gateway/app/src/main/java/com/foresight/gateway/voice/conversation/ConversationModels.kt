package com.foresight.gateway.voice.conversation

import com.foresight.gateway.vision.SceneEvidenceSnapshot
import com.foresight.gateway.vision.SceneLocation
import com.foresight.gateway.vision.geolocation.VisualGeolocationResult

enum class ConversationReadiness {
    MODEL_NOT_INSTALLED,
    MODEL_DOWNLOADING,
    READY_NOT_LOADED,
    LOADING,
    READY,
    FAILED,
}

enum class ConversationTurnRole { USER, ASSISTANT }

data class ConversationTurn(
    val role: ConversationTurnRole,
    val text: String,
    val timestampElapsedMillis: Long,
)

data class ObservedObject(
    val label: String,
    val confidence: Float,
)

data class SelectedVisualContext(
    val targetId: String,
    val label: String? = null,
)

enum class ContextAvailability { AVAILABLE, UNAVAILABLE }

data class ForesightContextSnapshot(
    val captureActive: Boolean,
    val visualizationMode: String,
    val observationAgeMillis: Long?,
    val observedObjects: List<ObservedObject>,
    val selectedVisualContext: SelectedVisualContext?,
    val visualConceptMemory: ContextAvailability,
    val timestampElapsedMillis: Long,
    val sceneEvidence: SceneEvidenceSnapshot? = null,
    /** Location retained for nearby Maps grounding when scene evidence has aged beyond 30 seconds. */
    val mapsLocation: SceneLocation? = null,
    val geolocation: VisualGeolocationResult? = null,
)

enum class ProposedDeviceActionType { START_CAPTURE, END_CAPTURE }

data class ProposedDeviceAction(
    val type: ProposedDeviceActionType,
    val source: String,
)

data class ConversationRequest(
    val utterance: String,
    val context: ForesightContextSnapshot,
    val history: List<ConversationTurn>,
    val selectedVisualContext: SelectedVisualContext?,
    val timestampElapsedMillis: Long,
    val visualFrame: ConversationVisualFrame? = null,
)

data class ConversationVisualFrame(
    val jpegBytes: ByteArray,
    val width: Int,
    val height: Int,
    val capturedAtElapsedMillis: Long,
    val source: String,
    val sourceFrameId: Long? = null,
) {
    fun clear() = jpegBytes.fill(0)
}

enum class GroundingSourceType { GOOGLE_SEARCH, GOOGLE_MAPS }

data class GroundingSource(
    val title: String?,
    val url: String?,
    val sourceType: GroundingSourceType,
    val attribution: String? = null,
    val placeId: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class ConversationResponse(
    val spokenText: String,
    val displayText: String? = null,
    val proposedAction: ProposedDeviceAction? = null,
    val confidence: Float? = null,
    val modelIdentity: String,
    val groundingSources: List<GroundingSource> = emptyList(),
    val usedVisualContext: Boolean = false,
    val usedGoogleSearch: Boolean = false,
    val usedGoogleMaps: Boolean = false,
)

interface ConversationEngine : AutoCloseable {
    fun readiness(): ConversationReadiness
    suspend fun respond(request: ConversationRequest): ConversationResponse
    fun cancelActiveRequest()
}
