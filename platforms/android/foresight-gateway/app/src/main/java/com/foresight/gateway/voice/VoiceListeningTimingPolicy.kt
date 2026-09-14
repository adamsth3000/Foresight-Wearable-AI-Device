package com.foresight.gateway.voice

/** Pure timing contract for one bounded command after the listening cue has completed. */
data class VoiceListeningTiming(
    val cueGuardMillis: Long = 150L,
    val maxStartMillis: Long = 5_000L,
    val maxCommandMillis: Long = 8_000L,
    val trailingSilenceMillis: Long = 1_000L,
)

class VoiceListeningTimingPolicy(private val timing: VoiceListeningTiming = VoiceListeningTiming()) {
    private var speechStarted = false

    fun onDecoderText(text: String): VoiceListeningDecision {
        if (text.isBlank()) return VoiceListeningDecision.KeepListening
        speechStarted = true
        return VoiceListeningDecision.ScheduleTrailingSilence(timing.trailingSilenceMillis)
    }

    fun onStartTimeout(): VoiceListeningDecision =
        if (speechStarted) VoiceListeningDecision.KeepListening else VoiceListeningDecision.EndNoSpeech

    fun onCommandTimeout(): VoiceListeningDecision = VoiceListeningDecision.EndCommandTimeout
}

sealed interface VoiceListeningDecision {
    data object KeepListening : VoiceListeningDecision
    data class ScheduleTrailingSilence(val delayMillis: Long) : VoiceListeningDecision
    data object EndNoSpeech : VoiceListeningDecision
    data object EndCommandTimeout : VoiceListeningDecision
}
