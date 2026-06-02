package com.docpilot.qwen.data.local

import android.content.Context
import com.alibaba.mnnllm.android.llm.GenerateProgressListener
import com.alibaba.mnnllm.android.llm.LlmSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class LocalGenerationConfig(
    val threads: Int,
    val accelerationEngine: String,
    val maxTokens: Int = 768,
    val imagePaths: List<String> = emptyList(),
    val enableMnn: Boolean = false
) {
    val backendType: Int
        get() = if (accelerationEngine == "MNN (Arm SME2)") 13 else 0

    val precision: String
        get() = if (accelerationEngine == "MNN (Arm SME2)") "low" else "normal"
}

interface LocalModelEngine {
    fun runtimeStatus(): String
    suspend fun summarize(markdown: String, config: LocalGenerationConfig): String?
    suspend fun answer(markdown: String, question: String, config: LocalGenerationConfig): String?
    suspend fun answer(markdown: String, question: String, config: LocalGenerationConfig, onPartial: suspend (String) -> Unit): String?
    suspend fun complete(prompt: String, config: LocalGenerationConfig): String?
    suspend fun complete(prompt: String, config: LocalGenerationConfig, onPartial: suspend (String) -> Unit): String?
}

class MnnQwenEngine(
    context: Context,
    private val modelManager: LocalModelManager
) : LocalModelEngine {
    private val modelRoot = File(context.getExternalFilesDir(null), "models")

    override fun runtimeStatus(): String {
        return when {
            !MnnLlmNative.isRuntimeLoadable -> "MNN 原生推理暂不可用"
            selectedConfigFile() == null -> "未选择 MNN 模型包"
            else -> "MNN 就绪"
        }
    }

    override suspend fun summarize(markdown: String, config: LocalGenerationConfig): String? {
        val prompt = """
            请基于下面文档内容生成简洁摘要，使用中文 Markdown。

            文档内容：
            $markdown
        """.trimIndent()
        return complete(prompt, config)
    }

    override suspend fun answer(markdown: String, question: String, config: LocalGenerationConfig): String? {
        val prompt = """
            你是本地文档问答助手。只基于下面文档内容回答问题，信息不足时直接说明。

            文档内容：
            $markdown

            问题：
            $question
        """.trimIndent()
        return complete(prompt, config)
    }

    override suspend fun answer(
        markdown: String,
        question: String,
        config: LocalGenerationConfig,
        onPartial: suspend (String) -> Unit
    ): String? {
        val prompt = """
            你是本地文档问答助手。只基于下面文档内容回答问题，信息不足时直接说明。
            文档内容：
            $markdown

            问题：
            $question
        """.trimIndent()
        return complete(prompt, config, onPartial)
    }

    override suspend fun complete(prompt: String, config: LocalGenerationConfig): String? {
        return complete(prompt, config) {}
    }

    override suspend fun complete(
        prompt: String,
        config: LocalGenerationConfig,
        onPartial: suspend (String) -> Unit
    ): String? {
        if (!config.enableMnn) return null
        val configFile = selectedConfigFile() ?: return null
        if (!MnnLlmNative.isRuntimeLoadable) return null
        return withContext(Dispatchers.IO) {
            MnnLlmNative.generate(
                configPath = configFile.absolutePath,
                prompt = prompt,
                threads = config.threads.coerceIn(1, 8),
                backendType = config.backendType,
                precision = config.precision,
                maxTokens = config.maxTokens,
                imagePaths = config.imagePaths.takeIf { modelManager.selectedModel().contains("VL", ignoreCase = true) }.orEmpty(),
                onPartial = onPartial
            )
        }?.takeIf { it.isNotBlank() }
    }

    private fun selectedConfigFile(): File? {
        val selected = modelManager.selectedModel()
        val state = modelManager.states.value[selected] ?: return null
        val path = state.localPath.takeIf { it.isNotBlank() } ?: return null
        return resolveConfigFile(File(path))
    }

    private fun resolveConfigFile(file: File): File? {
        val candidates = listOf(
            file,
            File(file, "config.json"),
            File(modelRoot, file.nameWithoutExtension).resolve("config.json")
        )
        return candidates.firstOrNull { it.isFile && it.name == "config.json" }
    }
}

object MnnLlmNative {
    private const val ENABLE_NATIVE_MNN = false

    val isRuntimeLoadable: Boolean by lazy {
        if (!ENABLE_NATIVE_MNN) return@lazy false
        runCatching {
            System.loadLibrary("c++_shared")
            System.loadLibrary("MNN")
            System.loadLibrary("docpilot_mnn_llm")
        }.isSuccess
    }

    fun generate(
        configPath: String,
        prompt: String,
        threads: Int,
        backendType: Int,
        precision: String,
        maxTokens: Int,
        imagePaths: List<String> = emptyList(),
        onPartial: suspend (String) -> Unit = {}
    ): String? {
        if (!isRuntimeLoadable) return null
        return runCatching {
            val generated = StringBuilder()
            val runtimeConfig = """
                {
                  "backend_type": $backendType,
                  "precision": "$precision",
                  "thread_num": $threads,
                  "max_new_tokens": $maxTokens
                }
            """.trimIndent()
            val session = LlmSession(configPath)
            try {
                if (!session.init(runtimeConfig)) return@runCatching null
                val finalText = session.submit(prompt, imagePaths, object : GenerateProgressListener {
                    override fun onProgress(progress: String?): Boolean {
                        if (!progress.isNullOrBlank()) {
                            generated.append(progress)
                            kotlinx.coroutines.runBlocking { onPartial(generated.toString()) }
                        }
                        return false
                    }
                })
                finalText.ifBlank { generated.toString() }
            } finally {
                session.release()
            }
        }.getOrNull()
    }
}
