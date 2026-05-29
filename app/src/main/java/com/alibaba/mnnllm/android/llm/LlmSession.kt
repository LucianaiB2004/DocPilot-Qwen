package com.alibaba.mnnllm.android.llm

class LlmSession(private val configPath: String) {
    private var nativeHandle: Long = 0L

    fun init(runtimeConfig: String = "{}"): Boolean {
        nativeHandle = initNative(configPath, runtimeConfig, emptyMap())
        return nativeHandle != 0L
    }

    fun submit(prompt: String, progressListener: GenerateProgressListener): String {
        check(nativeHandle != 0L) { "MNN LLM session is not initialized" }
        val result = submitNative(nativeHandle, prompt, progressListener)
        return result["text"]?.toString().orEmpty()
    }

    fun submit(prompt: String, imagePaths: List<String>, progressListener: GenerateProgressListener): String {
        check(nativeHandle != 0L) { "MNN LLM session is not initialized" }
        if (imagePaths.isEmpty()) return submit(prompt, progressListener)
        val result = runCatching {
            submitMultimodalNative(nativeHandle, prompt, imagePaths, progressListener)
        }.getOrElse {
            submitNative(nativeHandle, buildString {
                append(prompt)
                append("\n\n[本地 VL 输入]\n")
                imagePaths.forEachIndexed { index, path -> append("image_${index + 1}: ").append(path).append('\n') }
            }, progressListener)
        }
        return result["text"]?.toString().orEmpty()
    }

    fun release() {
        if (nativeHandle != 0L) {
            releaseNative(nativeHandle)
            nativeHandle = 0L
        }
    }

    private external fun initNative(
        configPath: String,
        mergedConfigStr: String,
        configMap: Map<String, Any>
    ): Long

    private external fun submitNative(
        instanceId: Long,
        input: String,
        listener: GenerateProgressListener
    ): HashMap<String, Any>

    private external fun submitMultimodalNative(
        instanceId: Long,
        input: String,
        imagePaths: List<String>,
        listener: GenerateProgressListener
    ): HashMap<String, Any>

    private external fun releaseNative(instanceId: Long)
}
