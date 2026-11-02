package com.example.input_ds.communication

import org.json.JSONObject

data class ChatContact(
    val principalId: String,
    val name: String,
    val conversationId: String,
    val online: Boolean,
    val unread: Int,
    val lastMessage: String?
) {
    companion object {
        fun fromJson(json: JSONObject): ChatContact = ChatContact(
            json.getString("principal_id"), json.optString("display_name", "联系人"),
            json.getString("conversation_id"), json.optBoolean("online"),
            json.optInt("unread_count"), json.optJSONObject("last_message")?.optString("text")
        )
    }
}

data class ChatMessage(
    val id: String,
    val conversationId: String,
    val clientId: String?,
    val seq: Long,
    val senderId: String,
    val text: String,
    val createdAt: String
) {
    companion object {
        fun fromJson(json: JSONObject): ChatMessage = ChatMessage(
            json.getString("id"), json.getString("conversation_id"),
            json.optString("client_message_id").takeIf { it.isNotBlank() },
            json.getLong("server_seq"), json.getString("sender_principal_id"),
            json.getString("text"), json.getString("created_at")
        )
    }
}

enum class CommunicationStage { CONTACT_LIST, CONTACT_ACTIONS, CHAT_INPUT, PAIRING }

data class PendingMessage(val conversationId: String, val clientId: String, val text: String)
