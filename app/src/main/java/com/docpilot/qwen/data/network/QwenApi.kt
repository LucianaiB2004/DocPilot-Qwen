package com.docpilot.qwen.data.network

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface QwenApi {
    @POST("chat/completions")
    suspend fun chat(
        @Header("Authorization") authorization: String,
        @Body request: QwenChatRequest
    ): Response<QwenChatResponse>
}

data class QwenChatRequest(
    val model: String,
    val messages: List<QwenMessage>,
    val temperature: Double = 0.2,
    @SerializedName("max_tokens") val maxTokens: Int? = null
)

data class QwenMessage(
    val role: String,
    val content: Any
)

data class QwenChatResponse(
    val choices: List<QwenChoice> = emptyList()
)

data class QwenChoice(
    val message: QwenResponseMessage? = null,
    val delta: QwenResponseMessage? = null
)

data class QwenResponseMessage(
    val role: String? = null,
    val content: Any? = null,
    @SerializedName("reasoning_content") val reasoningContent: String? = null
)

