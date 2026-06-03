package com.docpilot.qwen.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.docpilot.qwen.data.DocumentRepository
import com.docpilot.qwen.data.export.DocumentExporter
import com.docpilot.qwen.data.export.ExportFormat
import com.docpilot.qwen.data.local.ChatMessageEntity
import com.docpilot.qwen.data.local.DocumentEntity
import com.docpilot.qwen.data.local.ExtractionEntity
import com.docpilot.qwen.data.local.LocalGenerationConfig
import com.docpilot.qwen.data.local.LocalModelDownloadState
import com.docpilot.qwen.data.local.LocalModelManager
import com.docpilot.qwen.data.local.LocalModelSpec
import com.docpilot.qwen.data.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class DocPilotUiState(
    val documents: List<DocumentEntity> = emptyList(),
    val selectedDocumentId: Long = 1,
    val messages: List<ChatMessageEntity> = emptyList(),
    val extractions: List<ExtractionEntity> = emptyList(),
    val allExtractions: List<ExtractionEntity> = emptyList(),
    val isCloudEnabled: Boolean = false,
    val status: String = "本地优先",
    val hasQwenKey: Boolean = false,
    val hasTextInKey: Boolean = false,
    val qwenKeyValue: String = "",
    val textInAppIdValue: String = "",
    val textInSecretValue: String = "",
    val qwenTestStatus: String = "未测试",
    val textInTestStatus: String = "未测试",
    val insightResults: Map<String, String> = emptyMap(),
    val templateResults: Map<String, String> = emptyMap(),
    val workingTask: String = "",
    val recentQuestions: List<ChatMessageEntity> = emptyList(),
    val recentOpenedIds: List<Long> = emptyList(),
    val localModelSpecs: List<LocalModelSpec> = emptyList(),
    val localModelStates: Map<String, LocalModelDownloadState> = emptyMap(),
    val selectedLocalModel: String = "Qwen3.5-0.8B MNN",
    val defaultLocalMode: Boolean = true,
    val autoClearClipboardAndCache: Boolean = true,
    val performanceMode: String = "均衡模式",
    val parseThreadCount: Int = 4,
    val accelerationEngine: String = "系统 IO",
    val cloudModel: String = "qwen3.6-plus",
    val localRuntimeStatus: String = "MNN 运行库未安装"
) {
    val selectedDocument: DocumentEntity?
        get() = documents.firstOrNull { it.id == selectedDocumentId } ?: documents.firstOrNull()
}

private data class ApiKeyUiState(
    val qwenKeyValue: String,
    val textInAppIdValue: String,
    val textInSecretValue: String,
    val qwenTestStatus: String,
    val textInTestStatus: String
)

private data class AiRuntimeUiState(
    val insights: Map<String, String>,
    val templates: Map<String, String>,
    val workingTask: String,
    val recentQuestions: List<ChatMessageEntity>,
    val recentOpenedIds: List<Long>,
    val modelStates: Map<String, LocalModelDownloadState>,
    val selectedLocalModel: String,
    val localRuntimeStatus: String
)

private data class ActivityUiState(
    val recentQuestions: List<ChatMessageEntity>,
    val recentOpenedIds: List<Long>,
    val modelStates: Map<String, LocalModelDownloadState>,
    val selectedLocalModel: String,
    val localRuntimeStatus: String
)

private data class SettingsUiState(
    val defaultLocalMode: Boolean,
    val autoClearClipboardAndCache: Boolean,
    val performanceMode: String,
    val parseThreadCount: Int,
    val accelerationEngine: String,
    val cloudModel: String
)

private const val FILE_READ_TIMEOUT_MS = 45_000L
private const val TAG = "DocPilot"

