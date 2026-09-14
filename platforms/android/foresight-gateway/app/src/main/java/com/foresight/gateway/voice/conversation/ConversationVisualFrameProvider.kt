package com.foresight.gateway.voice.conversation

interface ConversationVisualFrameProvider : AutoCloseable {
    fun acquire(onComplete: (ConversationVisualFrame?) -> Unit)
    override fun close() = Unit
}

object NoConversationVisualFrameProvider : ConversationVisualFrameProvider {
    override fun acquire(onComplete: (ConversationVisualFrame?) -> Unit) = onComplete(null)
}
