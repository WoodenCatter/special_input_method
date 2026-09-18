package com.example.input_ds.engine

import com.example.input_ds.personalization.ConversationTurn

data class ConversationContext(
    val turns: List<ConversationTurn> = emptyList(),
    val knownEntities: List<String> = emptyList()
)

interface ConversationContextProvider {
    suspend fun getContext(): ConversationContext
}

/** The Activity is not a system IME yet, so the safe default is no external chat history. */
class EmptyConversationContextProvider : ConversationContextProvider {
    override suspend fun getContext(): ConversationContext = ConversationContext()
}

/** Session-scoped bridge for manual/demo context now and a future IME/chat integration later. */
class MutableConversationContextProvider : ConversationContextProvider {
    @Volatile
    private var context = ConversationContext()

    override suspend fun getContext(): ConversationContext = context

    @Synchronized
    fun update(value: ConversationContext) {
        context = value.copy(turns = value.turns.takeLast(MAX_TURNS))
    }

    @Synchronized
    fun append(turn: ConversationTurn) {
        if (turn.role != "user" && turn.role != "other") return
        if (turn.text.isBlank()) return
        context = context.copy(turns = (context.turns + turn).takeLast(MAX_TURNS))
    }

    private companion object {
        const val MAX_TURNS = 4
    }
}
