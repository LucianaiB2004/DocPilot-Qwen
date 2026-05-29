package com.docpilot.qwen.data.network

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface QwenApi {
    @POST("chat/completions")
    suspend fun chat(
        @Header("Authorization") authorization: String,
        @Body request: QwenChatRequest
    ): QwenChatResponse
}

data class QwenChatRequest(
    val model: String,
    val messages: List<QwenMessage>,
    val temperature: Double = 0.2
)

data class QwenMessage(
    val role: String,
    val content: String
)

data class QwenChatResponse(
    val choices: List<QwenChoice> = emptyList()
)

data class QwenChoice(
    val message: QwenMessage
)

