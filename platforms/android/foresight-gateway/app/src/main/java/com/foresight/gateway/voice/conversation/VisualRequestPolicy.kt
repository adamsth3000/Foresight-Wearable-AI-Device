package com.foresight.gateway.voice.conversation

data class VisualContextDecision(val includeImage: Boolean, val reason: String)

/** Replaceable MVP policy; false positives are preferable to denying visual grounding. */
object VisualRequestPolicy {
    fun decide(utterance: String): VisualContextDecision {
        val normalized = utterance.lowercase()
        val visualTerms = listOf(
            "what is that", "what is this", "what am i looking", "describe", "room", "painting",
            "color", "colour", "next to", "beside", "look unusual", "model of", "where would i buy",
            "who made", "who painted", "tell me about", "visible", "see", "sign", "read", "label", "menu", "written", "writing", "text", "brand", "room number", "plaque", "summarize", "building", "restaurant", "store", "monument", "place am i looking", "business", "statue",
        )
        return if (visualTerms.any(normalized::contains)) VisualContextDecision(true, "visual_language")
        else VisualContextDecision(false, "general_language")
    }
}
