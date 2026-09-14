package com.foresight.gateway.voice

import com.foresight.gateway.capture.LocalMediaSourceId
import com.foresight.gateway.ui.GatewayVisualizationMode
import com.foresight.gateway.vision.DetectionSnapshot
import com.foresight.gateway.vision.LatestDetectionState
import com.foresight.gateway.vision.LiveDetectionPresentation
import com.foresight.gateway.vision.LiveNormalizedBoundingBox
import com.foresight.gateway.voice.conversation.ConversationResponse
import com.foresight.gateway.voice.conversation.VoiceRequestRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInteractionPolicyTest {
    private val parser = DeterministicVoiceIntentParser()

    @Test
    fun `does not phrase match ordinary language`() {
        listOf("What do you see?", "WHAT AM I LOOKING AT", "what's in front of me", "Tell me what you see")
            .forEach { assertEquals(ForesightIntentType.UNKNOWN, parser.parse(it).type) }
    }

    @Test
    fun `keeps future vision intents for the conversation engine`() {
        listOf("Remember this", "describe scene", "please order coffee")
            .forEach { assertEquals(ForesightIntentType.UNKNOWN, parser.parse(it).type) }
    }

    @Test
    fun `formats empty single duplicate and multiple detections deterministically`() {
        assertEquals("I don't recognize any of the objects in view right now.", VisibleObjectsResponseFormatter.format(snapshot()).text)
        assertEquals("I see a chair.", VisibleObjectsResponseFormatter.format(snapshot("chair")).text)
        assertEquals("I see two bottles and two people.", VisibleObjectsResponseFormatter.format(snapshot("person", "bottle", "person", "bottle")).text)
        assertEquals("I see a bottle, a chair, and a laptop.", VisibleObjectsResponseFormatter.format(snapshot("laptop", "chair", "bottle")).text)
    }

    @Test
    fun `latest detection state only returns fresh snapshots`() {
        var now = 10_000_000_000L
        val state = LatestDetectionState { now }
        state.publish(snapshot(captureNanos = 8_000_000_000L, labels = arrayOf("person")))
        assertTrue(state.freshSnapshot(3_000_000_000L) != null)
        now = 12_000_000_001L
        assertNull(state.freshSnapshot(3_000_000_000L))
        state.clear()
        assertNull(state.latestSnapshot())
    }

    @Test
    fun `microphone arbitration protects active phone capture only`() {
        assertTrue(MicrophoneArbiter.availability(false, null) is MicrophoneAvailability.Available)
        assertTrue(MicrophoneArbiter.availability(true, LocalMediaSourceId.GOPRO_RTMP) is MicrophoneAvailability.Available)
        val unavailable = MicrophoneArbiter.availability(true, LocalMediaSourceId.PHONE_CAMERA)
        assertEquals("Microphone is currently in use by phone capture.", (unavailable as MicrophoneAvailability.Unavailable).reason)
    }

    @Test
    fun `pending interaction expires and clears`() {
        val store = PendingInteractionStore()
        store.set(PendingInteraction(PendingInteractionType.AWAITING_CONCEPT_NAME, "target", 100L))
        assertEquals(PendingInteractionType.AWAITING_CONCEPT_NAME, store.current(99L)?.type)
        assertNull(store.current(100L))
        store.set(PendingInteraction(PendingInteractionType.AWAITING_CONCEPT_NAME, null, 200L))
        store.clear()
        assertNull(store.current(101L))
    }

    @Test
    fun `pre speech silence is tolerated while speech arms trailing silence`() {
        val policy = VoiceListeningTimingPolicy(VoiceListeningTiming(maxStartMillis = 5_000L, trailingSilenceMillis = 1_000L))
        assertEquals(VoiceListeningDecision.KeepListening, policy.onDecoderText(""))
        assertEquals(VoiceListeningDecision.EndNoSpeech, policy.onStartTimeout())

        val afterSpeech = VoiceListeningTimingPolicy(VoiceListeningTiming(trailingSilenceMillis = 1_000L))
        assertEquals(VoiceListeningDecision.ScheduleTrailingSilence(1_000L), afterSpeech.onDecoderText("what do you see"))
        assertEquals(VoiceListeningDecision.KeepListening, afterSpeech.onStartTimeout())
        assertEquals(VoiceListeningDecision.EndCommandTimeout, afterSpeech.onCommandTimeout())
    }

    @Test
    fun `voice state machine is single turn and returns idle after speech`() {
        val input = FakeInput()
        val speech = FakeSpeech()
        val states = mutableListOf<VoiceRuntimeState>()
        val controller = VoiceTurnController(
            microphoneAvailability = { MicrophoneAvailability.Available },
            audioInput = input,
            audioCueOutput = FakeCue(),
            speechOutput = speech,
            requestRouter = ImmediateRouter(),
            onStateChanged = states::add,
            onResponse = {},
        )
        controller.startPushToTalk()
        controller.startPushToTalk()
        assertEquals(1, input.requests)
        input.complete(UserUtterance("what do you see", null, 1L, VoiceInputSource.PUSH_TO_TALK))
        assertEquals(VoiceRuntimeState.SPEAKING, controller.state)
        speech.complete(Result.success(Unit))
        assertEquals(VoiceRuntimeState.IDLE, controller.state)
        assertEquals(listOf(VoiceRuntimeState.LISTENING_FOR_COMMAND, VoiceRuntimeState.PROCESSING, VoiceRuntimeState.SPEAKING, VoiceRuntimeState.IDLE), states)
    }

    @Test
    fun `recognition and speech failures return idle without affecting context`() {
        val input = FakeInput()
        val speech = FakeSpeech()
        val responses = mutableListOf<String>()
        val controller = VoiceTurnController(
            microphoneAvailability = { MicrophoneAvailability.Available },
            audioInput = input,
            audioCueOutput = FakeCue(),
            speechOutput = speech,
            requestRouter = ImmediateRouter(),
            onStateChanged = {},
            onResponse = { responses += it.text },
        )
        controller.startPushToTalk()
        input.fail(IllegalStateException("mic unavailable"))
        assertEquals(VoiceRuntimeState.IDLE, controller.state)
        assertEquals("Recognition failed.", responses.last())
        controller.startPushToTalk()
        input.complete(UserUtterance("what do you see", null, 1L, VoiceInputSource.PUSH_TO_TALK))
        speech.complete(Result.failure(IllegalStateException("no offline tts")))
        assertEquals(VoiceRuntimeState.IDLE, controller.state)
    }

    @Test
    fun `model readiness and input failures remain distinguishable and return idle`() {
        val input = FakeInput(readiness = VoiceInputReadiness.MODEL_LOADING)
        val responses = mutableListOf<String>()
        val states = mutableListOf<VoiceRuntimeState>()
        val controller = VoiceTurnController(
            microphoneAvailability = { MicrophoneAvailability.Available },
            audioInput = input,
            audioCueOutput = FakeCue(),
            speechOutput = FakeSpeech(),
            requestRouter = ImmediateRouter(),
            onStateChanged = states::add,
            onResponse = { responses += it.text },
        )
        controller.startPushToTalk()
        assertEquals("Preparing voice...", responses.last())
        assertEquals(VoiceRuntimeState.IDLE, controller.state)
        assertFalse(states.contains(VoiceRuntimeState.LISTENING_FOR_COMMAND))

        input.readiness = VoiceInputReadiness.FAILED
        controller.startPushToTalk()
        assertEquals("Offline speech model could not be loaded.", responses.last())
        assertEquals(VoiceRuntimeState.IDLE, controller.state)
        assertFalse(states.contains(VoiceRuntimeState.LISTENING_FOR_COMMAND))

        input.readiness = VoiceInputReadiness.READY
        controller.startPushToTalk()
        input.fail(VoiceInputException(VoiceInputFailure.MICROPHONE_UNAVAILABLE))
        assertEquals("Microphone unavailable.", responses.last())
        assertEquals(VoiceRuntimeState.IDLE, controller.state)
    }

    @Test
    fun `wake acknowledgement completes before command cue and failure falls back to command listening`() {
        val input = FakeInput()
        val speech = FakeSpeech()
        val cue = FakeCue()
        val controller = VoiceTurnController(
            microphoneAvailability = { MicrophoneAvailability.Available },
            audioInput = input,
            audioCueOutput = cue,
            speechOutput = speech,
            requestRouter = ImmediateRouter(),
            onStateChanged = {},
            onResponse = {},
        )
        var started = 0
        var completed = 0
        var failed = 0
        assertTrue(controller.startWakeAcknowledgedTurn("Yes?", { started++ }, { completed++ }, { failed++ }))
        assertEquals(1, started)
        assertEquals(0, input.requests)
        assertEquals(0, cue.plays)

        speech.complete(Result.success(Unit))
        assertEquals(1, completed)
        assertEquals(0, failed)
        assertEquals(1, input.requests)
        assertEquals(1, cue.plays)

        input.complete(UserUtterance("question", null, 1L, VoiceInputSource.PUSH_TO_TALK))
        speech.complete(Result.success(Unit))
        assertEquals(VoiceRuntimeState.IDLE, controller.state)

        val retryInput = FakeInput()
        val retrySpeech = FakeSpeech()
        val retryCue = FakeCue()
        val retry = VoiceTurnController(
            microphoneAvailability = { MicrophoneAvailability.Available },
            audioInput = retryInput,
            audioCueOutput = retryCue,
            speechOutput = retrySpeech,
            requestRouter = ImmediateRouter(),
            onStateChanged = {},
            onResponse = {},
        )
        retry.startWakeAcknowledgedTurn("Yes?", {}, {}, { failed++ })
        retrySpeech.complete(Result.failure(IllegalStateException("tts unavailable")))
        assertEquals(1, failed)
        assertEquals(1, retryInput.requests)
        assertEquals(1, retryCue.plays)
    }

    private fun contextAssembler(): InteractionContextAssembler = InteractionContextAssembler(
        captureActive = { true },
        visualizationMode = { GatewayVisualizationMode.VISION },
        latestDetectionState = LatestDetectionState { 1_000L }.apply { publish(snapshot(captureNanos = 1_000L, labels = arrayOf("person"))) },
        elapsedRealtimeMillis = { 1L },
        maxDetectionAgeNanos = 1_000L,
    )

    private fun snapshot(vararg labels: String, captureNanos: Long = 1L): DetectionSnapshot = DetectionSnapshot(
        runtimeGeneration = 1L,
        frameId = 1L,
        captureElapsedRealtimeNanos = captureNanos,
        sourceWidth = 100,
        sourceHeight = 100,
        detections = labels.map { label ->
            LiveDetectionPresentation(label, 0.9f, LiveNormalizedBoundingBox(0f, 0f, 1f, 1f))
        },
    )

    private class FakeInput(var readiness: VoiceInputReadiness = VoiceInputReadiness.READY) : AudioInputAdapter {
        var requests = 0
        private var callback: ((Result<UserUtterance>) -> Unit)? = null
        private var onReady: (() -> Unit)? = null
        override fun readiness(): VoiceInputReadiness = readiness
        override fun prepareOnce(onMicrophoneReady: () -> Unit, onResult: (Result<UserUtterance>) -> Unit) {
            requests++
            onReady = onMicrophoneReady
            callback = onResult
            onMicrophoneReady()
        }
        override fun startListening() = Unit
        fun complete(utterance: UserUtterance) { callback?.invoke(Result.success(utterance)); callback = null }
        fun fail(error: Throwable) { callback?.invoke(Result.failure(error)); callback = null }
        override fun cancel() { callback = null }
        override fun close() = Unit
    }

    private class FakeSpeech : SpeechOutput {
        private var callback: ((Result<Unit>) -> Unit)? = null
        override fun speak(text: String, onComplete: (Result<Unit>) -> Unit) { callback = onComplete }
        fun complete(result: Result<Unit>) { callback?.invoke(result); callback = null }
        override fun close() = Unit
    }

    private class FakeCue : AudioCueOutput {
        var plays = 0
        override fun playListeningCue(onComplete: () -> Unit) { plays++; onComplete() }
        override fun close() = Unit
    }

    private class ImmediateRouter : VoiceRequestRouter {
        override fun readiness() = com.foresight.gateway.voice.conversation.ConversationReadiness.READY
        override fun route(utterance: UserUtterance, onComplete: (Result<ConversationResponse>) -> Unit) = onComplete(
            Result.success(ConversationResponse("Fake response", modelIdentity = "test")),
        )
        override fun close() = Unit
    }
}
