package com.alibaba.mnnllm.android.llm

class LlmSession(private val configPath: String) {
    private var nativeHandle: Long = 0L

    fun init(
        mergedConfigJson: String = "{}",
        extraConfigJson: String = "{}",
        history: List<String>? = null
    ): Boolean {
        nativeHandle = initNative(configPath, history, mergedConfigJson, extraConfigJson)
        return nativeHandle != 0L
    }

    fun submit(prompt: String, progressListener: GenerateProgressListener): String {
        check(nativeHandle != 0L) { "MNN LLM session is not initialized" }
        val result = submitNative(nativeHandle, prompt, false, progressListener)
        return result["text"]?.toString().orEmpty()
    }

    fun submit(prompt: String, imagePaths: List<String>, progressListener: GenerateProgressListener): String {
        check(nativeHandle != 0L) { "MNN LLM session is not initialized" }
        if (imagePaths.isEmpty()) return submit(prompt, progressListener)
        val multimodalPrompt = buildString {
            imagePaths.forEach { path ->
                append("<img>").append(path).append("</img>\n")
            }
            append(prompt)
        }
        val result = submitNative(nativeHandle, multimodalPrompt, false, progressListener)
        return result["text"]?.toString().orEmpty()
    }

    fun release() {
        if (nativeHandle != 0L) {
            releaseNative(nativeHandle)
            nativeHandle = 0L
        }
    }

    private external fun initNative(
        configPath: String?,
        history: List<String>?,
        mergedConfigStr: String?,
        configJsonStr: String?
    ): Long

    private external fun submitNative(
        instanceId: Long,
        input: String,
        keepHistory: Boolean,
        listener: GenerateProgressListener
    ): HashMap<String, Any>

    private external fun releaseNative(instanceId: Long)
}
