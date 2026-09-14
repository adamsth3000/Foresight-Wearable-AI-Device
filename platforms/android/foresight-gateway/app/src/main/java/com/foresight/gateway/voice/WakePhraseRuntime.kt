package com.foresight.gateway.voice

import android.util.Log
import org.json.JSONArray
import java.util.Locale

enum class WakeRuntimeState {
    DISABLED,
    STARTING,
    LISTENING,
    SUSPENDED,
    WAKE_DETECTED,
    HANDING_OFF,
    STOPPED,
    FAILED,
}

enum class WakePhraseType {
    FORESIGHT,
    HEY_FORESIGHT,
    OKAY_FORESIGHT,
}

object WakePhraseGrammar {
    private val phrases = mapOf(
        "foresight" to WakePhraseType.FORESIGHT,
        "hey foresight" to WakePhraseType.HEY_FORESIGHT,
        "okay foresight" to WakePhraseType.OKAY_FORESIGHT,
    )

    /** Vosk receives only these exact phrases; arbitrary speech is not continuously decoded. */
    fun recognizerGrammarJson(): String = JSONArray(phrases.keys.toList()).toString()

    fun match(recognizedText: String): WakePhraseType? =
        phrases[recognizedText.trim().lowercase(Locale.US).replace(Regex("\\s+"), " ")]
}

/** Centralized, short acknowledgements played only for a successful wake handoff. */
object WakeAcknowledgements {
    val phrases = listOf(
        "How may I be of assistance?",
        "Yes?",
        "What can I do for you?",
        "I'm listening.",
    )
}

class RoundRobinWakeAcknowledgementSelector(
    private val phrases: List<String> = WakeAcknowledgements.phrases,
) {
    private var nextIndex = 0

    fun next(): String {
        check(phrases.isNotEmpty()) { "Wake acknowledgements must not be empty." }
        return phrases[nextIndex++ % phrases.size]
    }
}

enum class WakeSuspensionReason {
    ACTIVITY_NOT_RESUMED,
    RECORD_AUDIO_PERMISSION_REQUIRED,
    TTS_SPEAKING,
    VOICE_TURN_ACTIVE,
    MICROPHONE_UNAVAILABLE,
}

data class WakeRuntimeInputs(
    val activityResumed: Boolean,
    val recordAudioPermissionGranted: Boolean,
    val microphoneAvailability: MicrophoneAvailability,
    val voiceState: VoiceRuntimeState,
)

object WakeRuntimePolicy {
    fun suspensionReason(inputs: WakeRuntimeInputs): WakeSuspensionReason? = when {
        !inputs.activityResumed -> WakeSuspensionReason.ACTIVITY_NOT_RESUMED
        !inputs.recordAudioPermissionGranted -> WakeSuspensionReason.RECORD_AUDIO_PERMISSION_REQUIRED
        inputs.voiceState == VoiceRuntimeState.SPEAKING -> WakeSuspensionReason.TTS_SPEAKING
        inputs.voiceState != VoiceRuntimeState.IDLE -> WakeSuspensionReason.VOICE_TURN_ACTIVE
        inputs.microphoneAvailability is MicrophoneAvailability.Unavailable -> WakeSuspensionReason.MICROPHONE_UNAVAILABLE
        else -> null
    }
}

interface WakePhraseListener : AutoCloseable {
    fun start(
        onReady: () -> Unit,
        onDetected: (WakePhraseType) -> Unit,
        onFailure: (Throwable) -> Unit,
    )

    fun stop()
}

