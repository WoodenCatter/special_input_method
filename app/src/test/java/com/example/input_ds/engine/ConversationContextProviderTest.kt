package com.example.input_ds.engine

import com.example.input_ds.personalization.ConversationTurn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationContextProviderTest {
    @Test
    fun `keeps only the latest four valid session turns`() = runBlocking {
        val provider = MutableConversationContextProvider()
        (1..5).forEach { index ->
            provider.append(ConversationTurn(if (index % 2 == 0) "other" else "user", "消息$index"))
        }
        provider.append(ConversationTurn("invalid", "不会进入上下文"))

        assertEquals(
            listOf("消息2", "消息3", "消息4", "消息5"),
            provider.getContext().turns.map { it.text }
        )
    }
}
