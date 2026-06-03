package com.docpilot.qwen.data.local

import android.content.Context
import com.alibaba.mnnllm.android.llm.GenerateProgressListener
import com.alibaba.mnnllm.android.llm.LlmSession
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
        get() = 0

    val backendName: String
        get() = "cpu"

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
    private val appContext = context.applicationContext
    private val modelRoot = File(context.getExternalFilesDir(null), "models")
    private val mmapRoot = File(appContext.cacheDir, "mnn_mmap").also { it.mkdirs() }
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    override fun runtimeStatus(): String {
        return when {
            !MnnLlmNative.isRuntimeLoadable -> MnnLlmNative.loadFailureMessage()
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
        val runtimeConfig = mergedRuntimeConfig(configFile, config)
        val extraConfig = extraNativeConfig(configFile)
        val supportsVision = selectedModelSupportsVision(configFile)
        return withContext(Dispatchers.IO) {
            MnnLlmNative.generate(
                configPath = configFile.absolutePath,
                prompt = prompt,
                mergedConfigJson = runtimeConfig,
                extraConfigJson = extraConfig,
                imagePaths = config.imagePaths.takeIf { supportsVision }.orEmpty(),
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
        candidates.firstOrNull { it.isFile && it.name == "config.json" }?.let { return it }
        return file.singleContentDirectory()?.let { File(it, "config.json") }?.takeIf { it.isFile }
    }

    private fun File.singleContentDirectory(): File? {
        val children = listFiles()
            ?.filterNot { it.name == ".nomedia" || it.name == ".DS_Store" || it.name == "__MACOSX" }
            ?: return null
        return children.singleOrNull { it.isDirectory }?.takeIf { children.none { child -> child.isFile } }
    }

    private fun mergedRuntimeConfig(configFile: File, config: LocalGenerationConfig): String {
        val json = runCatching {
            JsonParser.parseString(configFile.readText()).asJsonObject.deepCopy()
        }.getOrDefault(JsonObject())

        json.addProperty("backend_type", config.backendName)
        json.addProperty("thread_num", config.threads.coerceIn(1, 8))
        json.addProperty("precision", config.precision)
        json.addProperty("memory", "low")
        json.addProperty("max_new_tokens", config.maxTokens.coerceIn(64, 2048))
        json.addProperty("use_mmap", true)
        return gson.toJson(json)
    }

    private fun extraNativeConfig(configFile: File): String {
        val cacheDir = File(mmapRoot, configFile.parentFile?.name ?: "default").also { it.mkdirs() }
        return gson.toJson(JsonObject().apply {
            addProperty("is_r1", false)
            addProperty("mmap_dir", cacheDir.absolutePath)
            addProperty("keep_history", false)
        })
    }

    private fun selectedModelSupportsVision(configFile: File): Boolean {
        val dir = configFile.parentFile ?: return false
        return File(dir, "visual.mnn").isFile && File(dir, "visual.mnn.weight").isFile
    }
}

object MnnLlmNative {
    private val loadResult: Result<Unit> by lazy {
        runCatching {
            System.loadLibrary("c++_shared")
            System.loadLibrary("MNN")
            runCatching {
                System.loadLibrary("docpilot_mnn_llm")
            }.getOrElse {
                System.loadLibrary("mnnllmapp")
            }
        }
    }

    val isRuntimeLoadable: Boolean
        get() = loadResult.isSuccess

    fun loadFailureMessage(): String {
        return loadResult.exceptionOrNull()?.let {
            "MNN 运行库加载失败：${it.message ?: it::class.java.simpleName}"
        } ?: "MNN 原生推理暂不可用"
    }

    fun generate(
        configPath: String,
        prompt: String,
        mergedConfigJson: String,
        extraConfigJson: String,
        imagePaths: List<String> = emptyList(),
        onPartial: suspend (String) -> Unit = {}
    ): String? {
        if (!isRuntimeLoadable) return null
        return runCatching {
            val generated = StringBuilder()
            val session = LlmSession(configPath)
            try {
                if (!session.init(mergedConfigJson, extraConfigJson)) return@runCatching null
                val finalText = session.submit(prompt, imagePaths, object : GenerateProgressListener {
                    override fun onProgress(progress: String?): Boolean {
                        if (!progress.isNullOrBlank()) {
                            generated.append(progress)
                            kotlinx.coroutines.runBlocking { onPartial(generated.toString().cleanMnnOutput()) }
                        }
                        return false
                    }
                })
                finalText.ifBlank { generated.toString() }.cleanMnnOutput()
            } finally {
                session.release()
            }
        }.getOrNull()
    }

    private fun String.cleanMnnOutput(): String {
        return replace(Regex("""(?is)<think>\s*</think>"""), "")
            .replace(Regex("""(?is)<think>.*?</think>"""), "")
            .replace("<think>", "")
            .replace("</think>", "")
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .trim()
    }
}