/** Owns wake-listening state and never starts a command turn until its listener has released audio. */
class WakeRuntimeController(
    private val listener: WakePhraseListener,
    private val onStateChanged: (WakeRuntimeState) -> Unit,
    private val startCommandTurn: () -> Boolean,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) : AutoCloseable {
    var state: WakeRuntimeState = WakeRuntimeState.DISABLED
        private set
    var enabled: Boolean = false
        private set
    private var lastSuspensionReason: WakeSuspensionReason? = null

    fun setEnabled(value: Boolean, inputs: WakeRuntimeInputs) {
        if (enabled == value) {
            reconcile(inputs)
            return
        }
        enabled = value
        if (value) log("FORESIGHT_WAKE_ENABLED") else log("FORESIGHT_WAKE_DISABLED")
        reconcile(inputs)
    }

    fun reconcile(inputs: WakeRuntimeInputs) {
        if (!enabled) {
            stopListener()
            transitionTo(WakeRuntimeState.DISABLED)
            return
        }
        if (state == WakeRuntimeState.FAILED) return
        if (state == WakeRuntimeState.WAKE_DETECTED) return
        if (state == WakeRuntimeState.HANDING_OFF) {
            // VoiceTurnController is authoritative for the command turn. It returns to IDLE only
            // after command handling and speech have completed, at which point wake may resume.
            if (inputs.voiceState != VoiceRuntimeState.IDLE) return
            transitionTo(WakeRuntimeState.SUSPENDED)
        }

        val reason = WakeRuntimePolicy.suspensionReason(inputs)
        if (reason != null) {
            suspend(reason)
            return
        }
        if (state == WakeRuntimeState.LISTENING || state == WakeRuntimeState.STARTING) return
        if (lastSuspensionReason != null) log("FORESIGHT_WAKE_RESUMED")
        lastSuspensionReason = null
        transitionTo(WakeRuntimeState.STARTING)
        log("FORESIGHT_WAKE_LISTENER_START")
        listener.start(
            onReady = {
                if (enabled && state == WakeRuntimeState.STARTING) {
                    transitionTo(WakeRuntimeState.LISTENING)
                    log("FORESIGHT_WAKE_LISTENER_READY")
                }
            },
            onDetected = ::onWakeDetected,
            onFailure = { error ->
                if (enabled && state != WakeRuntimeState.DISABLED && state != WakeRuntimeState.STOPPED) {
                    if (state == WakeRuntimeState.STARTING || state == WakeRuntimeState.LISTENING) {
                        log("FORESIGHT_WAKE_LISTENER_STOP")
                    }
                    transitionTo(WakeRuntimeState.FAILED)
                    log("FORESIGHT_WAKE_FAILURE type=${error.javaClass.simpleName}")
                }
            },
        )
    }

    private fun suspend(reason: WakeSuspensionReason) {
        stopListener()
        if (lastSuspensionReason != reason || state != WakeRuntimeState.SUSPENDED) {
            log("FORESIGHT_WAKE_SUSPENDED reason=$reason")
        }
        lastSuspensionReason = reason
        transitionTo(WakeRuntimeState.SUSPENDED)
    }

    private fun onWakeDetected(phrase: WakePhraseType) {
        if (!enabled || state != WakeRuntimeState.LISTENING) return
        transitionTo(WakeRuntimeState.WAKE_DETECTED)
        log("FORESIGHT_WAKE_DETECTED phraseType=$phrase")
        stopListener()
        transitionTo(WakeRuntimeState.HANDING_OFF)
        log("FORESIGHT_WAKE_HANDOFF_START")
        if (startCommandTurn()) {
            log("FORESIGHT_WAKE_HANDOFF_COMPLETE")
        } else {
            transitionTo(WakeRuntimeState.SUSPENDED)
            lastSuspensionReason = WakeSuspensionReason.VOICE_TURN_ACTIVE
            log("FORESIGHT_WAKE_SUSPENDED reason=${WakeSuspensionReason.VOICE_TURN_ACTIVE}")
        }
    }

    private fun stopListener() {
        if (state == WakeRuntimeState.LISTENING || state == WakeRuntimeState.STARTING || state == WakeRuntimeState.WAKE_DETECTED) {
            listener.stop()
            log("FORESIGHT_WAKE_LISTENER_STOP")
        }
    }

    private fun transitionTo(next: WakeRuntimeState) {
        if (state == next) return
        state = next
        onStateChanged(next)
    }

    override fun close() {
        stopListener()
        listener.close()
        transitionTo(WakeRuntimeState.STOPPED)
    }

    private companion object {
        const val TAG = "ForesightWake"
    }
}

interface WakePreferenceStore {
    fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean)
}
