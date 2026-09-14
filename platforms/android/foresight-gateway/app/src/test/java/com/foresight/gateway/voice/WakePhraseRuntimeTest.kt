package com.foresight.gateway.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePhraseRuntimeTest {
    @Test
    fun `accepts only the three exact wake phrases`() {
        assertEquals(WakePhraseType.FORESIGHT, WakePhraseGrammar.match("Foresight"))
        assertEquals(WakePhraseType.HEY_FORESIGHT, WakePhraseGrammar.match(" hey   foresight "))
        assertEquals(WakePhraseType.OKAY_FORESIGHT, WakePhraseGrammar.match("Okay Foresight"))
        assertEquals(null, WakePhraseGrammar.match("forecast"))
        assertEquals(null, WakePhraseGrammar.match("four sites"))
        assertEquals(null, WakePhraseGrammar.match("hey foresight please help"))
    }

    @Test
    fun `grammar contains only supported phrases`() {
        assertEquals("[\"foresight\",\"hey foresight\",\"okay foresight\"]", WakePhraseGrammar.recognizerGrammarJson())
    }

    @Test
    fun `wake acknowledgements are centralized and rotate deterministically`() {
        val selector = RoundRobinWakeAcknowledgementSelector()
        assertEquals("How may I be of assistance?", selector.next())
        assertEquals("Yes?", selector.next())
        assertEquals("What can I do for you?", selector.next())
        assertEquals("I'm listening.", selector.next())
        assertEquals("How may I be of assistance?", selector.next())
    }

    @Test
    fun `wake suspends for tts voice turns microphone conflicts and lifecycle pause`() {
        assertEquals(WakeSuspensionReason.TTS_SPEAKING, WakeRuntimePolicy.suspensionReason(inputs(voiceState = VoiceRuntimeState.SPEAKING)))
        assertEquals(WakeSuspensionReason.VOICE_TURN_ACTIVE, WakeRuntimePolicy.suspensionReason(inputs(voiceState = VoiceRuntimeState.PROCESSING)))
        assertEquals(
            WakeSuspensionReason.MICROPHONE_UNAVAILABLE,
            WakeRuntimePolicy.suspensionReason(inputs(microphone = MicrophoneAvailability.Unavailable("phone capture"))),
        )
        assertEquals(WakeSuspensionReason.ACTIVITY_NOT_RESUMED, WakeRuntimePolicy.suspensionReason(inputs(activityResumed = false)))
        assertEquals(null, WakeRuntimePolicy.suspensionReason(inputs()))
    }

    @Test
    fun `wake listener hands off exactly one command turn and resumes after idle`() {
        val listener = FakeListener()
        var handoffs = 0
        val controller = WakeRuntimeController(
            listener = listener,
            onStateChanged = {},
            startCommandTurn = { handoffs++; true },
            log = {},
        )
        controller.setEnabled(true, inputs())
        listener.ready()
        assertEquals(WakeRuntimeState.LISTENING, controller.state)

        listener.detect(WakePhraseType.HEY_FORESIGHT)
        listener.detect(WakePhraseType.HEY_FORESIGHT)
        assertEquals(1, handoffs)
        assertEquals(1, listener.stops)
        assertEquals(WakeRuntimeState.HANDING_OFF, controller.state)

        controller.reconcile(inputs(voiceState = VoiceRuntimeState.PROCESSING))
        assertEquals(1, listener.starts)
        controller.reconcile(inputs())
        assertEquals(2, listener.starts)
    }

    @Test
    fun `disabling and activity pause release the listener while enabled resumes`() {
        val listener = FakeListener()
        val controller = WakeRuntimeController(listener, {}, { true }, log = {})
        controller.setEnabled(true, inputs())
        listener.ready()
        controller.reconcile(inputs(activityResumed = false))
        assertEquals(WakeRuntimeState.SUSPENDED, controller.state)
        assertEquals(1, listener.stops)
        controller.reconcile(inputs())
        assertEquals(WakeRuntimeState.STARTING, controller.state)
        assertEquals(2, listener.starts)
        controller.setEnabled(false, inputs())
        assertEquals(WakeRuntimeState.DISABLED, controller.state)
        assertEquals(2, listener.stops)
    }

    @Test
    fun `wake preference store persists enabled value`() {
        val store = FakePreferenceStore()
        assertFalse(store.isEnabled())
        store.setEnabled(true)
        assertTrue(store.isEnabled())
    }

    private fun inputs(
        activityResumed: Boolean = true,
        microphone: MicrophoneAvailability = MicrophoneAvailability.Available,
        voiceState: VoiceRuntimeState = VoiceRuntimeState.IDLE,
    ) = WakeRuntimeInputs(
        activityResumed = activityResumed,
        recordAudioPermissionGranted = true,
        microphoneAvailability = microphone,
        voiceState = voiceState,
    )

    private class FakeListener : WakePhraseListener {
        var starts = 0
        var stops = 0
        private var onReady: (() -> Unit)? = null
        private var onDetected: ((WakePhraseType) -> Unit)? = null
        override fun start(onReady: () -> Unit, onDetected: (WakePhraseType) -> Unit, onFailure: (Throwable) -> Unit) {
            starts++
            this.onReady = onReady
            this.onDetected = onDetected
        }
        fun ready() = onReady?.invoke()
        fun detect(phrase: WakePhraseType) = onDetected?.invoke(phrase)
        override fun stop() {
            stops++
            onReady = null
            onDetected = null
        }
        override fun close() = Unit
    }

    private class FakePreferenceStore : WakePreferenceStore {
        private var enabled = false
        override fun isEnabled(): Boolean = enabled
        override fun setEnabled(enabled: Boolean) { this.enabled = enabled }
    }
}
