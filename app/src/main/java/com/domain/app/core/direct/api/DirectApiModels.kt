// app/src/main/java/com/domain/app/core/direct/api/DirectApiModels.kt
package com.domain.app.core.direct.api

import com.google.gson.annotations.SerializedName
import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

enum class MessageRole {
    @SerializedName("user")
    USER,
    @SerializedName("assistant")
    ASSISTANT
}

data class ChatRequest(
    val message: String,
    val history: List<ChatHistoryItem> = emptyList()
)

data class ChatHistoryItem(
    val role: String,
    val content: String
)

data class ChatResponse(
    val response: String,
    val error: String? = null
)
