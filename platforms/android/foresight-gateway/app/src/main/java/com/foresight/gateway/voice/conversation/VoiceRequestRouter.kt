package com.foresight.gateway.voice.conversation

import com.foresight.gateway.voice.InteractionContext
import com.foresight.gateway.voice.UserUtterance
import com.foresight.gateway.vision.SceneLocation
import com.foresight.gateway.vision.geolocation.NoVisualGeolocationResolver
import com.foresight.gateway.vision.geolocation.VisualGeolocationQueryPolicy
import com.foresight.gateway.vision.geolocation.VisualGeolocationResolver
import java.util.Locale
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

interface VoiceRequestRouter : AutoCloseable {
    fun readiness(): ConversationReadiness
    fun route(utterance: UserUtterance, onComplete: (Result<ConversationResponse>) -> Unit)
    fun onTrimMemory(level: Int) = Unit
}

interface LocationRefreshRequester {
    fun refreshIfNeeded(
        utterance: String,
        location: SceneLocation?,
        nowElapsedMillis: Long,
        onComplete: () -> Unit,
    )
}

class DeviceActionParser {
    fun parse(utterance: String): ProposedDeviceAction? = when (normalize(utterance)) {
        "start capture" -> ProposedDeviceAction(ProposedDeviceActionType.START_CAPTURE, "deterministic-device-action-parser")
        "end capture", "stop capture" -> ProposedDeviceAction(ProposedDeviceActionType.END_CAPTURE, "deterministic-device-action-parser")
        else -> null
    }

    private fun normalize(value: String): String = value.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

class DefaultVoiceRequestRouter(
    private val actionParser: DeviceActionParser,
    private val conversationEngine: ConversationEngine,
    private val contextAssembler: ForesightContextSnapshotAssembler,
    private val history: ConversationHistory,
    private val visualFrameProvider: ConversationVisualFrameProvider = NoConversationVisualFrameProvider,
    private val visualContextEnabled: () -> Boolean = { false },
    private val locationRefreshRequester: LocationRefreshRequester? = null,
    private val geolocationResolver: VisualGeolocationResolver = NoVisualGeolocationResolver,
) : VoiceRequestRouter {
    override fun readiness(): ConversationReadiness = conversationEngine.readiness()

    override fun route(utterance: UserUtterance, onComplete: (Result<ConversationResponse>) -> Unit) {
        actionParser.parse(utterance.text)?.let { action ->
            onComplete(
                Result.success(
                    ConversationResponse(
                        spokenText = "Voice device control is not enabled yet.",
                        proposedAction = action,
                        modelIdentity = action.source,
                    ),
                ),
            )
            return
        }
        if (conversationEngine.readiness() !in setOf(ConversationReadiness.READY, ConversationReadiness.READY_NOT_LOADED)) {
            onComplete(Result.failure(ConversationUnavailableException(conversationEngine.readiness().userMessage())))
            return
        }
        val initialSnapshot = contextAssembler.assemble()
        val decision = if (visualContextEnabled()) VisualRequestPolicy.decide(utterance.text) else VisualContextDecision(false, "hosted_not_selected")
        android.util.Log.i("ForesightGoogleAI", "FORESIGHT_VISUAL_CONTEXT_DECISION included=${decision.includeImage} reason=${decision.reason}")
        val proceedWithSnapshot: (ForesightContextSnapshot) -> Unit = { snapshot ->
            val proceedWithGeolocation: (com.foresight.gateway.vision.geolocation.VisualGeolocationResult, ConversationVisualFrame?) -> Unit = { geolocation, frame ->
            val request = ConversationRequest(
                utterance = utterance.text,
                context = snapshot.copy(geolocation = geolocation),
                history = history.snapshot(),
                selectedVisualContext = snapshot.selectedVisualContext,
                timestampElapsedMillis = utterance.elapsedRealtimeMillis,
                visualFrame = frame,
            )
            suspend { conversationEngine.respond(request) }.startCoroutine(
            object : Continuation<ConversationResponse> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<ConversationResponse>) {
                    result.onSuccess { response ->
                        history.append(ConversationTurn(ConversationTurnRole.USER, utterance.text, utterance.elapsedRealtimeMillis))
                        history.append(ConversationTurn(ConversationTurnRole.ASSISTANT, response.spokenText, utterance.elapsedRealtimeMillis))
                    }
                    onComplete(result)
                }
            },
        )
            }
            val geoKind = VisualGeolocationQueryPolicy.classify(utterance.text)
            val withFrame: (ConversationVisualFrame?) -> Unit = { capturedFrame ->
                if (geoKind == com.foresight.gateway.vision.geolocation.VisualGeolocationQueryKind.NONE) proceedWithGeolocation(com.foresight.gateway.vision.geolocation.VisualGeolocationResult(null, unresolvedReason = "not_geographic"), capturedFrame)
                else geolocationResolver.resolve(utterance.text, snapshot.sceneEvidence, snapshot.mapsLocation, capturedFrame) { result -> proceedWithGeolocation(result, capturedFrame) }
            }
            if (decision.includeImage) visualFrameProvider.acquire(withFrame) else withFrame(null)
        }
        val refresh = locationRefreshRequester
        if (refresh != null && MapsGroundingPolicy.requiresLocationRefresh(utterance.text, initialSnapshot.mapsLocation, utterance.elapsedRealtimeMillis)) {
            refresh.refreshIfNeeded(utterance.text, initialSnapshot.mapsLocation, utterance.elapsedRealtimeMillis) {
                proceedWithSnapshot(contextAssembler.assemble())
            }
        } else {
            proceedWithSnapshot(initialSnapshot)
        }
    }

    override fun close() {
        history.clear()
        conversationEngine.cancelActiveRequest()
        conversationEngine.close()
        visualFrameProvider.close()
    }

    override fun onTrimMemory(level: Int) {
        (conversationEngine as? ConversationMemoryAware)?.onTrimMemory(level)
    }
}

