// app/src/main/java/com/domain/app/core/direct/api/DirectApiService.kt
package com.domain.app.core.direct.api

import retrofit2.http.Body
import retrofit2.http.POST

interface DirectApiService {
    @POST("chat")
    suspend fun sendMessage(@Body request: ChatRequest): ChatResponse
}
