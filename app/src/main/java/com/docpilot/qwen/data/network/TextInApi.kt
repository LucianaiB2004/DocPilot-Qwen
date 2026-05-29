package com.docpilot.qwen.data.network

import okhttp3.MultipartBody
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface TextInApi {
    @Multipart
    @POST("api/v1/xparse/parse/sync")
    suspend fun parseSync(
        @Header("x-ti-app-id") appId: String,
        @Header("x-ti-secret-code") secretCode: String,
        @Part file: MultipartBody.Part
    ): TextInParseResponse
}

data class TextInParseResponse(
    val code: Int? = null,
    val message: String? = null,
    val result: TextInParseResult? = null
)

data class TextInParseResult(
    val markdown: String? = null,
    val pages: List<Map<String, Any>>? = null
)

