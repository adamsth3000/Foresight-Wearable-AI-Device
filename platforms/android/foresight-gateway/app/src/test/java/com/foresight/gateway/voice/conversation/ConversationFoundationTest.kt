package com.foresight.gateway.voice.conversation

import com.foresight.gateway.ui.GatewayVisualizationMode
import com.foresight.gateway.vision.DetectionSnapshot
import com.foresight.gateway.vision.LatestDetectionState
import com.foresight.gateway.vision.LiveDetectionPresentation
import com.foresight.gateway.vision.LiveNormalizedBoundingBox
import com.foresight.gateway.vision.SceneEvidenceSnapshot
import com.foresight.gateway.vision.SceneLocation
import com.foresight.gateway.voice.InteractionContextAssembler
import com.foresight.gateway.voice.UserUtterance
import com.foresight.gateway.voice.VoiceInputSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationFoundationTest {
    @Test
    fun `arbitrary ordinary utterances route to the fake conversation engine`() {
        val engine = RecordingEngine()
        val router = router(engine)

        listOf("why would someone use a chair?", "tell me something interesting", "what is a bicycle used for?")
            .forEach { utterance ->
                var response: ConversationResponse? = null
                router.route(userUtterance(utterance)) { response = it.getOrThrow() }
                assertEquals("fake-conversation-engine", response?.modelIdentity)
            }

        assertEquals(3, engine.requests.size)
        assertEquals("what is a bicycle used for?", engine.requests.last().utterance)
    }

    @Test
    fun `explicit device actions bypass the conversation engine without execution`() {
        val engine = RecordingEngine()
        var response: ConversationResponse? = null

        router(engine).route(userUtterance("Start capture")) { response = it.getOrThrow() }

        assertTrue(engine.requests.isEmpty())
        assertEquals(ProposedDeviceActionType.START_CAPTURE, response?.proposedAction?.type)
        assertEquals("Voice device control is not enabled yet.", response?.spokenText)
    }

    @Test
    fun `context renderer is fresh bounded deterministic and honest about unavailable data`() {
        val rendered = ForesightContextRenderer().render(contextSnapshot(objects = listOf(
            ObservedObject("bottle", 0.91f),
            ObservedObject("chair", 0.80f),
            ObservedObject("bottle", 0.88f),
        )))

        assertTrue(rendered.contains("bottle x2, confidence 0.88-0.91"))
        assertTrue(rendered.contains("chair x1, confidence 0.80-0.80"))
        assertTrue(rendered.contains("location: unavailable"))
        assertTrue(rendered.contains("ocr: unavailable"))
    }

    @Test
    fun `stale detection is omitted by the snapshot assembler`() {
        val latest = LatestDetectionState { 5_000_000_000L }.apply { publish(detectionSnapshot(1_000_000_000L)) }
        val assembler = InteractionContextAssembler(
            captureActive = { true },
            visualizationMode = { GatewayVisualizationMode.VISION },
            latestDetectionState = latest,
            elapsedRealtimeMillis = { 5_000L },
            maxDetectionAgeNanos = 1_000_000_000L,
        )

        val snapshot = ForesightContextSnapshotAssembler(assembler::assemble).assemble()

        assertTrue(snapshot.observedObjects.isEmpty())
        assertNull(snapshot.observationAgeMillis)
    }

    @Test
    fun `history bounds turns characters and supports reset`() {
        val history = ConversationHistory(maximumTurns = 3, maximumCharacters = 10)
        history.append(ConversationTurn(ConversationTurnRole.USER, "one", 1L))
        history.append(ConversationTurn(ConversationTurnRole.ASSISTANT, "two", 2L))
        history.append(ConversationTurn(ConversationTurnRole.USER, "three", 3L))
        history.append(ConversationTurn(ConversationTurnRole.ASSISTANT, "four", 4L))

        assertTrue(history.snapshot().size <= 3)
        assertTrue(history.snapshot().sumOf { it.text.length } <= 10)
        history.clear()
        assertTrue(history.snapshot().isEmpty())
    }

    @Test
    fun `conversation failure is returned without a fallback parser`() {
        var result: Result<ConversationResponse>? = null
        router(FailingEngine()).route(userUtterance("tell me something interesting")) { result = it }
        assertTrue(result?.isFailure == true)
    }

    @Test
    fun `model request text contains only rendered current context and the utterance`() {
        val request = ConversationRequest(
            utterance = " What is interesting around me? ",
            context = contextSnapshot(listOf(ObservedObject("bottle", 0.91f))),
            history = emptyList(),
            selectedVisualContext = null,
            timestampElapsedMillis = 1L,
        )

        val formatted = ConversationRequestFormatter().format(request)

        assertTrue(formatted.startsWith("FORESIGHT CURRENT CONTEXT"))
        assertTrue(formatted.contains("bottle x1, confidence 0.91-0.91"))
        assertTrue(formatted.contains("location: unavailable"))
        assertTrue(formatted.endsWith("USER\nWhat is interesting around me?"))
    }

    @Test
    fun `context renders phone location separately from heading`() {
        val rendered = ForesightContextRenderer().render(
            contextSnapshot(emptyList()).copy(
                timestampElapsedMillis = 2_000L,
                sceneEvidence = SceneEvidenceSnapshot(
                    generation = 1L,
                    capturedAtNanos = 2_000_000_000L,
                    location = SceneLocation(42.0, -71.0, 9f, 12.0, 1_000L, "gps"),
                ),
            ),
        )

        assertTrue(rendered.contains("location: phone_position"))
        assertTrue(rendered.contains("accuracy_m=9.0"))
        assertTrue(rendered.contains("scene_heading: unavailable"))
    }

    @Test
    fun `resource policy refuses low memory and severe thermal load`() {
        val policy = ConversationResourcePolicy(minimumAvailableMemoryBytes = 100L, severeThermalStatus = 4)
        assertTrue(policy.refusal(ConversationResourceSnapshot(99L, 0))?.contains("heavy load") == true)
        assertTrue(policy.refusal(ConversationResourceSnapshot(1_000L, 4))?.contains("heavy load") == true)
        assertNull(policy.refusal(ConversationResourceSnapshot(1_000L, 3)))
    }

    @Test
    fun `readiness messages distinguish model setup loading and failure`() {
        assertEquals("Local AI model setup is required.", ConversationReadiness.MODEL_NOT_INSTALLED.userMessage())
        assertEquals("Foresight AI is still loading.", ConversationReadiness.LOADING.userMessage())
        assertEquals("Local AI is unavailable right now.", ConversationReadiness.FAILED.userMessage())
    }

    @Test
    fun `geographic request awaits refresh without blocking normal request completion`() {
        val engine = RecordingEngine()
        var refreshRequested = false
        var completed = false
        val router = DefaultVoiceRequestRouter(
            actionParser = DeviceActionParser(),
            conversationEngine = engine,
            contextAssembler = ForesightContextSnapshotAssembler(
                interactionContext = ::interactionContext,
                mapsLocation = { null },
            ),
            history = ConversationHistory(),
            locationRefreshRequester = object : LocationRefreshRequester {
                override fun refreshIfNeeded(utterance: String, location: SceneLocation?, nowElapsedMillis: Long, onComplete: () -> Unit) {
                    refreshRequested = true
                    onComplete() // Models the bounded timeout/degraded completion path.
                }
            },
        )

        router.route(userUtterance("Where am I?")) { completed = it.isSuccess }

        assertTrue(refreshRequested)
        assertTrue(completed)
        assertEquals(1, engine.requests.size)
    }

    private fun router(engine: ConversationEngine): DefaultVoiceRequestRouter = DefaultVoiceRequestRouter(
        actionParser = DeviceActionParser(),
        conversationEngine = engine,
        contextAssembler = ForesightContextSnapshotAssembler(interactionContext = ::interactionContext),
        history = ConversationHistory(),
    )

    private fun interactionContext() = InteractionContextAssembler(
        captureActive = { true },
        visualizationMode = { GatewayVisualizationMode.VISION },
        latestDetectionState = LatestDetectionState { 1_000_000_000L }.apply { publish(detectionSnapshot(900_000_000L)) },
        elapsedRealtimeMillis = { 1_000L },
        maxDetectionAgeNanos = 1_000_000_000L,
    ).assemble()

    private fun contextSnapshot(objects: List<ObservedObject>) = ForesightContextSnapshot(
        captureActive = true,
        visualizationMode = "VISION",
        observationAgeMillis = 420L,
        observedObjects = objects,
        selectedVisualContext = null,
        visualConceptMemory = ContextAvailability.UNAVAILABLE,
        timestampElapsedMillis = 1_000L,
    )

    private fun detectionSnapshot(captureNanos: Long) = DetectionSnapshot(
        runtimeGeneration = 1L,
        frameId = 1L,
        captureElapsedRealtimeNanos = captureNanos,
        sourceWidth = 100,
        sourceHeight = 100,
        detections = listOf(LiveDetectionPresentation("chair", 0.9f, LiveNormalizedBoundingBox(0f, 0f, 1f, 1f))),
    )

    private fun userUtterance(text: String) = UserUtterance(text, null, 1_000L, VoiceInputSource.PUSH_TO_TALK)

    private class RecordingEngine : ConversationEngine {
        val requests = mutableListOf<ConversationRequest>()
        override fun readiness() = ConversationReadiness.READY
        override suspend fun respond(request: ConversationRequest): ConversationResponse {
            requests += request
            return FakeConversationEngine().respond(request)
        }
        override fun cancelActiveRequest() = Unit
        override fun close() = Unit
    }

    private class FailingEngine : ConversationEngine {
        override fun readiness() = ConversationReadiness.READY
        override suspend fun respond(request: ConversationRequest): ConversationResponse = error("unavailable")
        override fun cancelActiveRequest() = Unit
        override fun close() = Unit
    }
}
