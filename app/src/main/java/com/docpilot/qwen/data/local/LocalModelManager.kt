package com.docpilot.qwen.data.local

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

data class LocalModelDownloadState(
    val name: String,
    val status: String,
    val downloaded: Boolean,
    val selected: Boolean,
    val downloading: Boolean = false,
    val progress: Int = 0,
    val localPath: String = "",
    val message: String = ""
)

private data class ActiveDownload(val ids: List<Long>, val spec: LocalModelSpec)

private enum class DownloadMonitorResult {
    Continue,
    StartNext,
    Finished
}

data class LocalModelSpec(
    val name: String,
    val sizeLabel: String,
    val usage: String,
    val fileName: String,
    val repositoryId: String,
    val files: List<String>,
    val sha256: Map<String, String> = emptyMap(),
    val aliases: List<String> = emptyList()
)

class LocalModelManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("docpilot_local_models", Context.MODE_PRIVATE)
    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val activeDownloads = mutableMapOf<String, ActiveDownload>()
    private val activeDownloadJobs = mutableMapOf<String, Job>()
    private val modelDir: File
        get() = File(context.getExternalFilesDir(null), "models").also { it.mkdirs() }

    val specs: List<LocalModelSpec> = listOf(
        LocalModelSpec(
            name = "Qwen3.5-0.8B MNN",
            sizeLabel = "约 190 MB",
            usage = "轻量兜底模型，适合摘要、问答和短文档处理",
            fileName = "Qwen3.5-0.8B-MNN",
            repositoryId = "Qwen3.5-0.8B-MNN",
            files = textModelFiles + "llm.mnn.json",
            sha256 = mapOf(
                "config.json" to "92853033efe602f95efca3e1c05cd8b108f973c8beed417843a9671f8147ed8d",
                "llm.mnn" to "94e0459e10584487a3bda77dc3c3322c0c01cfc9d223a7284e8d07345bf877d5",
                "llm.mnn.weight" to "85e4af770f3ba4b767589ccd2b7e96b36351335f8348bbc18afe3633c912e2e4",
                "llm.mnn.json" to "fe58a15987b391b443ade62a92221956349e11628fa36b1bfb9503af32923a1a",
                "llm_config.json" to "33f2c15bda1911ed666418437f45900e78185b57b883545e5d02c14f92a0907b",
                "tokenizer.txt" to "7e75de1f279a10b65bd9dc1a5207205cb8993823861c4c42bbbd74e48e1c23a4"
            ),
            aliases = listOf("Qwen3.5-0.8B-MNN", "qwen3_0_8b_mnn", "qwen3_0.8b_mnn")
        ),
        LocalModelSpec(
            name = "Qwen3-1.7B MNN",
            sizeLabel = "约 2B",
            usage = "更强的通用文本理解，适合较长文档和复杂问答",
            fileName = "Qwen3-1.7B-MNN",
            repositoryId = "Qwen3-1.7B-MNN",
            files = textModelFiles,
            aliases = listOf("Qwen3-1.7B-MNN", "qwen3_1_7b_mnn", "qwen3_1.7b_mnn")
        ),
        LocalModelSpec(
            name = "Qwen3-4B MNN",
            sizeLabel = "约 4B",
            usage = "高质量文本推理，建议高内存设备使用",
            fileName = "Qwen3-4B-MNN",
            repositoryId = "Qwen3-4B-MNN",
            files = textModelFiles,
            aliases = listOf("Qwen3-4B-MNN", "qwen3_4b_mnn")
        ),
        LocalModelSpec(
            name = "Qwen3-VL-2B MNN",
            sizeLabel = "约 2B",
            usage = "图片、扫描件和多模态文档理解",
            fileName = "Qwen3-VL-2B-Instruct-MNN",
            repositoryId = "Qwen3-VL-2B-Instruct-MNN",
            files = vlModelFiles,
            aliases = listOf("Qwen3-VL-2B-Instruct-MNN", "Qwen3-VL-2B-MNN", "qwen3_vl_2b_mnn")
        ),
        LocalModelSpec(
            name = "Qwen3-VL-4B MNN",
            sizeLabel = "约 4B",
            usage = "复杂图表、截图和多模态文档理解",
            fileName = "Qwen3-VL-4B-Instruct-MNN",
            repositoryId = "Qwen3-VL-4B-Instruct-MNN",
            files = vlModelFiles,
            aliases = listOf("Qwen3-VL-4B-Instruct-MNN", "Qwen3-VL-4B-MNN", "qwen3_vl_4b_mnn")
        )
    )

    private val _selectedModel = MutableStateFlow(resolveSelectedModelName())
    val selectedModelState: StateFlow<String> = _selectedModel.asStateFlow()
    private val _states = MutableStateFlow(loadStates(_selectedModel.value))
    val states: StateFlow<Map<String, LocalModelDownloadState>> = _states.asStateFlow()

    fun selectedModel(): String = _selectedModel.value

    fun selectModel(name: String) {
        val spec = specs.firstOrNull { it.name == name } ?: return
        if (!isModelAvailable(spec)) return
        prefs.edit().putString(KEY_SELECTED, name).apply()
        _selectedModel.value = name
        refreshStates()
    }

    fun downloadModel(name: String, scope: CoroutineScope) {
        val spec = specs.firstOrNull { it.name == name } ?: return
        val targetDir = modelFile(spec)
        if (isModelAvailable(spec)) {
            setDownloaded(spec, availableModelFile(spec) ?: targetDir, "已检测到本地模型")
            selectModel(name)
            return
        }

        activeDownloads[name]?.let { active ->
            monitorDownloads(active.ids, active.spec, targetDir, scope)
            return
        }
        val storedIds = storedDownloadIds(name)
        if (storedIds.isNotEmpty()) {
            when (updateDownloadStateFromManager(spec, storedIds, targetDir)) {
                DownloadMonitorResult.Continue -> {
                    activeDownloads[name] = ActiveDownload(storedIds, spec)
                    monitorDownloads(storedIds, spec, targetDir, scope)
                    return
                }
                DownloadMonitorResult.StartNext -> {
                    clearStoredDownloadIds(name)
                    startNextMissingDownload(spec, scope)
                    return
                }
                DownloadMonitorResult.Finished -> clearStoredDownloadIds(name)
            }
        }

        startNextMissingDownload(spec, scope)
    }

    private fun monitorDownloads(
        downloadIds: List<Long>,
        spec: LocalModelSpec,
        targetDir: File,
        scope: CoroutineScope
    ) {
        activeDownloadJobs.remove(spec.name)?.cancel()
        lateinit var monitorJob: Job
        monitorJob = scope.launch(Dispatchers.IO) {
            var running = true
            while (running) {
                if (!isActiveDownload(spec.name, downloadIds)) {
                    running = false
                    continue
                }
                when (updateDownloadStateFromManager(spec, downloadIds, targetDir)) {
                    DownloadMonitorResult.Continue -> delay(1000)
                    DownloadMonitorResult.StartNext -> {
                        running = false
                        if (activeDownloadJobs[spec.name] == monitorJob) {
                            activeDownloadJobs.remove(spec.name)
                        }
                        startNextMissingDownload(spec, scope)
                    }
                    DownloadMonitorResult.Finished -> running = false
                }
            }
            if (activeDownloadJobs[spec.name] == monitorJob) {
                activeDownloadJobs.remove(spec.name)
            }
        }
        activeDownloadJobs[spec.name] = monitorJob
    }

    fun pauseDownload(name: String) {
        val ids = activeDownloads.remove(name)?.ids ?: storedDownloadIds(name)
        activeDownloadJobs.remove(name)?.cancel()
        ids.forEach { downloadManager.remove(it) }
        clearStoredDownloadIds(name)
        setState(name, status = "已暂停", downloaded = false, downloading = false, progress = _states.value[name]?.progress ?: 0, message = "已暂停；再次点击下载会继续补齐缺失文件。")
    }

    fun uninstallModel(name: String) {
        val spec = specs.firstOrNull { it.name == name } ?: return
        activeDownloads.remove(name)?.ids?.forEach { downloadManager.remove(it) }
        activeDownloadJobs.remove(name)?.cancel()
        storedDownloadIds(name).forEach { downloadManager.remove(it) }
        clearStoredDownloadIds(name)
        modelCandidates(spec).forEach { it.deleteRecursively() }
        prefs.edit().remove("downloaded_$name").apply()
        if (selectedModel() == name) {
            val fallback = specs.firstOrNull { it.name != name && isModelAvailable(it) }?.name ?: specs.first().name
            prefs.edit().putString(KEY_SELECTED, fallback).apply()
            _selectedModel.value = fallback
        }
        refreshStates()
    }

    private fun loadStates(selected: String): Map<String, LocalModelDownloadState> {
        return specs.associate { spec ->
            val file = modelFile(spec)
            val availableFile = availableModelFile(spec)
            val storedIds = storedDownloadIds(spec.name)
            val downloaded = availableFile != null
            val snapshots = if (!downloaded && storedIds.isNotEmpty()) queryDownloads(storedIds) else emptyList()
            val downloadFinished = snapshots.isNotEmpty() && snapshots.all { it.status == DownloadManager.STATUS_SUCCESSFUL }
            val downloadFailed = snapshots.any { it.status == DownloadManager.STATUS_FAILED }
            val downloading = snapshots.any {
                it.status == DownloadManager.STATUS_PENDING ||
                    it.status == DownloadManager.STATUS_RUNNING ||
                    it.status == DownloadManager.STATUS_PAUSED
            }
            spec.name to LocalModelDownloadState(
                name = spec.name,
                status = when {
                    downloaded -> "已下载"
                    downloadFailed -> "下载失败"
                    downloadFinished -> "下载完成待确认"
                    downloading -> if (snapshots.any { it.status == DownloadManager.STATUS_PAUSED }) "连接中" else "下载中"
                    else -> "可下载"
                },
                downloaded = downloaded,
                selected = spec.name == selected,
                downloading = downloading,
                progress = when {
                    downloaded || downloadFinished -> 100
                    snapshots.isNotEmpty() -> {
                        val doneCount = spec.files.count { File(file, it).isFile }
                        (((doneCount * 100) + snapshots.progress().coerceIn(1, 99)) / spec.files.size).coerceIn(1, 99)
                    }
                    else -> 0
                },
                localPath = availableFile?.absolutePath ?: if (file.exists()) file.absolutePath else "",
                message = when {
                    downloaded && availableFile != null -> if (spec.sha256.isEmpty()) {
                        "下载完成：${availableFile.name}（未配置官方 hash，仅完成文件存在性检查）"
                    } else {
                        "下载完成：${availableFile.name}（SHA-256 已校验）"
                    }
                    downloadFailed -> "下载失败，请重试或检查网络/存储空间。"
                    downloadFinished -> "下载已完成，但缺少必要模型文件，请重试。"
                    downloading -> {
                        val doneCount = spec.files.count { File(file, it).isFile }
                        val activeFile = spec.files.firstOrNull { !File(file, it).isFile }.orEmpty()
                        "正在下载 ${doneCount + 1}/${spec.files.size} 个文件：$activeFile"
                    }
                    else -> "准备下载到 ${file.absolutePath}"
                }
            )
        }
    }

    private fun modelFile(spec: LocalModelSpec): File = File(modelDir, spec.fileName)

    private fun modelCandidates(spec: LocalModelSpec): List<File> {
        return (listOf(spec.fileName) + spec.aliases).distinct().map { File(modelDir, it) }
    }

    private fun isModelAvailable(spec: LocalModelSpec): Boolean = availableModelFile(spec) != null

    private fun availableModelFile(spec: LocalModelSpec): File? {
        return modelCandidates(spec).firstNotNullOfOrNull { file ->
            normalizeModelDirectory(spec, file)
        }
    }

    private fun normalizeModelDirectory(spec: LocalModelSpec, file: File): File? {
        if (!file.isDirectory) return null
        if (hasRequiredModelFiles(spec, file) && verifyModelHashes(spec, file)) return file

        val nested = file.singleContentDirectory() ?: return null
        if (!hasRequiredModelFiles(spec, nested) || !verifyModelHashes(spec, nested)) return null

        return if (flattenSingleDirectory(file, nested) && hasRequiredModelFiles(spec, file) && verifyModelHashes(spec, file)) {
            file
        } else {
            nested
        }
    }

    private fun hasRequiredModelFiles(spec: LocalModelSpec, file: File): Boolean {
        return spec.files.all { File(file, it).isFile }
    }

    private fun flattenSingleDirectory(parent: File, childDir: File): Boolean {
        val children = childDir.listFiles()?.takeIf { it.isNotEmpty() } ?: return false
        if (children.any { File(parent, it.name).exists() }) return false
        var movedAll = true
        children.forEach { child ->
            movedAll = child.renameTo(File(parent, child.name)) && movedAll
        }
        if (movedAll) {
            childDir.delete()
        }
        return movedAll
    }

    private fun File.singleContentDirectory(): File? {
        val children = listFiles()
            ?.filterNot { it.name == ".nomedia" || it.name == ".DS_Store" || it.name == "__MACOSX" }
            ?: return null
        return children.singleOrNull { it.isDirectory }?.takeIf { children.none { child -> child.isFile } }
    }

    private fun verifyModelHashes(spec: LocalModelSpec, modelDir: File): Boolean {
        if (spec.sha256.isEmpty()) return true
        return spec.sha256.all { (name, expected) ->
            val file = File(modelDir, name)
            file.isFile && file.sha256().equals(expected, ignoreCase = true)
        }
    }

    private fun refreshStates() {
        _states.value = loadStates(_selectedModel.value)
    }

    private fun modelFileUrl(spec: LocalModelSpec, fileName: String): String {
        return "https://modelscope.cn/models/MNN/${spec.repositoryId}/resolve/master/$fileName"
    }

    private fun startNextMissingDownload(spec: LocalModelSpec, scope: CoroutineScope) {
        val name = spec.name
        val targetDir = modelFile(spec)
        if (isModelAvailable(spec)) {
            setDownloaded(spec, availableModelFile(spec) ?: targetDir, "已检测到本地模型")
            selectModel(name)
            return
        }

        val missingFiles = spec.files.filterNot { File(targetDir, it).isFile }
        if (missingFiles.isEmpty()) {
            refreshStates()
            return
        }

        targetDir.mkdirs()
        val fileName = missingFiles.first()
        val request = DownloadManager.Request(Uri.parse(modelFileUrl(spec, fileName)))
            .setTitle("DocPilot Qwen - ${spec.name}")
            .setDescription("正在下载 $fileName")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, "models/${spec.fileName}", fileName)
        val downloadIds = listOf(downloadManager.enqueue(request))
        activeDownloads[name] = ActiveDownload(downloadIds, spec)
        saveStoredDownloadIds(name, downloadIds)

        val doneCount = spec.files.size - missingFiles.size
        val progress = ((doneCount * 100) / spec.files.size).coerceIn(1, 99)
        setState(
            name = name,
            status = "下载中",
            downloaded = false,
            downloading = true,
            progress = progress,
            message = "正在下载 ${doneCount + 1}/${spec.files.size} 个文件：$fileName"
        )
        monitorDownloads(downloadIds, spec, targetDir, scope)
    }

    private fun queryDownloads(ids: List<Long>): List<DownloadSnapshot> {
        val results = mutableListOf<DownloadSnapshot>()
        downloadManager.query(DownloadManager.Query().setFilterById(*ids.toLongArray()))?.use { cursor ->
            while (cursor.moveToNext()) {
                results += DownloadSnapshot(
                    status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                    downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                )
            }
        }
        return results.ifEmpty { ids.map { DownloadSnapshot(DownloadManager.STATUS_PENDING, 0L, -1L) } }
    }

    private fun updateDownloadStateFromManager(spec: LocalModelSpec, ids: List<Long>, targetDir: File): DownloadMonitorResult {
        val name = spec.name
        if (!isActiveDownload(name, ids)) return DownloadMonitorResult.Finished
        val snapshots = queryDownloads(ids)
        val progress = snapshots.progress().coerceIn(1, 99)
        return when {
            snapshots.any { it.status == DownloadManager.STATUS_FAILED } -> {
                activeDownloads.remove(name)
                clearStoredDownloadIds(name)
                setState(name, status = "下载失败", downloaded = false, downloading = false, progress = progress, message = "下载失败，请重试或检查网络/存储空间。")
                DownloadMonitorResult.Finished
            }
            snapshots.all { it.status == DownloadManager.STATUS_SUCCESSFUL } -> {
                activeDownloads.remove(name)
                clearStoredDownloadIds(name)
                if (isModelAvailable(spec)) {
                    setDownloaded(spec, availableModelFile(spec) ?: targetDir, "下载完成，可选择使用")
                    selectModel(name)
                    DownloadMonitorResult.Finished
                } else {
                    val missingFiles = spec.files.filterNot { File(targetDir, it).isFile }
                    if (missingFiles.isNotEmpty()) {
                        setState(name, status = "下载中", downloaded = false, downloading = true, progress = progress, message = "正在准备下一个文件，还剩 ${missingFiles.size} 个")
                        DownloadMonitorResult.StartNext
                    } else {
                        setState(name, status = "下载完成待确认", downloaded = false, downloading = false, progress = 100, message = "下载已完成，但缺少必要模型文件，请重试。")
                        DownloadMonitorResult.Finished
                    }
                }
            }
            snapshots.any { it.status == DownloadManager.STATUS_PAUSED } -> {
                setState(name, status = "连接中", downloaded = false, downloading = true, progress = progress, message = "下载器正在等待网络或下载源响应。")
                DownloadMonitorResult.Continue
            }
            else -> {
                val doneCount = spec.files.count { File(targetDir, it).isFile }
                val activeFile = spec.files.firstOrNull { !File(targetDir, it).isFile }.orEmpty()
                val overallProgress = (((doneCount * 100) + progress) / spec.files.size).coerceIn(1, 99)
                setState(name, status = "下载中", downloaded = false, downloading = true, progress = overallProgress, message = "正在下载 ${doneCount + 1}/${spec.files.size} 个文件：$activeFile")
                DownloadMonitorResult.Continue
            }
        }
    }

    private fun setDownloaded(spec: LocalModelSpec, localFile: File, message: String) {
        prefs.edit().putBoolean("downloaded_${spec.name}", true).apply()
        setState(spec.name, status = "已下载", downloaded = true, downloading = false, progress = 100, message = "$message：${localFile.name}", localPath = localFile.absolutePath)
    }

    private fun storedDownloadIds(name: String): List<Long> {
        return prefs.getString(activeDownloadKey(name), null)
            ?.split(",")
            ?.mapNotNull { it.toLongOrNull() }
            .orEmpty()
    }

    private fun saveStoredDownloadIds(name: String, ids: List<Long>) {
        prefs.edit().putString(activeDownloadKey(name), ids.joinToString(",")).apply()
    }

    private fun clearStoredDownloadIds(name: String) {
        prefs.edit().remove(activeDownloadKey(name)).apply()
    }

    private fun activeDownloadKey(name: String): String = "active_download_ids_$name"

    private fun isActiveDownload(name: String, ids: List<Long>): Boolean {
        val memoryIds = activeDownloads[name]?.ids
        return memoryIds == ids || storedDownloadIds(name) == ids
    }

    private fun setState(
        name: String,
        status: String,
        downloaded: Boolean,
        downloading: Boolean = _states.value[name]?.downloading ?: false,
        progress: Int,
        message: String,
        localPath: String = _states.value[name]?.localPath.orEmpty()
    ) {
        val current = _states.value[name] ?: return
        _states.value = _states.value + (name to current.copy(
            status = status,
            downloaded = downloaded,
            selected = name == _selectedModel.value,
            downloading = downloading,
            progress = progress,
            message = message,
            localPath = localPath
        ))
    }

    private fun resolveSelectedModelName(): String {
        val saved = prefs.getString(KEY_SELECTED, null)
        specs.firstOrNull { it.name == saved && isModelAvailable(it) }?.let { return it.name }
        specs.firstOrNull { isModelAvailable(it) }?.let { return it.name }
        return specs.first().name
    }

    companion object {
        private val textModelFiles = listOf("config.json", "llm.mnn", "llm.mnn.weight", "llm_config.json", "tokenizer.txt")
        private val vlModelFiles = textModelFiles + listOf("llm.mnn.json", "visual.mnn", "visual.mnn.weight")
        private const val KEY_SELECTED = "selected_model"
    }
}

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private data class DownloadSnapshot(
    val status: Int,
    val downloaded: Long,
    val total: Long
)

private fun List<DownloadSnapshot>.progress(): Int {
    val total = sumOf { it.total.takeIf { value -> value > 0L } ?: 0L }
    if (total <= 0L) return if (any { it.status == DownloadManager.STATUS_RUNNING }) 1 else 0
    val downloaded = sumOf { it.downloaded.coerceAtLeast(0L) }
    return ((downloaded * 100) / total).toInt()
}