@OptIn(ExperimentalCoroutinesApi::class)
class DocPilotViewModel(
    application: Application,
    private val repository: DocumentRepository,
    private val settingsStore: SettingsStore,
    private val localModelManager: LocalModelManager
) : AndroidViewModel(application) {
    private val selectedDocumentId = MutableStateFlow(1L)
    private val cloudEnabled = MutableStateFlow(false)
    private val status = MutableStateFlow("本地优先")
    private val hasQwenKey = MutableStateFlow(repositoryHasQwenKey())
    private val hasTextInKey = MutableStateFlow(repositoryHasTextInKey())
    private val qwenKeyValue = MutableStateFlow(repository.getQwenApiKey())
    private val textInAppIdValue = MutableStateFlow(repository.getTextInAppId())
    private val textInSecretValue = MutableStateFlow(repository.getTextInSecret())
    private val qwenTestStatus = MutableStateFlow("未测试")
    private val textInTestStatus = MutableStateFlow("未测试")
    private val insightResults = MutableStateFlow<Map<String, String>>(emptyMap())
    private val templateResults = MutableStateFlow<Map<String, String>>(emptyMap())
    private val workingTask = MutableStateFlow("")
    private val recentOpenedIds = MutableStateFlow<List<Long>>(emptyList())
    private val defaultLocalMode = MutableStateFlow(true)
    private val autoClearClipboardAndCache = MutableStateFlow(true)
    private val performanceMode = MutableStateFlow("均衡模式")
    private val parseThreadCount = MutableStateFlow(4)
    private val accelerationEngine = MutableStateFlow("系统 IO")
    private val cloudModel = MutableStateFlow("qwen3.6-plus")
    private val documents = repository.observeDocuments()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val messages = selectedDocumentId.flatMapLatest(repository::observeMessages)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val recentQuestions = repository.observeRecentQuestions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val extractions = selectedDocumentId.flatMapLatest(repository::observeExtractions)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val allExtractions = repository.observeAllExtractions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val baseDocumentState = combine(
        documents,
        selectedDocumentId,
        messages,
        extractions,
        cloudEnabled
    ) { docs, selectedId, msgs, extracts, cloud ->
        DocPilotUiState(
            documents = docs,
            selectedDocumentId = selectedId,
            messages = msgs,
            extractions = extracts,
            isCloudEnabled = cloud
        )
    }

    private val baseState = combine(
        baseDocumentState,
        allExtractions
    ) { base, allExtracts ->
        base.copy(allExtractions = allExtracts)
    }

    private val readyState = combine(
        hasQwenKey,
        hasTextInKey,
        status
    ) { qwenReady, textInReady, stateText ->
        Triple(qwenReady, textInReady, stateText)
    }

    private val partialState = combine(
        baseState,
        readyState
    ) { base, ready ->
        base.copy(
            status = ready.third,
            hasQwenKey = ready.first,
            hasTextInKey = ready.second
        )
    }

    private val apiKeyState = combine(
        qwenKeyValue,
        textInAppIdValue,
        textInSecretValue,
        qwenTestStatus,
        textInTestStatus
    ) { qwenValue, textInId, textInSecret, qwenTest, textInTest ->
        ApiKeyUiState(qwenValue, textInId, textInSecret, qwenTest, textInTest)
    }

    private val keyedState = combine(
        partialState,
        apiKeyState
    ) { base, keys ->
        base.copy(
            qwenKeyValue = keys.qwenKeyValue,
            textInAppIdValue = keys.textInAppIdValue,
            textInSecretValue = keys.textInSecretValue,
            qwenTestStatus = keys.qwenTestStatus,
            textInTestStatus = keys.textInTestStatus
        )
    }

    private val generationState = combine(
        insightResults,
        templateResults,
        workingTask
    ) { insights, templates, working ->
        Triple(insights, templates, working)
    }

    private val activityState = combine(
        recentQuestions,
        recentOpenedIds,
        localModelManager.states,
        localModelManager.selectedModelState
    ) { recent, opened, modelStates, selectedModel ->
        ActivityUiState(recent, opened, modelStates, selectedModel, repository.localRuntimeStatus())
    }

    private val aiResultState = combine(
        generationState,
        activityState
    ) { generation, activity ->
        AiRuntimeUiState(
            generation.first,
            generation.second,
            generation.third,
            activity.recentQuestions,
            activity.recentOpenedIds,
            activity.modelStates,
            activity.selectedLocalModel,
            activity.localRuntimeStatus
        )
    }

    private val settingsUiState = combine(
        defaultLocalMode,
        autoClearClipboardAndCache,
        performanceMode,
        parseThreadCount,
        accelerationEngine,
        cloudModel
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        SettingsUiState(
            defaultLocalMode = values[0] as Boolean,
            autoClearClipboardAndCache = values[1] as Boolean,
            performanceMode = values[2] as String,
            parseThreadCount = values[3] as Int,
            accelerationEngine = values[4] as String,
            cloudModel = values[5] as String
        )
    }

    val uiState: StateFlow<DocPilotUiState> = combine(
        keyedState,
        aiResultState,
        settingsUiState
    ) { base, ai, settings ->
        base.copy(
            insightResults = ai.insights,
            templateResults = ai.templates,
            workingTask = ai.workingTask,
            recentQuestions = ai.recentQuestions,
            recentOpenedIds = ai.recentOpenedIds,
            localModelSpecs = localModelManager.specs,
            localModelStates = ai.modelStates,
            localRuntimeStatus = ai.localRuntimeStatus,
            selectedLocalModel = ai.selectedLocalModel,
            defaultLocalMode = settings.defaultLocalMode,
            autoClearClipboardAndCache = settings.autoClearClipboardAndCache,
            performanceMode = settings.performanceMode,
            parseThreadCount = settings.parseThreadCount,
            accelerationEngine = settings.accelerationEngine,
            cloudModel = settings.cloudModel
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DocPilotUiState())

    init {
        viewModelScope.launch {
            repository.finishInterruptedGenerations()
            repository.seedDemoData()
        }
        viewModelScope.launch {
            documents.collectLatest { docs ->
                syncSelectedDocument(docs, preferRecentQuestion = false)
            }
        }
        viewModelScope.launch {
            settingsStore.cloudEnabled.collect { enabled ->
                cloudEnabled.value = enabled
                status.value = if (enabled) "云端增强可用" else "本地优先"
            }
        }
        viewModelScope.launch {
            settingsStore.defaultLocalMode.collect { defaultLocalMode.value = it }
        }
        viewModelScope.launch {
            settingsStore.autoClearClipboardAndCache.collect { autoClearClipboardAndCache.value = it }
        }
        viewModelScope.launch {
            settingsStore.performanceMode.collect { performanceMode.value = it }
        }
        viewModelScope.launch {
            settingsStore.parseThreadCount.collect { parseThreadCount.value = it }
        }
        viewModelScope.launch {
            settingsStore.accelerationEngine.collect { accelerationEngine.value = it }
        }
        viewModelScope.launch {
            settingsStore.cloudModel.collect { cloudModel.value = it }
        }
    }

    fun selectDocument(id: Long) {
        selectedDocumentId.value = id
        recentOpenedIds.value = (listOf(id) + recentOpenedIds.value.filterNot { it == id }).take(20)
    }

    fun deleteDocument(id: Long) {
        viewModelScope.launch {
            repository.deleteDocument(id)
            recentOpenedIds.value = recentOpenedIds.value.filterNot { it == id }
            val next = uiState.value.documents.firstOrNull { it.id != id }?.id ?: 0L
            selectedDocumentId.value = next
            status.value = "文档已移除"
        }
    }

    fun deleteMessage(id: Long) {
        viewModelScope.launch {
            repository.deleteMessage(id)
            status.value = "回答已删除"
        }
    }

    fun saveTemplateResult(templateName: String, content: String) {
        val doc = uiState.value.selectedDocument ?: return
        viewModelScope.launch {
            repository.saveTemplateResult(doc.id, templateName, content)
            status.value = "$templateName 已保存到历史"
        }
    }

    fun deleteTemplateHistory(id: Long) {
        viewModelScope.launch {
            repository.deleteExtraction(id)
            status.value = "模板历史已删除"
        }
    }

    fun renameSelectedDocument(name: String) {
        val doc = uiState.value.selectedDocument ?: return
        viewModelScope.launch {
            repository.renameDocument(doc.id, name)
            status.value = "文档已重命名"
        }
    }

    fun updateSelectedDocumentOrganization(tags: String, folder: String) {
        val doc = uiState.value.selectedDocument ?: return
        viewModelScope.launch {
            repository.updateDocumentOrganization(doc.id, tags, folder)
            status.value = "标签与文件夹已更新"
        }
    }

    fun exportSelectedDocument(format: ExportFormat, templateContent: String = "") {
        val doc = uiState.value.selectedDocument ?: return
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                DocumentExporter(getApplication()).export(doc, format, templateContent.takeIf { it.isNotBlank() })
            }
            val uri = FileProvider.getUriForFile(getApplication(), "${getApplication<Application>().packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = format.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            getApplication<Application>().startActivity(
                Intent.createChooser(intent, "导出 ${file.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            status.value = "已导出 ${file.name}（已脱敏）"
        }
    }

    fun openRecentQuestion(message: ChatMessageEntity) {
        selectDocument(message.documentId)
    }

    fun openAssistantConversation() {
        syncSelectedDocument(uiState.value.documents, preferRecentQuestion = true)
    }

    fun selectLocalModel(name: String) {
        localModelManager.selectModel(name)
    }

    fun downloadLocalModel(name: String) {
        localModelManager.downloadModel(name, viewModelScope)
    }

    fun pauseLocalModelDownload(name: String) {
        localModelManager.pauseDownload(name)
    }

    fun uninstallLocalModel(name: String) {
        localModelManager.uninstallModel(name)
    }

    fun toggleCloud(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled && !repositoryHasQwenKey()) {
                settingsStore.setCloudEnabled(false)
                status.value = "请先保存 Qwen API Key，再开启云端增强"
                return@launch
            }
            settingsStore.setCloudEnabled(enabled)
            if (enabled) settingsStore.setDefaultLocalMode(false)
        }
    }

    fun setDefaultLocalMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setDefaultLocalMode(enabled)
            if (enabled) settingsStore.setCloudEnabled(false)
            status.value = if (enabled) "默认使用本地优先" else "可按需使用云端增强"
        }
    }

    fun setAutoClearClipboardAndCache(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setAutoClearClipboardAndCache(enabled)
            status.value = if (enabled) "已开启剪贴板与缓存自动清理" else "已关闭自动清理"
        }
    }

    fun setPerformanceMode(mode: String) {
        viewModelScope.launch {
            settingsStore.setPerformanceMode(mode)
            status.value = "性能模式已切换为 $mode"
        }
    }

    fun setParseThreadCount(count: Int) {
        viewModelScope.launch {
            settingsStore.setParseThreadCount(count)
            status.value = "并行解析线程已设置为 ${count.coerceIn(1, 8)}"
        }
    }

    fun setAccelerationEngine(engine: String) {
        viewModelScope.launch {
            settingsStore.setAccelerationEngine(engine)
            status.value = "加速引擎已切换为 $engine"
        }
    }

    fun setCloudModel(model: String) {
        viewModelScope.launch {
            settingsStore.setCloudModel(model)
            status.value = "云端模型已切换为 $model"
        }
    }

    fun importDocument(uri: Uri, displayName: String, sizeLabel: String) {
        viewModelScope.launch {
            val type = displayName.toDocumentType()
            val id = repository.registerImportedDocument(uri, displayName, type, sizeLabel)
            selectedDocumentId.value = id
            recentOpenedIds.value = (listOf(id) + recentOpenedIds.value.filterNot { it == id }).take(20)
            status.value = "已导入 $displayName，点击“分析”后调用 TextIn"
        }
    }

    fun analyzeDocument(documentId: Long) {
        val doc = uiState.value.documents.firstOrNull { it.id == documentId } ?: return
        val resultKey = analysisResultKey(documentId)
        if (workingTask.value == resultKey) {
            Log.w(TAG, "Analyze ignored: documentId=$documentId reason=already_running")
            status.value = "该文档正在分析中，请等待 TextIn 返回"
            return
        }
        workingTask.value = resultKey
        viewModelScope.launch {
            Log.i(TAG, "Analyze clicked: documentId=$documentId name=${doc.name} status=${doc.status} source=${doc.sourceUri}")
            selectedDocumentId.value = documentId
            val parallelism = effectiveParseParallelism()
            try {
                repository.markDocumentStatus(documentId, "准备分析", 1)
                status.value = "正在读取文档 · ${accelerationEngine.value} ${parallelism}线程"
                val bytes = withContext(Dispatchers.IO.limitedParallelism(parallelism)) {
                    withTimeout(FILE_READ_TIMEOUT_MS) { readDocumentBytes(doc) }
                }
                Log.i(TAG, "Analyze file read: documentId=$documentId bytes=${bytes?.size ?: 0}")
                if (bytes == null) {
                    repository.markDocumentStatus(documentId, "读取文件失败", 100)
                    insightResults.value = insightResults.value + (resultKey to "## 分析失败\n- 无法读取文档文件，请重新导入后再分析。")
                    status.value = "无法读取文档文件"
                    return@launch
                }
                status.value = "正在调用 TextIn 解析"
                Log.i(TAG, "Analyze TextIn start: documentId=$documentId bytes=${bytes.size}")
                val parse = withContext(Dispatchers.IO.limitedParallelism(parallelism)) {
                    repository.parseWithTextIn(documentId, doc.name, bytes)
                }
                Log.i(TAG, "Analyze TextIn done: documentId=$documentId success=${parse.success} message=${parse.message}")
                if (!parse.success) {
                    insightResults.value = insightResults.value + (resultKey to "## TextIn 解析未完成\n- ${parse.message}\n- 未继续调用 AI，避免基于不完整内容生成误导结果。")
                    status.value = "TextIn 解析未完成：${parse.message}"
                    return@launch
                }
                status.value = "TextIn 完成，正在调用 AI 分析"
                Log.i(TAG, "Analyze AI start: documentId=$documentId cloud=${cloudEnabled.value} model=${cloudModel.value}")
                val result = repository.generateDocumentInsight(documentId, "摘要", cloudEnabled.value, cloudModel.value, localGenerationConfig(doc))
                insightResults.value = insightResults.value + (analysisResultKey(documentId) to result)
                status.value = "TextIn 解析与 AI 分析完成"
            } catch (error: Throwable) {
                repository.markDocumentStatus(documentId, "分析失败", 100)
                insightResults.value = insightResults.value + (resultKey to "## 分析失败\n- ${error.message ?: error::class.java.simpleName}")
                status.value = "分析失败：${error.message ?: error::class.java.simpleName}"
            } finally {
                workingTask.value = ""
            }
        }
    }

    fun ask(question: String) {
        val state = uiState.value
        val doc = state.selectedDocument ?: return
        viewModelScope.launch {
            status.value = when {
                cloudEnabled.value -> "Qwen 思考中"
                state.localRuntimeStatus == "MNN 就绪" -> "MNN 本地模型生成中"
                else -> "本地规则兜底回答"
            }
            runCatching {
                val context = doc.markdown.ifBlank {
                    """
                    文档名称：${doc.name}
                    文档类型：${doc.type}
                    文件大小：${doc.sizeLabel}
                    解析状态：${doc.status}
                    来源：${doc.sourceUri.ifBlank { "本地示例文档" }}
                    提示：当前文档还没有完整 Markdown 内容，请基于已有元信息回答，并建议用户配置 TextIn 或重新解析。
                    """.trimIndent()
                }
                repository.askQwen(doc.id, question, context, doc.name, cloudEnabled.value, cloudModel.value, localGenerationConfig(doc))
            }.onFailure {
                repository.appendAssistantExchange(
                    documentId = doc.id,
                    userPrompt = question,
                    answer = "## 暂时无法完成回答\n- 当前生成流程遇到异常：${it.message ?: it::class.java.simpleName}\n- 你可以稍后重试，或先关闭云端增强/切回本地规则后再问。",
                    source = "异常兜底"
                )
            }
            status.value = if (cloudEnabled.value) "云端增强可用" else "本地优先"
        }
    }

    fun saveApiKeys(qwenKey: String, textInAppId: String, textInSecret: String) {
        viewModelScope.launch {
            if (qwenKey.isNotBlank()) repository.saveQwenApiKey(qwenKey)
            if (textInAppId.isNotBlank()) repository.saveTextInAppId(textInAppId)
            if (textInSecret.isNotBlank()) repository.saveTextInSecret(textInSecret)
            qwenKeyValue.value = repository.getQwenApiKey()
            textInAppIdValue.value = repository.getTextInAppId()
            textInSecretValue.value = repository.getTextInSecret()
            hasQwenKey.value = repositoryHasQwenKey()
            hasTextInKey.value = repositoryHasTextInKey()
            qwenTestStatus.value = "未测试"
            textInTestStatus.value = "未测试"
            status.value = "API Key 已安全保存"
        }
    }

    fun deleteApiKeys() {
        viewModelScope.launch {
            repository.deleteAllApiKeys()
            settingsStore.setCloudEnabled(false)
            qwenKeyValue.value = ""
            textInAppIdValue.value = ""
            textInSecretValue.value = ""
            hasQwenKey.value = false
            hasTextInKey.value = false
            qwenTestStatus.value = "未测试"
            textInTestStatus.value = "未测试"
            status.value = "API Key 已删除，云端增强已关闭"
        }
    }

    fun testQwenApiKey(candidate: String) {
        viewModelScope.launch {
            qwenTestStatus.value = "测试中..."
            qwenTestStatus.value = repository.testQwenApiKey(candidate, cloudModel.value)
            status.value = "Qwen API ${qwenTestStatus.value}"
        }
    }

    fun testTextInCredentials(appId: String, secret: String) {
        viewModelScope.launch {
            textInTestStatus.value = "测试中..."
            textInTestStatus.value = repository.testTextInCredentials(appId, secret)
            status.value = "TextIn API ${textInTestStatus.value}"
        }
    }

    fun generateInsight(mode: String) {
        val doc = uiState.value.selectedDocument ?: return
        viewModelScope.launch {
            val resultKey = insightResultKey(doc.id, mode)
            try {
                workingTask.value = resultKey
                status.value = "正在生成 $mode · ${if (cloudEnabled.value) cloudModel.value else "本地"}"
                val prompt = insightPromptText(mode)
                val result = runCatching {
                    repository.generateDocumentInsight(doc.id, mode, cloudEnabled.value, cloudModel.value, localGenerationConfig(doc))
                }.getOrElse {
                    "## $mode 生成失败\n- 异常：${it.message ?: it::class.java.simpleName}\n- 请稍后重试，或先检查云端/API/本地模型设置。"
                }
                insightResults.value = insightResults.value + (resultKey to result)
                val source = when {
                    cloudEnabled.value -> cloudModel.value
                    repository.localRuntimeStatus() == "MNN 就绪" -> "MNN"
                    else -> "本地规则"
                }
                repository.appendAssistantExchange(doc.id, prompt, result, source)
                status.value = "$mode 已生成"
            } finally {
                workingTask.value = ""
            }
        }
    }

    fun extractTemplate(templateName: String, customInstruction: String = "") {
        val doc = uiState.value.selectedDocument ?: return
        val resultKey = templateResultKey(doc.id, templateName, customInstruction)
        if (workingTask.value == resultKey) {
            Log.w(TAG, "TextIn extract ignored: documentId=${doc.id} template=$templateName reason=already_running")
            status.value = "$templateName 正在抽取中，请等待 TextIn 返回"
            return
        }
        workingTask.value = resultKey
        viewModelScope.launch {
            Log.i(TAG, "TextIn extract clicked: documentId=${doc.id} name=${doc.name} template=$templateName")
            val parallelism = effectiveParseParallelism()
            try {
                status.value = "正在调用 TextIn 抽取上下文 · ${accelerationEngine.value} ${parallelism}线程"
                val bytes = withContext(Dispatchers.IO.limitedParallelism(parallelism)) {
                    withTimeout(FILE_READ_TIMEOUT_MS) { readDocumentBytes(doc) }
                }
                Log.i(TAG, "TextIn extract file read: documentId=${doc.id} bytes=${bytes?.size ?: 0}")
                val result = withContext(Dispatchers.IO.limitedParallelism(parallelism)) {
                    repository.extractTemplate(
                        documentId = doc.id,
                        templateName = templateName,
                        fileName = doc.name,
                        fileBytes = bytes,
                        customInstruction = customInstruction,
                        useCloud = cloudEnabled.value,
                        cloudModel = cloudModel.value,
                        localConfig = localGenerationConfig(doc)
                    )
                }
                templateResults.value = templateResults.value + (resultKey to result)
                status.value = "$templateName 已抽取"
            } catch (error: Throwable) {
                templateResults.value = templateResults.value + (resultKey to "## $templateName 抽取失败\n- ${error.message ?: error::class.java.simpleName}")
                status.value = "$templateName 抽取失败：${error.message ?: error::class.java.simpleName}"
            } finally {
                workingTask.value = ""
            }
        }
    }

    fun templateResultKey(documentId: Long, templateName: String, customInstruction: String = ""): String {
        return "$documentId::$templateName::${customInstruction.trim()}"
    }

    fun analysisResultKey(documentId: Long): String = insightResultKey(documentId, "摘要")

    private fun insightResultKey(documentId: Long, mode: String): String = "$documentId::$mode"

    private fun repositoryHasQwenKey(): Boolean = repository.hasQwenApiKey()
    private fun repositoryHasTextInKey(): Boolean = repository.hasTextInCredentials()

    private fun syncSelectedDocument(docs: List<DocumentEntity>, preferRecentQuestion: Boolean) {
        if (docs.isEmpty()) {
            selectedDocumentId.value = 0L
            return
        }
        val selectedExists = docs.any { it.id == selectedDocumentId.value }
        if (selectedExists && !preferRecentQuestion) return
        val recentDocumentId = recentQuestions.value.firstOrNull { recent ->
            docs.any { it.id == recent.documentId }
        }?.documentId
        val nextId = if (preferRecentQuestion) {
            recentDocumentId ?: selectedDocumentId.value.takeIf { id -> docs.any { it.id == id } } ?: docs.first().id
        } else {
            selectedDocumentId.value.takeIf { id -> docs.any { it.id == id } } ?: recentDocumentId ?: docs.first().id
        }
        if (selectedDocumentId.value != nextId) {
            selectedDocumentId.value = nextId
        }
    }

    private fun effectiveParseParallelism(): Int {
        val base = parseThreadCount.value.coerceIn(1, 8)
        return when (accelerationEngine.value) {
            "省电队列" -> 1
            "MNN (Arm SME2)" -> maxOf(base, 4).coerceAtMost(8)
            "并行队列" -> base
            else -> when (performanceMode.value) {
                "省电模式" -> 1
                "极速模式" -> maxOf(base, 4).coerceAtMost(8)
                else -> base.coerceAtMost(4)
            }
        }
    }

    private fun localGenerationConfig(document: DocumentEntity? = null): LocalGenerationConfig {
        return LocalGenerationConfig(
            threads = effectiveParseParallelism(),
            accelerationEngine = accelerationEngine.value,
            maxTokens = if (performanceMode.value == "极速模式") 1024 else 768,
            imagePaths = resolveLocalImagePath(document)?.let { listOf(it) }.orEmpty(),
            enableMnn = true
        )
    }

    private fun insightPromptText(mode: String): String = when (mode) {
        "摘要" -> "请基于当前文档生成摘要"
        "提纲" -> "请基于当前文档生成提纲"
        "重点句" -> "请提取当前文档的重点句"
        "待办/风险点" -> "请提取当前文档的待办和风险点"
        else -> "请基于当前文档生成$mode"
    }

    private fun resolveLocalImagePath(doc: DocumentEntity?): String? {
        if (doc == null || doc.type !in listOf("图片", "IMAGE", "PNG", "JPG", "JPEG")) return null
        return runCatching {
            val app = getApplication<Application>()
            val target = java.io.File(app.cacheDir, "vl_inputs/${doc.id}_${doc.name.replace(Regex("""[\\/:*?"<>|]"""), "_")}")
            target.parentFile?.mkdirs()
            when {
                doc.sourceUri.startsWith("asset://") -> {
                    app.assets.open(doc.sourceUri.removePrefix("asset://")).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    target.absolutePath
                }
                doc.sourceUri.isNotBlank() -> {
                    app.contentResolver.openInputStream(Uri.parse(doc.sourceUri))?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    target.takeIf { it.isFile }?.absolutePath
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun readDocumentBytes(doc: DocumentEntity): ByteArray? {
        return runCatching {
            when {
                doc.sourceUri.startsWith("asset://") -> {
                    val path = doc.sourceUri.removePrefix("asset://")
                    getApplication<Application>().assets.open(path).use { it.readBytes() }
                }
                doc.sourceUri.isNotBlank() -> {
                    getApplication<Application>().contentResolver.openInputStream(Uri.parse(doc.sourceUri))?.use { it.readBytes() }
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun String.toDocumentType(): String {
        val ext = substringAfterLast('.', "文件").uppercase()
        return when (ext) {
            "JPG", "JPEG", "PNG", "WEBP", "BMP", "HEIC" -> "图片"
            "DOC" -> "DOCX"
            "PPT" -> "PPTX"
            "XLS", "CSV" -> "XLSX"
            else -> ext
        }
    }

    class Factory(
        private val application: Application,
        private val repository: DocumentRepository,
        private val settingsStore: SettingsStore,
        private val localModelManager: LocalModelManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DocPilotViewModel(application, repository, settingsStore, localModelManager) as T
        }
    }
}
