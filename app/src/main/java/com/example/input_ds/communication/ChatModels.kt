package com.example.input_ds.communication

import org.json.JSONObject

data class ChatContact(
    val principalId: String,
    val name: String,
    val conversationId: String,
    val online: Boolean,
    val unread: Int,
    val lastMessage: String?,
    val relation: String? = null,
    val avatarHue: Float? = null
) {
    companion object {
        fun fromJson(json: JSONObject): ChatContact = ChatContact(
            principalId = json.getString("principal_id"),
            name = json.optString("display_name", "联系人"),
            conversationId = json.getString("conversation_id"),
            online = json.optBoolean("online"),
            unread = json.optInt("unread_count"),
            lastMessage = json.optJSONObject("last_message")?.optString("text"),
            relation = json.optString("relation").takeIf { it.isNotBlank() }
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

data class PendingMessage(
    val conversationId: String,
    val clientId: String,
    val text: String,
    val createdAt: String = ""
)
