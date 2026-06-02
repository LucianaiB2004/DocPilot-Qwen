package com.docpilot.qwen.data.network

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Body
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
    ): Response<TextInParseResponse>

    @POST("ai/service/v2/entity_extraction")
    suspend fun extractEntities(
        @Header("x-ti-app-id") appId: String,
        @Header("x-ti-secret-code") secretCode: String,
        @Body request: TextInExtractionRequest
    ): Response<TextInExtractionResponse>
}

data class TextInParseResponse(
    val code: Int? = null,
    val message: String? = null,
    val result: TextInParseResult? = null,
    val data: TextInParseResult? = null
)

data class TextInParseResult(
    val markdown: String? = null,
    val md: String? = null,
    val text: String? = null,
    val content: String? = null,
    @SerializedName("page_count") val pageCount: Int? = null,
    val pages: List<Map<String, Any>>? = null
)

data class TextInExtractionRequest(
    val file: String,
    val prompt: String,
    val fields: List<TextInExtractionField> = emptyList(),
    @SerializedName("table_fields") val tableFields: List<TextInExtractionTable> = emptyList()
)

data class TextInExtractionField(
    val name: String,
    val description: String = ""
)

data class TextInExtractionTable(
    val title: String,
    val fields: List<TextInExtractionField>,
    val description: String = ""
)

data class TextInExtractionResponse(
    val version: String? = null,
    val code: Int? = null,
    val message: String? = null,
    val duration: Long? = null,
    val result: JsonElement? = null,
    val data: JsonElement? = null
)

