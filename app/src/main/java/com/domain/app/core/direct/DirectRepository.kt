// app/src/main/java/com/domain/app/core/direct/DirectRepository.kt
package com.domain.app.core.direct

import com.domain.app.core.direct.api.ChatHistoryItem
import com.domain.app.core.direct.api.ChatMessage
import com.domain.app.core.direct.api.ChatRequest
import com.domain.app.core.direct.api.ChatResponse
import com.domain.app.core.direct.api.DirectApiService
import com.domain.app.core.direct.api.MessageRole
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DirectRepository @Inject constructor(
    private val apiService: DirectApiService
) {
    suspend fun sendMessage(
        message: String,
        conversationHistory: List<ChatMessage>
    ): Result<ChatResponse> {
        return try {
            val historyItems = conversationHistory.map { msg ->
                ChatHistoryItem(
                    role = when (msg.role) {
                        MessageRole.USER -> "user"
                        MessageRole.ASSISTANT -> "assistant"
                    },
                    content = msg.content
                )
            }

            val request = ChatRequest(
                message = message,
                history = historyItems
            )

            val response = apiService.sendMessage(request)

            if (response.error != null) {
                Result.failure(Exception(response.error))
            } else {
                Result.success(response)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