fun ConversationReadiness.userMessage(): String = when (this) {
    ConversationReadiness.MODEL_NOT_INSTALLED -> "Local AI model setup is required."
    ConversationReadiness.MODEL_DOWNLOADING -> "Local AI model is downloading."
    ConversationReadiness.LOADING -> "Foresight AI is still loading."
    ConversationReadiness.FAILED -> "Local AI is unavailable right now."
    ConversationReadiness.READY_NOT_LOADED, ConversationReadiness.READY -> "Local AI is unavailable right now."
}

class ForesightContextSnapshotAssembler(
    private val interactionContext: () -> InteractionContext,
    private val visualConceptMemoryAvailability: () -> ContextAvailability = { ContextAvailability.UNAVAILABLE },
    private val sceneEvidence: () -> com.foresight.gateway.vision.SceneEvidenceSnapshot? = { null },
    private val mapsLocation: () -> SceneLocation? = { null },
) {
    fun assemble(): ForesightContextSnapshot {
        val context = interactionContext()
        val snapshot = context.latestDetections
        val ageMillis = snapshot?.let { (context.elapsedRealtimeMillis - it.captureElapsedRealtimeNanos / 1_000_000L).coerceAtLeast(0L) }
        return ForesightContextSnapshot(
            captureActive = context.captureActive,
            visualizationMode = context.visualizationMode.name,
            observationAgeMillis = ageMillis,
            observedObjects = snapshot?.detections.orEmpty().map { ObservedObject(it.label, it.confidence) },
            selectedVisualContext = context.selectedGestureTargetId?.let { SelectedVisualContext(targetId = it) },
            visualConceptMemory = visualConceptMemoryAvailability(),
            timestampElapsedMillis = context.elapsedRealtimeMillis,
            sceneEvidence = sceneEvidence(),
            mapsLocation = mapsLocation(),
        )
    }
}

class FakeConversationEngine(
    private val contextRenderer: ForesightContextRenderer = ForesightContextRenderer(),
) : ConversationEngine {
    override fun readiness(): ConversationReadiness = ConversationReadiness.READY

    override suspend fun respond(request: ConversationRequest): ConversationResponse = ConversationResponse(
        spokenText = "I received your question: ${request.utterance.trim()}. " +
            "Current Foresight context includes ${contextRenderer.inlineSummary(request.context)}.",
        modelIdentity = "fake-conversation-engine",
    )

    override fun cancelActiveRequest() = Unit
    override fun close() = Unit
}
