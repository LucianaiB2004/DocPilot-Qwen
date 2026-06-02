package com.docpilot.qwen.data.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class QwenStreamClient(
    private val baseUrl: String,
    private val client: OkHttpClient,
    private val gson: Gson = Gson()
) {
    suspend fun streamChat(
        authorization: String,
        request: QwenChatRequest,
        onDelta: suspend (String) -> Unit
    ): String {
        val streamingBody = mapOf(
            "model" to request.model,
            "messages" to request.messages,
            "temperature" to request.temperature,
            "stream" to true
        )
        val httpRequest = Request.Builder()
            .url("${baseUrl.ensureTrailingSlash()}chat/completions")
            .header("Authorization", authorization)
            .header("Accept", "text/event-stream")
            .post(gson.toJson(streamingBody).toRequestBody("application/json".toMediaType()))
            .build()

        val full = StringBuilder()
        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                val errorText = response.body?.string().orEmpty().take(600)
                error("Qwen stream failed: HTTP ${response.code}${errorText.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty()}")
            }
            val source = response.body?.source() ?: return ""
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val delta = parseDelta(data)
                if (delta.isNotBlank()) {
                    full.append(delta)
                    onDelta(delta)
                }
            }
        }
        return full.toString()
    }

    private fun parseDelta(data: String): String {
        return runCatching {
            val root = gson.fromJson(data, JsonObject::class.java)
            val choice = root.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject ?: return@runCatching ""
            val payload = choice.getAsJsonObject("delta") ?: choice.getAsJsonObject("message") ?: return@runCatching ""
            payload.get("content")?.let { content ->
                when {
                    content.isJsonNull -> ""
                    content.isJsonPrimitive -> content.asString
                    content.isJsonArray -> content.asJsonArray.joinToString("") { item ->
                        item.asJsonObject?.get("text")?.asString.orEmpty()
                    }
                    else -> ""
                }
            } ?: ""
        }.getOrDefault("")
    }

    private fun String.ensureTrailingSlash(): String = if (endsWith("/")) this else "$this/"
}
