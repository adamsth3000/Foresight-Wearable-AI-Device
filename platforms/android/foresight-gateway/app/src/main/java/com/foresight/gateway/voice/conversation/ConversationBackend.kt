package com.foresight.gateway.voice.conversation

enum class ConversationBackendMode {
    HOSTED,
    LOCAL,
    ;

    companion object {
        fun restore(value: String?): ConversationBackendMode =
            entries.firstOrNull { it.name == value } ?: HOSTED
    }
}

/** Selects a backend without exposing transport details to voice routing or Gateway controls. */
class SelectableConversationEngine(
    private val mode: () -> ConversationBackendMode,
    private val hosted: ConversationEngine,
    private val local: ConversationEngine,
) : ConversationEngine, ConversationMemoryAware {
    private fun active(): ConversationEngine = when (mode()) {
        ConversationBackendMode.HOSTED -> hosted
        ConversationBackendMode.LOCAL -> local
    }

    override fun readiness(): ConversationReadiness = active().readiness()

    override suspend fun respond(request: ConversationRequest): ConversationResponse = active().respond(request)

    override fun cancelActiveRequest() {
        hosted.cancelActiveRequest()
        local.cancelActiveRequest()
    }

    override fun onTrimMemory(level: Int) {
        (hosted as? ConversationMemoryAware)?.onTrimMemory(level)
        (local as? ConversationMemoryAware)?.onTrimMemory(level)
    }

    override fun close() {
        hosted.close()
        local.close()
    }
}

interface ConversationMemoryAware {
    fun onTrimMemory(level: Int)
}
