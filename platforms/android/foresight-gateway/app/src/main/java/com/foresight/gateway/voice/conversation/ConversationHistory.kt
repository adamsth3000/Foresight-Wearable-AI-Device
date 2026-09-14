package com.foresight.gateway.voice.conversation

class ConversationHistory(
    private val maximumTurns: Int = 6,
    private val maximumCharacters: Int = 2_400,
) {
    private val turns = ArrayDeque<ConversationTurn>()

    init {
        require(maximumTurns > 0)
        require(maximumCharacters > 0)
    }

    fun snapshot(): List<ConversationTurn> = turns.toList()

    fun append(turn: ConversationTurn) {
        turns += turn.copy(text = turn.text.trim().take(maximumCharacters))
        trim()
    }

    fun clear() = turns.clear()

    private fun trim() {
        while (turns.size > maximumTurns || turns.sumOf { it.text.length } > maximumCharacters) {
            turns.removeFirst()
        }
    }
}
