package com.foresight.gateway.voice.conversation

/** Builds only text supplied to the local model; unavailable fields remain explicit. */
class ConversationRequestFormatter(
    private val contextRenderer: ForesightContextRenderer = ForesightContextRenderer(),
) {
    fun format(request: ConversationRequest): String = buildString {
        append(contextRenderer.render(request.context))
        append("\n\nUSER\n")
        append(request.utterance.trim())
    }
}
