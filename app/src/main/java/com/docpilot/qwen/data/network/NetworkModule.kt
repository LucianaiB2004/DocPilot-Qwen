package com.docpilot.qwen.data.network

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class NetworkModule(qwenBaseUrl: String, textInBaseUrl: String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            val start = System.currentTimeMillis()
            Log.i("DocPilot", "HTTP start ${request.method} ${request.url}")
            try {
                val response = chain.proceed(request)
                Log.i("DocPilot", "HTTP done ${response.code} ${request.url} ${System.currentTimeMillis() - start}ms")
                response
            } catch (error: Throwable) {
                Log.e("DocPilot", "HTTP failed ${request.url} ${System.currentTimeMillis() - start}ms", error)
                throw error
            }
        }
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .build()

    val qwenApi: QwenApi = Retrofit.Builder()
        .baseUrl(qwenBaseUrl.ensureTrailingSlash())
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(QwenApi::class.java)

    val qwenStreamClient = QwenStreamClient(
        baseUrl = qwenBaseUrl,
        client = client
    )

    val textInApi: TextInApi = Retrofit.Builder()
        .baseUrl(textInBaseUrl.ensureTrailingSlash())
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(TextInApi::class.java)

    private fun String.ensureTrailingSlash(): String = if (endsWith("/")) this else "$this/"
}
