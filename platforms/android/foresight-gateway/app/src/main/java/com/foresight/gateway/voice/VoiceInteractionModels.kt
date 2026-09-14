package com.foresight.gateway.voice

import com.foresight.gateway.ui.GatewayVisualizationMode
import com.foresight.gateway.vision.DetectionSnapshot

enum class VoiceInputSource { PUSH_TO_TALK }

data class UserUtterance(
    val text: String,
    val confidence: Float?,
    val elapsedRealtimeMillis: Long,
    val source: VoiceInputSource,
)

enum class ForesightIntentType {
    LIST_VISIBLE_OBJECTS,
    IDENTIFY_SELECTED_TARGET,
    REMEMBER_SELECTED_TARGET,
    QUERY_SELECTED_MEMORY,
    READ_SELECTED_TARGET,
    DESCRIBE_SCENE,
    UNKNOWN,
}

data class ForesightIntent(
    val type: ForesightIntentType,
    val confidence: Float,
)

data class InteractionContext(
    val captureActive: Boolean,
    val visualizationMode: GatewayVisualizationMode,
    val latestDetections: DetectionSnapshot?,
    val selectedGestureTargetId: String?,
    val elapsedRealtimeMillis: Long,
)

data class ForesightResponse(
    val text: String,
    val requestSpeech: Boolean = true,
)

enum class VoiceRuntimeState {
    IDLE,
    LISTENING_FOR_COMMAND,
    PROCESSING,
    SPEAKING,
}

enum class VoiceInputReadiness {
    MODEL_LOADING,
    READY,
    FAILED,
}

enum class VoiceInputFailure(val userMessage: String) {
    MODEL_LOADING("Preparing voice..."),
    MODEL_UNAVAILABLE("Offline speech model could not be loaded."),
    MICROPHONE_UNAVAILABLE("Microphone unavailable."),
    MICROPHONE_START_FAILED("Could not start microphone."),
    NO_SPEECH_TIMEOUT("I couldn't hear a command."),
    COMMAND_TIMEOUT("Voice command timed out."),
    RECOGNITION_FAILED("Recognition failed."),
}

class VoiceInputException(val failure: VoiceInputFailure) : IllegalStateException(failure.name)

enum class PendingInteractionType { AWAITING_CONCEPT_NAME }

data class PendingInteraction(
    val type: PendingInteractionType,
    val targetId: String?,
    val expiresAtElapsedMillis: Long,
) {
    fun isExpired(nowElapsedMillis: Long): Boolean = nowElapsedMillis >= expiresAtElapsedMillis
}
