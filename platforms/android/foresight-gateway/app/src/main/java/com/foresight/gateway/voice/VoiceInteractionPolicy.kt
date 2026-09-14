package com.foresight.gateway.voice

import com.foresight.gateway.capture.LocalMediaSourceId
import com.foresight.gateway.vision.DetectionSnapshot
import java.util.Locale

class DeterministicVoiceIntentParser {
    // Ordinary language is deliberately handled by VoiceRequestRouter's ConversationEngine.
    fun parse(text: String): ForesightIntent = ForesightIntent(ForesightIntentType.UNKNOWN, 0f)
}

object VisibleObjectsResponseFormatter {
    fun format(snapshot: DetectionSnapshot?): ForesightResponse {
        val labels = snapshot?.detections.orEmpty()
            .map { it.label.trim().lowercase(Locale.US) }
            .filter { it.isNotBlank() }
        if (snapshot == null) return ForesightResponse("I don't have a current view of the scene.")
        if (labels.isEmpty()) return ForesightResponse("I don't recognize any of the objects in view right now.")
        val phrases = labels.groupingBy { it }.eachCount().toSortedMap().map { (label, count) ->
            when (count) {
                1 -> article(label)
                2 -> "two ${plural(label)}"
                else -> "$count ${plural(label)}"
            }
        }
        return ForesightResponse("I see ${joinNaturally(phrases)}.")
    }

    private fun article(label: String): String = "${if (label.firstOrNull()?.let { it in "aeiou" } == true) "an" else "a"} $label"
    private fun plural(label: String): String = when (label) {
        "person" -> "people"
        else -> if (label.endsWith("s")) label else "${label}s"
    }
    private fun joinNaturally(values: List<String>): String = when (values.size) {
        0 -> ""
        1 -> values.first()
        2 -> values.joinToString(" and ")
        else -> values.dropLast(1).joinToString(", ") + ", and " + values.last()
    }
}

object MicrophoneArbiter {
    fun availability(captureActive: Boolean, activeSource: LocalMediaSourceId?): MicrophoneAvailability =
        if (captureActive && activeSource == LocalMediaSourceId.PHONE_CAMERA) {
            MicrophoneAvailability.Unavailable("Microphone is currently in use by phone capture.")
        } else {
            MicrophoneAvailability.Available
        }
}

sealed interface MicrophoneAvailability {
    data object Available : MicrophoneAvailability
    data class Unavailable(val reason: String) : MicrophoneAvailability
}

class PendingInteractionStore {
    private var pending: PendingInteraction? = null

    fun set(value: PendingInteraction) { pending = value }
    fun current(nowElapsedMillis: Long): PendingInteraction? = pending?.takeUnless { it.isExpired(nowElapsedMillis) }
        .also { if (pending?.isExpired(nowElapsedMillis) == true) pending = null }
    fun clear() { pending = null }
}
