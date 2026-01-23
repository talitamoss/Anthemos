// app/src/main/java/com/domain/app/ui/direct/DirectViewModel.kt
package com.domain.app.ui.direct

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.domain.app.core.direct.DirectRepository
import com.domain.app.core.direct.api.ChatMessage
import com.domain.app.core.direct.api.MessageRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DirectUiState(
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class DirectViewModel @Inject constructor(
    private val directRepository: DirectRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DirectUiState())
    val uiState: StateFlow<DirectUiState> = _uiState.asStateFlow()

    fun updateInputText(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun sendMessage() {
        val currentInput = _uiState.value.inputText.trim()
        if (currentInput.isEmpty() || _uiState.value.isLoading) return

        val userMessage = ChatMessage(
            role = MessageRole.USER,
            content = currentInput
        )

        _uiState.update { state ->
            state.copy(
                messages = state.messages + userMessage,
                inputText = "",
                isLoading = true,
                error = null
            )
        }

        viewModelScope.launch {
            val result = directRepository.sendMessage(
                message = currentInput,
                conversationHistory = _uiState.value.messages.dropLast(1)
            )

            result.fold(
                onSuccess = { response ->
                    val assistantMessage = ChatMessage(
                        role = MessageRole.ASSISTANT,
                        content = response.response
                    )
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages + assistantMessage,
                            isLoading = false
                        )
                    }
                },
                onFailure = { exception ->
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            error = exception.message ?: "Failed to send message"
                        )
                    }
                }
            )
        }
    }

    fun clearConversation() {
        _uiState.update {
            DirectUiState()
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
