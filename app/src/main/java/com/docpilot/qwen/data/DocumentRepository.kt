package com.docpilot.qwen.data

import android.net.Uri
import android.util.Base64
import android.util.Log
import com.docpilot.qwen.data.local.ChatMessageEntity
import com.docpilot.qwen.data.local.DocumentDao
import com.docpilot.qwen.data.local.DocumentEntity
import com.docpilot.qwen.data.local.ExtractionEntity
import com.docpilot.qwen.data.local.LocalGenerationConfig
import com.docpilot.qwen.data.local.LocalModelEngine
import com.docpilot.qwen.data.local.PageCitation
import com.docpilot.qwen.data.network.QwenApi
import com.docpilot.qwen.data.network.QwenChatRequest
import com.docpilot.qwen.data.network.QwenChatResponse
import com.docpilot.qwen.data.network.QwenMessage
import com.docpilot.qwen.data.network.QwenStreamClient
import com.docpilot.qwen.data.network.TextInApi
import com.docpilot.qwen.data.network.TextInExtractionField
import com.docpilot.qwen.data.network.TextInExtractionRequest
import com.docpilot.qwen.data.network.TextInExtractionResponse
import com.docpilot.qwen.data.network.TextInExtractionTable
import com.docpilot.qwen.data.network.TextInParseResponse
import com.docpilot.qwen.data.network.TextInParseResult
import com.docpilot.qwen.security.ApiKeyStore
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import retrofit2.Response
import java.io.File
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

class DocumentRepository(
    private val documentDao: DocumentDao,
    private val qwenApi: QwenApi,
    private val qwenStreamClient: QwenStreamClient,
    private val textInApi: TextInApi,
    private val apiKeyStore: ApiKeyStore,
    private val localModelEngine: LocalModelEngine
) {
    private val gson = Gson()
    private val prettyGson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun observeDocuments(): Flow<List<DocumentEntity>> = documentDao.observeDocuments()
    fun observeMessages(documentId: Long): Flow<List<ChatMessageEntity>> = documentDao.observeMessages(documentId)
    fun observeRecentQuestions(): Flow<List<ChatMessageEntity>> = documentDao.observeRecentQuestions()
    fun observeExtractions(documentId: Long): Flow<List<ExtractionEntity>> = documentDao.observeExtractions(documentId)
    fun observeAllExtractions(): Flow<List<ExtractionEntity>> = documentDao.observeAllExtractions()

    suspend fun seedDemoData() {
        if (documentDao.countDocuments() > 0) {
            return
        }

        documentDao.deleteMessages()
        documentDao.deleteExtractions()
        documentDao.deleteDocuments()

        val ids = REAL_CASES.map { sample ->
            val id = documentDao.insertDocument(sample.document)
            documentDao.updateParsedContent(id, sample.markdown, sample.json)
            sample.extractions.forEach { extraction ->
                documentDao.insertExtraction(
                    ExtractionEntity(
                        documentId = id,
                        templateName = extraction.first,
                        content = extraction.second,
                        source = "真实案例预处理"
                    )
                )
            }
            id
        }

        documentDao.insertMessage(ChatMessageEntity(documentId = ids[0], role = "user", content = "这份 IT 行业报告最重要的结论是什么？", source = "2024年IT行业研究报告.pdf"))
        documentDao.insertMessage(ChatMessageEntity(documentId = ids[0], role = "assistant", content = "报告重点围绕 IT 行业增长、云计算、AI 应用、企业数字化和安全合规展开。建议优先关注云端基础设施、AI 工具链、行业软件和数据安全四类机会。", source = "真实案例预处理"))
        documentDao.insertMessage(ChatMessageEntity(documentId = ids[4], role = "user", content = "会议白板里有哪些待办和风险？", source = "会议白板.png"))
        documentDao.insertMessage(ChatMessageEntity(documentId = ids[4], role = "assistant", content = "待办包括接口联调、测试回归、上线准备；风险包括支付接口超时、Android 13 兼容、测试环境数据不一致。性能压测未完成，是需要优先处理的阻塞项。", source = "白板图像整理"))
    }

    fun hasQwenApiKey(): Boolean = apiKeyStore.getQwenApiKey().isNotBlank()
    fun hasTextInCredentials(): Boolean = apiKeyStore.getTextInAppId().isNotBlank() && apiKeyStore.getTextInSecret().isNotBlank()
    fun getQwenApiKey(): String = apiKeyStore.getQwenApiKey()
    fun getTextInAppId(): String = apiKeyStore.getTextInAppId()
    fun getTextInSecret(): String = apiKeyStore.getTextInSecret()
    fun saveQwenApiKey(value: String) = apiKeyStore.saveQwenApiKey(value)
    fun saveTextInAppId(value: String) = apiKeyStore.saveTextInAppId(value)
    fun saveTextInSecret(value: String) = apiKeyStore.saveTextInSecret(value)
    fun deleteAllApiKeys() = apiKeyStore.deleteAll()

    suspend fun deleteDocument(documentId: Long) {
        documentDao.deleteMessagesForDocument(documentId)
        documentDao.deleteExtractionsForDocument(documentId)
        documentDao.deleteDocument(documentId)
    }

    suspend fun deleteMessage(messageId: Long) {
        documentDao.deleteMessage(messageId)
    }

    suspend fun deleteExtraction(extractionId: Long) {
        documentDao.deleteExtraction(extractionId)
    }

    suspend fun finishInterruptedGenerations() {
        documentDao.finishBlankStreamingMessages(
            """
            ## 生成已中断
            - 上一次 AI 生成没有正常结束，可能是本地模型初始化过慢、进程被系统回收，或模型暂不可用。
            - 本次已停止显示“流式生成中”，请重新发送问题。
            """.trimIndent()
        )
        documentDao.finishPartialStreamingMessages(
            "\n\n## 生成被中断\n- 上一次生成没有收到结束信号，已自动停止流式状态。"
        )
    }

    suspend fun renameDocument(documentId: Long, name: String) {
        if (name.isBlank()) return
        documentDao.renameDocument(documentId, name.trim(), "刚刚")
    }

    suspend fun updateDocumentOrganization(documentId: Long, tags: String, folder: String) {
        val normalizedTags = tags.split(",", "，", "#")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(",")
        documentDao.updateDocumentOrganization(documentId, normalizedTags, folder.ifBlank { "默认" }.trim(), "刚刚")
    }

    suspend fun saveTemplateResult(documentId: Long, templateName: String, content: String, source: String = "用户保存") {
        if (content.isBlank()) return
        insertExtractionIfNew(
            ExtractionEntity(
                documentId = documentId,
                templateName = templateName,
                content = content,
                source = source
            )
        )
    }

    suspend fun testQwenApiKey(candidate: String = "", model: String = "qwen3.6-plus"): String {
        val apiKey = candidate.ifBlank { apiKeyStore.getQwenApiKey() }
        if (apiKey.isBlank()) return "请输入 Qwen API Key"
        val request = QwenChatRequest(
            model = model,
            messages = listOf(QwenMessage(role = "user", content = "只回复 OK，用于测试 API 是否可用。")),
            temperature = 0.0
        )
        return runCatching {
            val answer = qwenContent(qwenApi.chat("Bearer $apiKey", request))
            if (answer.isNotBlank()) "测试通过：Qwen 可用" else "测试失败：接口无返回"
        }.getOrElse { "测试失败：${it.readableMessage()}" }
    }

    suspend fun testTextInCredentials(appIdCandidate: String = "", secretCandidate: String = ""): String {
        val appId = appIdCandidate.ifBlank { apiKeyStore.getTextInAppId() }
        val secret = secretCandidate.ifBlank { apiKeyStore.getTextInSecret() }
        if (appId.isBlank() || secret.isBlank()) return "请输入 TextIn app-id 与 secret"
        val body = "DocPilot Qwen credential test".toByteArray()
            .toRequestBody("text/plain".toMediaTypeOrNull())
        val part = MultipartBody.Part.createFormData("file", "docpilot-test.txt", body)
        return runCatching {
            Log.i(TAG, "TextIn credential test start")
            val response = withTimeout(TEXTIN_TIMEOUT_MS) {
                textInApi.parseSync(appId = appId, secretCode = secret, file = part)
            }
            parseTextInResponse(response, requireMarkdown = false)
            Log.i(TAG, "TextIn credential test success")
            "测试通过：TextIn 可连接"
        }.getOrElse {
            Log.e(TAG, "TextIn credential test failed", it)
            "测试失败：${it.readableMessage()}"
        }
    }

    suspend fun registerImportedDocument(uri: Uri, displayName: String, type: String, sizeLabel: String): Long {
        return documentDao.insertDocument(
            DocumentEntity(
                name = displayName,
                type = type,
                sizeLabel = sizeLabel,
                status = "已导入，待分析",
                updatedAt = "刚刚",
                sourceUri = uri.toString()
            )
        )
    }

    suspend fun markDocumentStatus(documentId: Long, status: String, progress: Int? = null) {
        if (progress == null) {
            documentDao.updateStatus(documentId, status)
        } else {
            documentDao.updateParseProgress(documentId, progress, status)
        }
    }

    suspend fun parseWithTextIn(documentId: Long, fileName: String, bytes: ByteArray): ParseOutcome {
        val appId = apiKeyStore.getTextInAppId()
        val secret = apiKeyStore.getTextInSecret()
        val currentDocument = documentDao.getDocument(documentId)
        val safeFallback = localFallbackMarkdown(fileName, bytes, currentDocument?.markdown.orEmpty())
        documentDao.updateParseProgress(documentId, 3, "准备解析")
        if (appId.isBlank() || secret.isBlank()) {
            Log.w(TAG, "TextIn parse skipped: documentId=$documentId file=$fileName reason=missing_credentials")
            documentDao.updateStatus(documentId, "缺少 TextIn Key")
            return ParseOutcome(
                success = false,
                markdown = "",
                source = "TextIn",
                message = "缺少 TextIn app-id 或 secret，未发起 TextIn 调用。"
            )
        }

        documentDao.updateParseProgress(documentId, 8, "TextIn 分析中")
        val parsed = runCatching {
            Log.i(TAG, "TextIn parse start: documentId=$documentId file=$fileName bytes=${bytes.size}")
            val body = bytes.toRequestBody("application/octet-stream".toMediaTypeOrNull())
            val part = MultipartBody.Part.createFormData("file", fileName, body)
            withTimeout(TEXTIN_TIMEOUT_MS) {
                parseTextInResponse(textInApi.parseSync(appId = appId, secretCode = secret, file = part))
            }
        }
        return parsed.fold(
            onSuccess = { response ->
                Log.i(TAG, "TextIn parse success: documentId=$documentId markdown=${response.markdown.length} pages=${response.pages.size}")
                documentDao.updateParseProgress(documentId, 70, "整理页级结构")
                val markdown = response.markdown.ifBlank {
                    safeFallback
                }
                val parser = if (response.markdown.isBlank()) {
                    "TextIn xParse（无正文，已保留本地兜底）"
                } else {
                    "TextIn xParse"
                }
                val citations = citationsFromTextIn(fileName, markdown, response.pages)
                documentDao.updateParsedContent(
                    documentId,
                    markdown,
                    documentStructureJson(fileName, markdown, citations.size, parser, citations),
                    citations.size,
                    gson.toJson(citations),
                    100
                )
                documentDao.updateStatus(documentId, if (response.markdown.isBlank()) "TextIn 无正文" else "已解析")
                ParseOutcome(
                    success = response.markdown.isNotBlank(),
                    markdown = response.markdown,
                    source = parser,
                    message = if (response.markdown.isBlank()) "TextIn 已返回，但没有可用正文。" else "TextIn 解析成功。"
                )
            },
            onFailure = { error ->
                Log.e(TAG, "TextIn parse failed: documentId=$documentId file=$fileName", error)
                val citations = fallbackCitations(fileName, safeFallback)
                documentDao.updateParsedContent(
                    documentId,
                    safeFallback,
                    documentStructureJson(fileName, safeFallback, citations.size, "TextIn 失败，已保留本地兜底：${error.readableMessage()}", citations),
                    citations.size,
                    gson.toJson(citations),
                    100
                )
                documentDao.updateStatus(documentId, "TextIn 解析失败")
                ParseOutcome(
                    success = false,
                    markdown = "",
                    source = "TextIn",
                    message = error.readableMessage()
                )
            }
        )
    }

    fun localRuntimeStatus(): String = localModelEngine.runtimeStatus()

    suspend fun askQwen(
        documentId: Long,
        question: String,
        context: String,
        sourceName: String,
        useCloud: Boolean,
        cloudModel: String,
        localConfig: LocalGenerationConfig
    ): String {
        documentDao.insertMessage(ChatMessageEntity(documentId = documentId, role = "user", content = question, source = sourceName))
        val apiKey = apiKeyStore.getQwenApiKey()
        val usableContext = context.ifBlank {
            documentDao.getDocument(documentId)?.let {
                "文档名称：${it.name}\n文档类型：${it.type}\n解析状态：${it.status}\n文件大小：${it.sizeLabel}"
            }.orEmpty()
        }
        val localContext = compactForLocalAi(usableContext)
        if (!useCloud || apiKey.isBlank()) {
            return coroutineScope {
                val assistantId = documentDao.insertMessage(
                    ChatMessageEntity(documentId = documentId, role = "assistant", content = "", source = "MNN", streaming = true)
                )
                var timedOutByWatchdog = false
                val fallback = aiUnavailableMessage(
                    title = "暂时无法生成",
                    reason = "MNN 在 ${LOCAL_AI_TIMEOUT_MS / 1000} 秒内没有返回可用内容。当前本地状态：${localRuntimeStatus()}",
                    context = localContext
                )
                val watchdog = launch {
                    delay(LOCAL_AI_TIMEOUT_MS)
                    timedOutByWatchdog = true
                    documentDao.updateMessageContent(assistantId, fallback, streaming = false)
                }
                val local = runCatching {
                    withTimeout(LOCAL_AI_TIMEOUT_MS) {
                        localModelEngine.answer(localContext, question, localConfig.copy(maxTokens = minOf(localConfig.maxTokens, 512))) { partial ->
                            if (!timedOutByWatchdog) {
                                documentDao.updateMessageContent(assistantId, partial, streaming = true)
                            }
                        }
                    }
                }.getOrNull()
                watchdog.cancel()
                val answer = local ?: fallback
                documentDao.updateMessageContent(assistantId, answer, streaming = false)
                answer
            }
        }

        val prompt = """
            任务：回答用户关于当前文档的问题。

            回答要求：
            1. 只基于下方“文档内容”回答，不要编造没有出现过的事实、金额、日期、页码或人名。
            2. 如果文档内容不足，请先说明“当前可确认的信息”，再列出“需要补充解析/复核的信息”。
            3. 优先使用短段落 + 要点清单，手机端阅读要紧凑。
            4. 只要内容中出现来源、页码、章节、表格字段，就在相关结论后标注来源。
            5. 如果用户的问题要求判断、建议或下一步行动，请单独给出“建议”小节。

            输出格式：
            ## 结论
            - ...

            ## 依据
            - ...

            ## 建议
            - ...

            文档内容：
            $usableContext

            用户问题：
            $question
        """.trimIndent()
        val request = QwenChatRequest(
            model = cloudModel,
            messages = listOf(
                QwenMessage(role = "system", content = "你是 DocPilot Qwen 的中文文档研究助手，擅长来源可溯的摘要、问答、结构化抽取和行动建议。回答必须忠于文档、结构清晰、适合手机阅读。"),
                QwenMessage(role = "user", content = qwenUserContent(prompt, cloudModel, localConfig))
            )
        )

        val assistantId = documentDao.insertMessage(
            ChatMessageEntity(documentId = documentId, role = "assistant", content = "", source = cloudModel, streaming = true)
        )
        val citations = documentDao.getDocument(documentId)?.citationsJson.orEmpty().ifBlank { "[]" }
        var qwenFailure: Throwable? = null
        val answer = runCatching {
            val buffer = StringBuilder()
            withContext(Dispatchers.IO) {
                withTimeout(AI_TIMEOUT_MS) {
                    qwenStreamClient.streamChat("Bearer $apiKey", request) { delta ->
                        buffer.append(delta)
                        documentDao.updateMessageContent(assistantId, buffer.toString(), streaming = true, citationsJson = citations)
                    }
                }
            }
        }.getOrElse {
            qwenFailure = it
            runCatching {
                withTimeout(AI_TIMEOUT_MS) {
                    qwenContent(qwenApi.chat("Bearer $apiKey", request))
                }
            }.onFailure { error -> qwenFailure = error }.getOrDefault("")
        }.ifBlank {
            val local = runCatching {
                withTimeout(LOCAL_AI_TIMEOUT_MS) {
                    localModelEngine.answer(localContext, question, localConfig.copy(maxTokens = minOf(localConfig.maxTokens, 512)))
                }
            }.getOrNull()
            if (local.isNullOrBlank()) {
                val reason = qwenFailure?.readableMessage()
                aiUnavailableMessage(
                    title = if (reason.isNullOrBlank()) "AI 未生成结果" else "Qwen 调用失败，且 MNN 未生成",
                    reason = reason?.let { "Qwen 失败原因：$it；本地状态：${localRuntimeStatus()}" } ?: "本地状态：${localRuntimeStatus()}",
                    context = localContext
                )
            } else {
                local
            }
        }

        documentDao.updateMessageContent(assistantId, answer, streaming = false, citationsJson = citations)
        return answer
    }

    private fun localAnswer(question: String, context: String): String {
        val compact = context.lines().map { it.trim() }.filter { it.isNotBlank() }.take(8)
        val basis = if (compact.isEmpty()) {
            "当前只检测到文档记录，尚未获得可用正文。"
        } else {
            compact.joinToString("\n") { "- $it" }
        }
        return """
            ## 本地回答
            我先基于当前本地可用信息回答。由于云端增强未开启或暂不可用，以下结果更偏“可读整理”和“线索提取”。

            ## 当前可确认的信息
            $basis

            ## 针对你的问题
            $question

            ## 初步判断
            - 如果上方信息已经包含相关结论，请优先按文档原文或字段理解。
            - 如果只看到文件名、类型、状态等元信息，说明正文解析还不完整，需要先补充 TextIn 解析结果。

            ## 建议
            - 需要页码、表格和段落引用时，请先在设置中保存并测试 TextIn API，然后重新导入或解析文档。
            - 需要更完整推理、跨段归纳或复杂抽取时，可以开启 Qwen 云端增强。
        """.trimIndent()
    }

    private fun compactForLocalAi(context: String): String {
        val normalized = context
            .replace(Regex("""(?is)<script\b[^>]*>.*?</script>"""), "")
            .replace(Regex("""(?is)<style\b[^>]*>.*?</style>"""), "")
            .replace(Regex("""(?is)<[^>]+>"""), " ")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace(Regex("""[ \t]+"""), " ")
        val usefulLines = normalized.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.length > 240 && it.count { char -> char == '{' || char == '}' || char == ':' || char == ',' } > 20 }
            .distinct()
        return usefulLines.joinToString("\n").take(LOCAL_CONTEXT_CHAR_LIMIT)
    }

    suspend fun generateDocumentInsight(
        documentId: Long,
        mode: String,
        useCloud: Boolean,
        cloudModel: String,
        localConfig: LocalGenerationConfig
    ): String {
        val document = documentDao.getDocument(documentId) ?: return "请先选择一个文档。"
        val rawContext = document.markdown.ifBlank { localEmptyDocumentMessage(document.name) }
        val context = if (useCloud) {
            rawContext.take(CLOUD_CONTEXT_CHAR_LIMIT)
        } else {
            compactForLocalAi(rawContext)
        }
        val prompt = when (mode) {
            "摘要" -> """
                请生成一份“高信号文档摘要”。
                输出要求：
                ## 一句话结论
                用一句话说明这份文档最重要的结论。
                ## 关键要点
                3-6 条，每条包含具体事实、指标、对象或结论。
                ## 适合继续追问的问题
                给出 3 个用户可能继续追问的问题。
                ## 来源线索
                列出可追踪的页码、章节、表格或字段；没有来源时说明需复核。
            """.trimIndent()
            "提纲" -> """
                请把文档整理为“可继续阅读/汇报”的分层提纲。
                输出要求：
                ## 主题
                ## 一级提纲
                用 1、2、3 编号组织，每个一级点下面给 2-3 个二级要点。
                ## 关键证据
                标出支撑提纲的数字、表格、章节或来源线索。
                ## 阅读顺序建议
                告诉用户先看哪些部分最省时间。
            """.trimIndent()
            "重点句" -> """
                请抽取“值得引用或复核的重点句”。
                输出要求：
                ## 最值得引用
                3-5 条原文/近原文表达，尽量保留数字、主体和动作。
                ## 为什么重要
                每条用一句话说明价值：结论、风险、决策、数据或行动依据。
                ## 复核提示
                标注可能需要确认的来源页码、口径或上下文。
            """.trimIndent()
            "待办/风险点" -> """
                请从文档中抽取“待办、风险和复核项”。
                输出要求：
                ## 待办事项
                用表格输出：事项 | 负责人/相关方 | 截止时间 | 优先级 | 依据。
                ## 风险点
                用表格输出：风险 | 影响 | 触发条件 | 建议动作。
                ## 需要补充确认
                列出文档中缺失或表述不清的信息。
            """.trimIndent()
            else -> "请基于文档生成结构化分析，包含结论、依据和下一步建议。"
        }
        return completeWithQwenOrLocal(
            system = "你是 DocPilot Qwen 文研助手。你要把复杂文档整理成手机端易读、可复核、可行动的中文结果。必须忠于文档，避免空泛套话，尽量保留来源线索。",
            user = """
                文档名称：${document.name}

                $prompt

                文档内容：
                $contentBoundary
                $context
                $contentBoundary
            """.trimIndent(),
            fallback = localInsight(mode, document),
            useCloud = useCloud,
            cloudModel = cloudModel,
            localConfig = localConfig
        )
    }

    suspend fun extractTemplate(
        documentId: Long,
        templateName: String,
        fileName: String = "",
        fileBytes: ByteArray? = null,
        customInstruction: String = "",
        useCloud: Boolean,
        cloudModel: String,
        localConfig: LocalGenerationConfig
    ): String {
        val document = documentDao.getDocument(documentId) ?: return "请先选择一个文档。"
        val result = extractTemplateWithTextIn(
            documentId = documentId,
            templateName = templateName,
            fileName = fileName.ifBlank { document.name },
            fileBytes = fileBytes,
            customInstruction = customInstruction,
            localConfig = localConfig
        )
        insertExtractionIfNew(
            ExtractionEntity(
                documentId = documentId,
                templateName = templateName,
                content = result,
                source = "TextIn + MNN"
            )
        )
        return result
    }

    private suspend fun insertExtractionIfNew(extraction: ExtractionEntity) {
        val existing = documentDao.findExtraction(
            documentId = extraction.documentId,
            templateName = extraction.templateName,
            content = extraction.content
        )
        if (existing == null) {
            documentDao.insertExtraction(extraction)
        }
    }

    suspend fun appendAssistantExchange(documentId: Long, userPrompt: String, answer: String, source: String) {
        documentDao.insertMessage(ChatMessageEntity(documentId = documentId, role = "user", content = userPrompt, source = source))
        documentDao.insertMessage(ChatMessageEntity(documentId = documentId, role = "assistant", content = answer, source = source))
    }

    private suspend fun extractTemplateWithTextIn(
        documentId: Long,
        templateName: String,
        fileName: String,
        fileBytes: ByteArray?,
        customInstruction: String,
        localConfig: LocalGenerationConfig
    ): String {
        val appId = apiKeyStore.getTextInAppId()
        val secret = apiKeyStore.getTextInSecret()
        if (appId.isBlank() || secret.isBlank()) {
            Log.w(TAG, "TextIn Skill extract skipped: documentId=$documentId template=$templateName reason=missing_credentials")
            documentDao.updateStatus(documentId, "缺少 TextIn Key")
            error("缺少 TextIn app-id 或 secret，未发起 TextIn Skill 调用。")
        }
        if (fileBytes == null) {
            Log.w(TAG, "TextIn Skill extract skipped: documentId=$documentId template=$templateName reason=file_read_failed")
            documentDao.updateStatus(documentId, "读取文件失败")
            error("无法读取文档文件，未发起 TextIn Skill 调用。")
        }

        val spec = textInExtractionSpec(templateName, customInstruction)
        val request = TextInExtractionRequest(
            file = Base64.encodeToString(fileBytes, Base64.NO_WRAP),
            prompt = spec.prompt,
            fields = spec.fields,
            tableFields = spec.tableFields
        )
        Log.i(TAG, "TextIn Skill extract start: documentId=$documentId template=$templateName file=$fileName bytes=${fileBytes.size} fields=${spec.fields.size} tables=${spec.tableFields.size}")
        return runCatching {
            val response = withTimeout(TEXTIN_TIMEOUT_MS) {
                textInApi.extractEntities(appId = appId, secretCode = secret, request = request)
            }
            val result = parseTextInExtractionResponse(response)
            val compact = textInExtractionMarkdown(templateName, result, spec, customInstruction)
            val markdown = enhanceExtractionWithLocalAi(
                templateName = templateName,
                customInstruction = customInstruction,
                compactExtraction = compact,
                localConfig = localConfig
            )
            documentDao.updateStatus(documentId, "TextIn Skill 已抽取")
            Log.i(TAG, "TextIn Skill extract success: documentId=$documentId template=$templateName resultChars=${markdown.length}")
            markdown
        }.getOrElse { error ->
            Log.e(TAG, "TextIn Skill extract failed: documentId=$documentId template=$templateName", error)
            documentDao.updateStatus(documentId, "TextIn Skill 抽取失败")
            throw error
        }
    }

    private fun parseTextInExtractionResponse(response: Response<TextInExtractionResponse>): JsonElement {
        if (!response.isSuccessful) {
            val error = response.errorBody()?.string().orEmpty().take(800)
            error("TextIn Skill HTTP ${response.code()}${error.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty()}")
        }
        val body = response.body() ?: error("TextIn Skill 返回空响应")
        val okCode = body.code == null || body.code == 0 || body.code == 200
        if (!okCode) {
            error("TextIn Skill 服务返回 ${body.code}：${body.message ?: "未知错误"}")
        }
        return body.result ?: body.data ?: error("TextIn Skill 未返回 result/data：${body.message ?: "响应中缺少 result/data"}")
    }

    private suspend fun enhanceExtractionWithLocalAi(
        templateName: String,
        customInstruction: String,
        compactExtraction: String,
        localConfig: LocalGenerationConfig
    ): String {
        val status = localRuntimeStatus()
        if (status != "MNN 就绪" || !localConfig.enableMnn) {
            return "$compactExtraction\n\n## 本地 AI 展示\n- 暂时无法生成：$status。已先展示 TextIn 按要求抽取出的字段。"
        }
        val prompt = """
            你是手机端文档抽取结果整理助手。
            请只基于下方 TextIn 已抽取字段，按用户要求整理为简洁中文 Markdown。
            不要扩展、不要复述 OCR 坐标、不要输出原始 JSON。
            如果字段为空或未找到，直接写“未找到”。

            模板：$templateName
            用户要求：${customInstruction.ifBlank { templateName }}

            TextIn 已抽取字段：
            $contentBoundary
            ${compactExtraction.take(LOCAL_EXTRACTION_CONTEXT_LIMIT)}
            $contentBoundary
        """.trimIndent()
        val generated = runCatching {
            withTimeout(LOCAL_AI_TIMEOUT_MS) {
                localModelEngine.complete(prompt, localConfig.copy(maxTokens = minOf(localConfig.maxTokens, 512)))
            }
        }.getOrNull()
        return if (generated.isNullOrBlank()) {
            "$compactExtraction\n\n## 本地 AI 展示\n- 暂时无法生成：$status。已先展示 TextIn 按要求抽取出的字段。"
        } else {
            val basis = compactExtraction.lines()
                .dropWhile { it.startsWith("##") }
                .joinToString("\n")
                .trim()
            "## 本地 AI 展示\n$generated\n\n## TextIn 字段依据\n$basis"
        }
    }

    private fun textInExtractionMarkdown(
        templateName: String,
        result: JsonElement,
        spec: TextInExtractionSpec,
        customInstruction: String
    ): String {
        val fieldRows = spec.fields.map { field ->
            field.name to (findRequestedValue(result, field.name).ifBlank { "未找到" })
        }
        val tables = spec.tableFields.mapNotNull { table ->
            findRequestedTable(result, table)
        }
        return buildString {
            appendLine("## TextIn 抽取结果")
            appendLine("- 模式：$templateName")
            appendLine("- 来源：TextIn entity_extraction")
            if (customInstruction.isNotBlank()) appendLine("- 要求：${customInstruction.toMarkdownCell()}")
            appendLine()
            if (fieldRows.isNotEmpty()) {
                appendLine("## 关键字段")
                appendLine("| 字段 | 值 |")
                appendLine("| --- | --- |")
                fieldRows
                    .forEach { (key, value) ->
                        appendLine("| ${key.toMarkdownCell()} | ${value.toMarkdownCell()} |")
                    }
                appendLine()
            }
            tables.forEach { table ->
                appendLine("## ${table.title.ifBlank { "明细表" }}")
                appendLine(table.toMarkdown())
                appendLine()
            }
            appendLine("## 说明")
            appendLine("- 这里只展示本次模板/自定义要求对应的字段。")
            appendLine("- TextIn 返回的 OCR 坐标、版面块和底层结构已隐藏。")
        }.trim()
    }

    private data class TextInMarkdownTable(
        val title: String,
        val headers: List<String>,
        val rows: List<List<String>>
    )

    private fun TextInMarkdownTable.toMarkdown(): String = buildString {
        appendLine("| ${headers.joinToString(" | ") { it.toMarkdownCell() }} |")
        appendLine("| ${headers.joinToString(" | ") { "---" }} |")
        rows.take(MAX_TEXTIN_TABLE_ROWS).forEach { row ->
            appendLine("| ${row.joinToString(" | ") { it.toMarkdownCell() }} |")
        }
        if (rows.size > MAX_TEXTIN_TABLE_ROWS) {
            val hidden = rows.size - MAX_TEXTIN_TABLE_ROWS
            val summary = List(headers.size) { index ->
                when (index) {
                    0 -> "更多"
                    1 -> "已隐藏 $hidden 行，请查看原始 JSON"
                    else -> ""
                }
            }
            appendLine("| ${summary.joinToString(" | ") { it.toMarkdownCell() }} |")
        }
    }

    private fun findRequestedValue(element: JsonElement?, fieldName: String): String {
        if (element == null || element.isJsonNull) return ""
        val target = fieldName.normalizedFieldName()
        fun search(node: JsonElement?): String? {
            if (node == null || node.isJsonNull) return null
            if (node.isJsonObject) {
                val obj = node.asJsonObject
                obj.entrySet().firstOrNull { (key, _) -> key.normalizedFieldName() == target }?.let { (_, value) ->
                    return value.cellValue().takeIfUseful()
                }
                val label = firstStringValue(obj, "name", "key", "field", "field_name", "title", "label")
                if (label?.normalizedFieldName() == target) {
                    listOf("value", "text", "content", "result", "answer").forEach { key ->
                        obj.get(key)?.cellValue()?.takeIfUseful()?.let { return it }
                    }
                    return obj.entrySet()
                        .filterNot { it.key in setOf("name", "key", "field", "field_name", "title", "label") }
                        .joinToString("；") { (key, value) -> "$key：${value.cellValue()}" }
                        .takeIfUseful()
                }
                obj.entrySet().forEach { (_, value) ->
                    search(value)?.let { return it }
                }
            }
            if (node.isJsonArray) {
                node.asJsonArray.forEach { item ->
                    search(item)?.let { return it }
                }
            }
            return null
        }
        return search(element).orEmpty()
    }

    private fun findRequestedTable(element: JsonElement?, table: TextInExtractionTable): TextInMarkdownTable? {
        if (element == null || element.isJsonNull) return null
        val headers = table.fields.map { it.name }
        val targetTitle = table.title.normalizedFieldName()
        fun search(node: JsonElement?, path: String): TextInMarkdownTable? {
            if (node == null || node.isJsonNull) return null
            if (node.isJsonObject) {
                node.asJsonObject.entrySet().forEach { (key, value) ->
                    search(value, if (path.isBlank()) key else "$path.$key")?.let { return it }
                }
            }
            if (node.isJsonArray) {
                val objects = node.asJsonArray.mapNotNull { item -> item.takeIf { it.isJsonObject }?.asJsonObject }
                if (objects.isNotEmpty() && objects.size == node.asJsonArray.size()) {
                    val pathMatches = path.normalizedFieldName().contains(targetTitle)
                    val overlap = objects
                        .flatMap { obj -> obj.entrySet().map { it.key.normalizedFieldName() } }
                        .distinct()
                        .count { key -> headers.any { header -> header.normalizedFieldName() == key } }
                    if (pathMatches || overlap > 0) {
                        val rows = objects.map { obj ->
                            headers.map { header -> findRequestedValue(obj, header).ifBlank { "未找到" } }
                        }.filter { row -> row.any { it != "未找到" } }
                        if (rows.isNotEmpty()) {
                            return TextInMarkdownTable(table.title, headers, rows)
                        }
                    }
                }
                node.asJsonArray.forEachIndexed { index, item ->
                    search(item, "$path[$index]")?.let { return it }
                }
            }
            return null
        }
        return search(element, "")
    }

    private fun collectScalarRows(element: JsonElement?, path: String, rows: MutableList<Pair<String, String>>) {
        if (element == null || element.isJsonNull) return
        when {
            element.isJsonPrimitive -> {
                val label = path.ifBlank { "result" }
                rows += label.cleanTextInPath() to element.asString
            }
            element.isJsonArray -> {
                val array = element.asJsonArray
                if (array.all { it.isJsonPrimitive || it.isJsonNull }) {
                    val value = array.joinToString("；") { if (it.isJsonNull) "未找到" else it.asString }
                    rows += path.cleanTextInPath() to value
                } else {
                    array.forEachIndexed { index, item ->
                        collectScalarRows(item, "$path[$index]", rows)
                    }
                }
            }
            element.isJsonObject -> {
                element.asJsonObject.entrySet().forEach { (key, value) ->
                    val nextPath = if (path.isBlank()) key else "$path.$key"
                    collectScalarRows(value, nextPath, rows)
                }
            }
        }
    }

    private fun collectObjectArrayTables(element: JsonElement?, path: String, tables: MutableList<TextInMarkdownTable>) {
        if (element == null || element.isJsonNull) return
        when {
            element.isJsonObject -> {
                element.asJsonObject.entrySet().forEach { (key, value) ->
                    val nextPath = if (path.isBlank()) key else "$path.$key"
                    collectObjectArrayTables(value, nextPath, tables)
                }
            }
            element.isJsonArray -> {
                val objects = element.asJsonArray.mapNotNull { it.takeIf { item -> item.isJsonObject }?.asJsonObject }
                if (objects.isNotEmpty() && objects.size == element.asJsonArray.size()) {
                    val headers = objects
                        .flatMap { it.entrySet().map { entry -> entry.key } }
                        .distinct()
                        .take(MAX_TEXTIN_TABLE_COLUMNS)
                    if (headers.isNotEmpty()) {
                        val rows = objects.map { obj ->
                            headers.map { header -> obj.get(header).cellValue() }
                        }
                        tables += TextInMarkdownTable(path.cleanTextInPath(), headers, rows)
                    }
                }
                element.asJsonArray.forEachIndexed { index, item ->
                    collectObjectArrayTables(item, "$path[$index]", tables)
                }
            }
        }
    }

    private fun JsonElement?.cellValue(): String {
        if (this == null || isJsonNull) return "未找到"
        return when {
            isJsonPrimitive -> asString
            isJsonArray -> asJsonArray.joinToString("；") { it.cellValue() }
            isJsonObject -> {
                val obj = asJsonObject
                firstStringValue(obj, "value", "text", "content", "name", "result")
                    ?: prettyGson.toJson(this)
            }
            else -> toString()
        }
    }

    private fun firstStringValue(obj: JsonObject, vararg keys: String): String? {
        return keys.firstNotNullOfOrNull { key ->
            obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
        }
    }

    private fun String.cleanTextInPath(): String {
        return trim('.')
            .replace("result.", "")
            .replace("details.", "")
            .replace(".row", "")
            .replace("row.", "")
            .ifBlank { "result" }
    }

    private fun String.normalizedFieldName(): String {
        return lowercase()
            .replace(Regex("""[\s_:\-：，,。/\\|（）()\[\]{}"'`]+"""), "")
    }

    private fun String.takeIfUseful(): String? {
        val value = trim()
        if (value.isBlank()) return null
        if (value.equals("null", ignoreCase = true)) return null
        if (value == "{}" || value == "[]") return null
        return value
    }

    private fun String.toMarkdownCell(): String {
        val compact = replace("\r", " ")
            .replace("\n", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { "未找到" }
        val clipped = if (compact.length > MAX_TEXTIN_CELL_CHARS) {
            compact.take(MAX_TEXTIN_CELL_CHARS) + "..."
        } else {
            compact
        }
        return clipped.replace("|", "\\|")
    }

    private data class TextInExtractionSpec(
        val prompt: String,
        val fields: List<TextInExtractionField>,
        val tableFields: List<TextInExtractionTable> = emptyList()
    )

    private fun textInExtractionSpec(templateName: String, customInstruction: String): TextInExtractionSpec {
        fun fields(vararg names: String): List<TextInExtractionField> = names.map { TextInExtractionField(it, "请从文档中提取“$it”，未找到则返回未找到。") }
        fun table(title: String, vararg names: String): TextInExtractionTable =
            TextInExtractionTable(title = title, fields = fields(*names), description = "请按行提取$title，保留原文可核验信息。")
        return when (templateName) {
            "发票信息提取" -> TextInExtractionSpec(
                prompt = "请提取发票关键字段、交易双方、金额税额和明细项目。未找到字段写未找到，不要猜测。",
                fields = fields(
                    "发票类型", "发票代码", "发票号码", "开票日期", "校验码", "机器编号",
                    "购买方名称", "购买方纳税人识别号", "销售方名称", "销售方纳税人识别号",
                    "合计金额", "合计税额", "价税合计", "备注"
                ),
                tableFields = listOf(table("发票明细", "名称", "规格型号", "单位", "数量", "单价", "金额", "税率", "税额"))
            )
            "会议纪要" -> TextInExtractionSpec(
                prompt = "请提取会议纪要中的主题、时间、参会方、关键决策、待办事项、风险与阻塞。",
                fields = fields("会议主题", "会议时间", "参会方", "会议背景", "关键决策", "风险与阻塞", "下一步跟进"),
                tableFields = listOf(table("待办事项", "事项", "负责人", "截止时间", "优先级", "状态", "来源"))
            )
            "合同要点" -> TextInExtractionSpec(
                prompt = "请提取合同审阅要点。缺失字段写未找到，不要补全。",
                fields = fields("合同名称", "合同编号", "甲方", "乙方", "合同金额", "合同期限", "生效条件", "付款节点", "交付物", "违约责任", "争议解决", "风险点"),
                tableFields = listOf(table("核心条款", "条款", "内容摘要", "权利义务", "来源"))
            )
            "论文笔记" -> TextInExtractionSpec(
                prompt = "请提取论文或研究文档中的研究问题、方法、数据、贡献、结论、可引用句、局限性。",
                fields = fields("研究问题", "方法与数据", "核心贡献", "关键结论", "可引用句", "局限性", "后续研究")
            )
            "表格字段抽取" -> TextInExtractionSpec(
                prompt = "请从文档表格中提取字段字典、关键数值、异常与缺失信息。",
                fields = fields("字段字典", "关键数值", "异常值", "缺失值", "单位", "日期", "金额", "比例"),
                tableFields = listOf(table("可导出表格", "字段名", "类型", "示例值", "含义", "是否需要校验"))
            )
            "自定义提取" -> TextInExtractionSpec(
                prompt = """
                    请严格按照用户要求提取字段，只返回用户要求的信息。
                    不要返回 OCR 坐标、版面块、全文、无关字段或模型解释。
                    未找到的字段写未找到，不要猜测。
                    用户要求：${customInstruction.ifBlank { "提取文档中最重要的结构化信息" }}
                """.trimIndent(),
                fields = customExtractionFields(customInstruction)
            )
            else -> TextInExtractionSpec(
                prompt = "请提取文档中的结构化关键信息，未找到字段写未找到。",
                fields = fields("主题", "关键信息", "金额", "日期", "主体", "风险点", "待办")
            )
        }
    }

    private fun customExtractionFields(customInstruction: String): List<TextInExtractionField> {
        val cleaned = customInstruction
            .replace("请", "")
            .replace("帮我", "")
            .replace("提取", "")
            .replace("抽取", "")
            .replace("字段", "")
            .replace("信息", "")
        val candidates = cleaned
            .split("、", "，", ",", "；", ";", "\n", "和", "及", "以及")
            .map { it.trim().trim(':', '：', '.', '。', '-', ' ') }
            .filter { it.length in 2..24 }
            .filterNot { it.contains("不要") || it.contains("只") && it.length > 12 }
            .distinct()
            .take(12)
        val names = candidates.ifEmpty { listOf("提取结果", "匹配依据", "未找到或待确认") }
        return names.map { name ->
            TextInExtractionField(
                name = name,
                description = "只提取“$name”对应的内容。未找到则返回未找到，不要输出无关正文、OCR 坐标或版面结构。"
            )
        }
    }

    private suspend fun parseTemplateSourceWithTextIn(documentId: Long, fileName: String, fileBytes: ByteArray?): String {
        val appId = apiKeyStore.getTextInAppId()
        val secret = apiKeyStore.getTextInSecret()
        if (appId.isBlank() || secret.isBlank()) error("缺少 TextIn app-id 或 secret，未发起 TextIn 调用。")
        if (fileBytes == null) error("无法读取文档文件，未发起 TextIn 调用。")

        val body = fileBytes.toRequestBody("application/octet-stream".toMediaTypeOrNull())
        val part = MultipartBody.Part.createFormData("file", fileName, body)
        Log.i(TAG, "TextIn template parse start: documentId=$documentId file=$fileName bytes=${fileBytes.size}")
        val response = withTimeout(TEXTIN_TIMEOUT_MS) {
            parseTextInResponse(textInApi.parseSync(appId = appId, secretCode = secret, file = part))
        }

        val current = documentDao.getDocument(documentId)
        val parsedMarkdown = response.markdown
        if (parsedMarkdown.isNotBlank()) {
            val citations = citationsFromTextIn(fileName, parsedMarkdown, response.pages)
            documentDao.updateParsedContent(
                documentId,
                parsedMarkdown,
                documentStructureJson(fileName, parsedMarkdown, citations.size, "TextIn xParse", citations),
                citations.size,
                gson.toJson(citations),
                100
            )
            documentDao.updateStatus(documentId, "已通过 TextIn 重新解析")
        } else if (current != null) {
            documentDao.updateParsedContent(documentId, current.markdown, current.parseJson)
        }
        return parsedMarkdown
    }

    private fun localTemplateWithTextInError(error: Throwable?, fallback: String): String {
        return if (error == null) {
            fallback
        } else {
            "## TextIn 解析未完成\n- 失败原因：${error.readableMessage()}\n- 已继续使用当前本地可用内容生成结果。\n\n$fallback"
        }
    }

    private suspend fun completeWithQwenOrLocal(
        system: String,
        user: String,
        fallback: String,
        useCloud: Boolean,
        cloudModel: String,
        localConfig: LocalGenerationConfig
    ): String {
        if (!useCloud) {
            return runCatching {
                withTimeout(AI_TIMEOUT_MS) {
                    localModelEngine.complete("$system\n\n$user", localConfig)
                }
            }.getOrNull()
                ?: "## MNN 未生成\n- 当前本地状态：${localRuntimeStatus()}\n- 本次没有返回固定模板结果，请下载/选择可用 MNN 模型后重试。"
        }
        val apiKey = apiKeyStore.getQwenApiKey()
        if (apiKey.isBlank()) {
            return runCatching {
                withTimeout(AI_TIMEOUT_MS) {
                    localModelEngine.complete("$system\n\n$user", localConfig)
                }
            }.getOrNull()
                ?: "## 未配置 Qwen API，MNN 未生成\n- 当前本地状态：${localRuntimeStatus()}\n- 本次没有返回固定模板结果。"
        }
        val request = QwenChatRequest(
            model = cloudModel,
            messages = listOf(
                QwenMessage(role = "system", content = system),
                QwenMessage(role = "user", content = qwenUserContent(user, cloudModel, localConfig))
            ),
            temperature = 0.2
        )
        var qwenFailure: Throwable? = null
        return runCatching {
            withTimeout(AI_TIMEOUT_MS) {
                qwenContent(qwenApi.chat("Bearer $apiKey", request))
            }
        }.onFailure {
            qwenFailure = it
        }.getOrDefault("").ifBlank {
            runCatching {
                withTimeout(AI_TIMEOUT_MS) {
                    localModelEngine.complete("$system\n\n$user", localConfig)
                }
            }.getOrNull()
                ?: qwenFailure?.let { "## 云端 Qwen 调用失败，且 MNN 未生成\n- 失败原因：${it.readableMessage()}\n- 当前本地状态：${localRuntimeStatus()}\n- 本次没有返回固定模板结果。" }
                ?: "## AI 未生成结果\n- 当前本地状态：${localRuntimeStatus()}"
        }
    }

    private fun aiUnavailableMessage(title: String, reason: String, context: String): String {
        val snippets = context.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(8)
            .joinToString("\n") { "- ${it.take(120)}" }
        return """
            ## $title
            - $reason
            - 本次没有返回固定模板回答，请先确认云端 Qwen 或本地 MNN 模型可用。

            ## 当前文档片段
            ${snippets.ifBlank { "- 暂无可用文档内容。" }}
        """.trimIndent()
    }

    private fun qwenContent(response: Response<QwenChatResponse>): String {
        if (!response.isSuccessful) {
            val error = response.errorBody()?.string().orEmpty().take(600)
            error("Qwen HTTP ${response.code()}${error.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty()}")
        }
        val body = response.body() ?: error("Qwen 返回空响应")
        return body.choices.firstOrNull()?.message?.content.qwenText()
    }

    private fun Any?.qwenText(): String {
        return when (this) {
            null -> ""
            is String -> this
            is List<*> -> this.joinToString("") { item ->
                val map = item as? Map<*, *> ?: return@joinToString ""
                (map["text"] ?: map["content"]).qwenText()
            }
            is Map<*, *> -> (this["text"] ?: this["content"]).qwenText()
            else -> toString()
        }
    }

    private fun qwenUserContent(prompt: String, model: String, localConfig: LocalGenerationConfig): Any {
        if (!model.contains("vl", ignoreCase = true) || localConfig.imagePaths.isEmpty()) {
            return prompt
        }
        val images = localConfig.imagePaths.mapNotNull { path ->
            imageDataUrl(path)?.let { dataUrl ->
                mapOf("type" to "image_url", "image_url" to mapOf("url" to dataUrl))
            }
        }
        if (images.isEmpty()) return prompt
        return listOf(mapOf("type" to "text", "text" to prompt)) + images
    }

    private fun imageDataUrl(path: String): String? {
        val file = File(path)
        if (!file.isFile) return null
        val mime = when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            else -> "image/png"
        }
        val encoded = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        return "data:$mime;base64,$encoded"
    }

    private fun parseTextInResponse(
        response: Response<TextInParseResponse>,
        requireMarkdown: Boolean = true
    ): TextInParsedResult {
        if (!response.isSuccessful) {
            val error = response.errorBody()?.string().orEmpty().take(600)
            error("TextIn HTTP ${response.code()}${error.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty()}")
        }
        val body = response.body() ?: error("TextIn 返回空响应")
        val payload = body.result ?: body.data
        val markdown = payload.markdownContent()
        val okCode = body.code == null || body.code == 0 || body.code == 200
        if (!okCode) {
            error("TextIn 服务返回 ${body.code}：${body.message ?: "未知错误"}")
        }
        if (requireMarkdown && markdown.isBlank() && payload == null) {
            error("TextIn 未返回解析结果：${body.message ?: "响应中缺少 result/data"}")
        }
        return TextInParsedResult(
            markdown = markdown,
            pages = payload?.pages.orEmpty(),
            pageCount = payload?.pageCount ?: payload?.pages?.size ?: 0
        )
    }

    private fun TextInParseResult?.markdownContent(): String {
        return this?.markdown?.takeIf { it.isNotBlank() }
            ?: this?.md?.takeIf { it.isNotBlank() }
            ?: this?.text?.takeIf { it.isNotBlank() }
            ?: this?.content?.takeIf { it.isNotBlank() }
            ?: this?.pages.pageMarkdownContent()
            ?: ""
    }

    private fun List<Map<String, Any>>?.pageMarkdownContent(): String? {
        if (this.isNullOrEmpty()) return null
        val pages = mapIndexedNotNull { index, page ->
            val text = page.textInPageText()
            if (text.isBlank()) {
                null
            } else {
                val pageNo = page.numberValue("page")
                    ?: page.numberValue("page_id")?.plus(1)
                    ?: page.numberValue("pageIndex")?.plus(1)
                    ?: index + 1
                "## 第 ${pageNo} 页\n\n$text"
            }
        }
        return pages.joinToString("\n\n").takeIf { it.isNotBlank() }
    }

    private fun Map<String, Any>.textInPageText(): String {
        val directKeys = listOf("markdown", "md", "text", "content", "page_content", "pageContent")
        directKeys.firstNotNullOfOrNull { key ->
            this[key]?.textInTextValue()
        }?.let { return it }
        val nestedKeys = listOf("result", "data", "blocks", "lines", "paragraphs", "tables")
        return nestedKeys.mapNotNull { key ->
            this[key]?.textInTextValue()
        }.joinToString("\n").trim()
    }

    private fun Any?.textInTextValue(): String? {
        return when (this) {
            null -> null
            is String -> takeIf { it.isNotBlank() }
            is Map<*, *> -> {
                val directKeys = listOf("markdown", "md", "text", "content", "value")
                directKeys.firstNotNullOfOrNull { key ->
                    this[key]?.textInTextValue()
                } ?: values.mapNotNull { it.textInTextValue() }.joinToString("\n").takeIf { it.isNotBlank() }
            }
            is List<*> -> mapNotNull { it.textInTextValue() }.joinToString("\n").takeIf { it.isNotBlank() }
            else -> toString().takeIf { it.isNotBlank() }
        }
    }

    private data class TextInParsedResult(
        val markdown: String,
        val pages: List<Map<String, Any>>,
        val pageCount: Int
    )

    private fun localInsight(mode: String, document: DocumentEntity): String {
        val text = document.markdown.ifBlank { localEmptyDocumentMessage(document.name) }
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val bullets = lines.take(5).joinToString("\n") { "- ${it.take(80)}" }
        return when (mode) {
            "摘要" -> "## 摘要\n$bullets"
            "提纲" -> "## 本地提纲\n1. 文档主题：${document.name}\n2. 主要内容：\n$bullets\n3. 阅读建议：先查看标题、表格和风险相关段落。\n\n## 后续建议\n- 配置 Qwen API 后可生成更完整层级结构。"
            "重点句" -> "## 重点句\n$bullets"
            "待办/风险点" -> "## 本地待办/风险点\n- 复核文档解析是否完整\n- 检查金额、日期、责任条款等关键字段\n- 标记负责人、截止时间和优先级\n- 配置云端增强以获得更精确抽取\n\n## 建议动作\n- 先处理高优先级风险，再补齐缺失字段。"
            else -> "已读取 ${document.name}，可继续提问。"
        }
    }

    private fun localTemplateResult(templateName: String, document: DocumentEntity): String {
        val snippets = document.markdown.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("| ---") }
            .take(8)
            .joinToString("\n") { "- ${it.take(120)}" }
        return """
            ## $templateName 未完成 AI 抽取
            - TextIn 解析内容已保存到文档，但当前没有可用的云端 Qwen 或 MNN 生成结果。
            - 为避免返回固定模板误导用户，本次不伪造抽取结论。

            ## 可复核片段
            ${snippets.ifBlank { "- 当前文档暂无可用正文片段。" }}

            ## 下一步
            - 开启云端增强并确认 Qwen API 测试通过，或在设置中下载并选择可用 MNN 模型后重试。
        """.trimIndent()
    }

    private fun localCustomTemplateResult(document: DocumentEntity, customInstruction: String, context: String): String {
        val snippets = context.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("| ---") }
            .take(10)
            .joinToString("\n") { "- $it" }
        return """
            ## 自定义提取结果
            - 文档：${document.name}
            - 提取要求：${customInstruction.ifBlank { "未填写具体要求，已返回文档可用结构化片段。" }}
            - 处理方式：优先使用 TextIn xParse 解析后的内容；无云端模型时用本地规则整理。

            ## 可用片段
            $snippets
        """.trimIndent()
    }

    private fun localFallbackMarkdown(fileName: String, bytes: ByteArray, existingMarkdown: String = ""): String {
        if (existingMarkdown.isNotBlank() && !looksLikeBinaryText(existingMarkdown)) return existingMarkdown
        val known = REAL_CASES.firstOrNull { it.document.name == fileName }?.markdown
        if (!known.isNullOrBlank()) return known
        val readable = runCatching {
            bytes.decodeToString().take(4000)
        }.getOrDefault("")
        return if (readable.any { it.isLetterOrDigit() || it in '\u4e00'..'\u9fff' } && !looksLikeBinaryText(readable)) {
            "# $fileName\n\n$readable"
        } else {
            "# $fileName\n\n已导入 ${bytes.size / 1024} KB 文件。"
        }
    }

    private fun citationsFromTextIn(
        fileName: String,
        markdown: String,
        pages: List<Map<String, Any>>
    ): List<PageCitation> {
        if (pages.isEmpty()) return fallbackCitations(fileName, markdown)
        return pages.mapIndexed { index, page ->
            val pageNumber = page.numberValue("page")
                ?: page.numberValue("page_num")
                ?: page.numberValue("pageIndex")?.plus(1)
                ?: index + 1
            val title = page.stringValue("title")
                ?: page.stringValue("heading")
                ?: page.stringValue("section")
                ?: "第 ${pageNumber} 页"
            val snippet = page.stringValue("markdown")
                ?: page.stringValue("text")
                ?: page.stringValue("content")
                ?: markdown.lines().drop(index * 8).take(8).joinToString("\n")
            PageCitation(
                id = "${fileName.hashCode()}-$pageNumber-$index",
                page = pageNumber,
                title = title.take(80),
                snippet = snippet.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty().take(220),
                source = fileName
            )
        }.filter { it.snippet.isNotBlank() || it.title.isNotBlank() }
    }

    private fun fallbackCitations(fileName: String, markdown: String): List<PageCitation> {
        val chunks = markdown.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .chunked(10)
        return chunks.take(30).mapIndexed { index, lines ->
            PageCitation(
                id = "${fileName.hashCode()}-fallback-${index + 1}",
                page = index + 1,
                title = lines.firstOrNull { it.startsWith("#") }?.trimStart('#', ' ') ?: "第 ${index + 1} 段",
                snippet = lines.firstOrNull { !it.startsWith("#") }.orEmpty().take(220),
                source = fileName
            )
        }.ifEmpty {
            listOf(PageCitation(id = "${fileName.hashCode()}-empty-1", page = 1, title = fileName, snippet = "暂无可引用正文。", source = fileName))
        }
    }

    private fun Map<String, Any>.stringValue(key: String): String? = this[key]?.toString()?.takeIf { it.isNotBlank() }

    private fun Map<String, Any>.numberValue(key: String): Int? {
        return when (val value = this[key]) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }

    private fun documentStructureJson(
        fileName: String,
        markdown: String,
        pageCount: Int,
        parser: String,
        citations: List<PageCitation> = emptyList()
    ): String {
        val tableCount = markdown.lines().count { it.trim().startsWith("|") && it.trim().endsWith("|") }
        val headingCount = markdown.lines().count { it.trim().startsWith("#") }
        val textLength = markdown.length
        val preview = markdown.lines()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("|") }
            .orEmpty()
            .take(80)
            .jsonEscape()
        return """
            {
              "documentName": "${fileName.jsonEscape()}",
              "parser": "$parser",
              "status": "parsed",
              "structure": {
                "pages": $pageCount,
                "headings": $headingCount,
                "tableRows": $tableCount,
                "markdownLength": $textLength
              },
              "citations": ${gson.toJson(citations)},
              "preview": "$preview"
            }
        """.trimIndent()
    }

    private fun looksLikeBinaryText(text: String): Boolean {
        if (text.contains("\u0000")) return true
        val sample = text.take(600)
        if (sample.contains("PK\u0003\u0004") || sample.startsWith("%PDF")) return true
        val suspicious = sample.count { it.code < 9 || (it.code in 14..31) || it == '�' }
        return suspicious > sample.length / 20
    }

    private fun String.jsonEscape(): String = replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "")

    private fun localEmptyDocumentMessage(name: String): String = "文档 $name 暂无解析内容。"

    private fun Throwable.readableMessage(): String = when (this) {
        is IOException -> "网络连接失败或超时"
        else -> message ?: this::class.java.simpleName
    }

    private data class RealCase(
        val document: DocumentEntity,
        val markdown: String,
        val json: String,
        val extractions: List<Pair<String, String>>
    )

    companion object {
        private const val TAG = "DocPilot"
        private const val TEXTIN_TIMEOUT_MS = 120_000L
        private const val AI_TIMEOUT_MS = 180_000L
        private const val LOCAL_AI_TIMEOUT_MS = 45_000L
        private const val LOCAL_CONTEXT_CHAR_LIMIT = 6_000
        private const val LOCAL_EXTRACTION_CONTEXT_LIMIT = 4_000
        private const val CLOUD_CONTEXT_CHAR_LIMIT = 30_000
        private const val MAX_TEXTIN_DISPLAY_ROWS = 120
        private const val MAX_TEXTIN_DISPLAY_TABLES = 4
        private const val MAX_TEXTIN_TABLE_ROWS = 40
        private const val MAX_TEXTIN_TABLE_COLUMNS = 10
        private const val MAX_TEXTIN_CELL_CHARS = 220
        private const val contentBoundary = "-----DOC_CONTENT-----"
        private val REAL_CASES = listOf(
            RealCase(
                document = DocumentEntity(
                    name = "2024年IT行业研究报告.pdf",
                    type = "PDF",
                    sizeLabel = "340 KB",
                    status = "已解析",
                    updatedAt = "2026/05/24",
                    sourceUri = "asset://sample_docs/2024_it_industry_report.pdf"
                ),
                markdown = """
                    # 2024年IT行业研究报告

                    ## 文档定位
                    这是一份 IT 行业研究报告案例，适合用于行业报告摘要、趋势问答、重点句抽取和投资/经营洞察提取。

                    ## 处理后的核心主题
                    - 企业数字化继续从“系统建设”转向“智能化运营”和“业务流程自动化”。
                    - 云计算、AI 工具链、数据安全、行业软件是 IT 投入的主要方向。
                    - 大模型能力开始进入知识管理、客服、研发、办公和数据分析场景。
                    - 企业更关注 ROI、降本增效、合规安全和可落地的业务闭环。
                    - 手机端文档研究工具适合承接临时阅读、报告问答和结构化抽取场景。

                    ## 建议关注指标
                    | 指标 | 观察重点 |
                    | --- | --- |
                    | 云服务投入 | IaaS/PaaS/SaaS 预算变化 |
                    | AI 应用落地 | 办公、研发、客服、营销的效率提升 |
                    | 安全合规 | 数据跨境、隐私保护、权限审计 |
                    | 行业软件 | 金融、制造、政企、教育场景 |

                    ## DocPilot 处理建议
                    - 可生成行业趋势摘要。
                    - 可提取重点句和投资/经营机会。
                    - 可基于章节进行问答。
                    - 可整理为“机会、风险、行动建议”三段式报告。
                """.trimIndent(),
                json = """{"source":"asset://sample_docs/2024_it_industry_report.pdf","type":"PDF","topics":["云计算","AI应用","数字化转型","数据安全","行业软件"],"status":"preprocessed"}""",
                extractions = listOf(
                    "论文笔记" to "## 行业研究笔记\n- 研究对象：2024 年 IT 行业发展趋势\n- 重点方向：云计算、AI 应用、数据安全、行业软件\n- 核心结论：企业 IT 投入更强调可量化 ROI 和业务闭环\n- 风险：预算收缩、合规压力、技术落地周期不确定",
                    "表格字段抽取" to "## 指标字段\n- 行业方向：云服务 / AI 应用 / 安全合规 / 行业软件\n- 观察维度：投入变化、落地场景、风险约束、增长机会\n- 输出建议：按机会优先级建立跟踪表"
                )
            ),
            RealCase(
                document = DocumentEntity(
                    name = "DocPilot_Qwen_产品需求规格说明书.docx",
                    type = "DOCX",
                    sizeLabel = "1.6 MB",
                    status = "已解析",
                    updatedAt = "2026/05/24",
                    sourceUri = "asset://sample_docs/docpilot_qwen_prd.docx"
                ),
                markdown = """
                    # DocPilot Qwen 产品需求规格说明书（PRD）

                    ## 产品定位
                    一款“本地优先、云端增强”的智能文档研究 App。用户导入 PDF、图片、Office 文档后，系统解析为 Markdown/JSON，并由本地 Qwen 小模型完成摘要、提纲、问答、重点抽取；长文、多图、多模态或高难任务可选调用 Qwen 云端 API。

                    ## 核心原则
                    - 本地优先
                    - 隐私可控
                    - 来源可溯
                    - 云端增强可选

                    ## MVP 范围
                    - 文档导入：本地文件选择、系统分享导入、拍照导入。
                    - 文档解析：通过 TextIn xParse 输出 Markdown/JSON，保存标题、段落、表格、图片和页码结构。
                    - 文档阅读：原文预览与 Markdown/结构化解析视图。
                    - AI 能力：摘要、提纲、问答、重点句、待办/风险点抽取。
                    - 抽取模板：会议纪要、合同要点、论文笔记、表格字段抽取。
                    - 设置：本地模型、云端 API Key、隐私模式、性能模式、TextIn xParse 配置。

                    ## 用户场景
                    | 用户 | 典型文档 | 成功结果 |
                    | --- | --- | --- |
                    | 学生/考研用户 | PDF、PPT、图片笔记 | 章节摘要、重点句、页码 |
                    | 职场办公用户 | DOCX、PPTX、XLSX | 会议纪要、行动项、风险点 |
                    | 法律/商务用户 | 合同、扫描件 | 合同要点、风险项、对应页码 |
                    | 研究/分析用户 | 行业报告、财报、论文 | 洞察与来源索引 |

                    ## 验收目标
                    - 3 步内完成“导入文档 -> 摘要 -> 提问”。
                    - 短文档无网状态下可完成基础摘要和问答。
                    - AI 回答尽量带来源页码或片段。
                    - 云端上传前有明确确认与范围说明。
                """.trimIndent(),
                json = """{"source":"asset://sample_docs/docpilot_qwen_prd.docx","type":"DOCX","product":"DocPilot Qwen","principles":["本地优先","隐私可控","来源可溯","云端增强可选"],"mvp":["导入","解析","阅读","AI问答","模板抽取","设置"]}""",
                extractions = listOf(
                    "会议纪要" to "## PRD 评审纪要\n- 目标：完成 DocPilot Qwen MVP 闭环\n- 决策：采用 Kotlin + Compose + Room + Retrofit + Keystore\n- 待办：完善 TextIn 解析、Qwen 问答、模板抽取、模型下载状态",
                    "论文笔记" to "## 产品笔记\n- 问题：手机端文档阅读与 AI 问答割裂\n- 方法：TextIn 结构化解析 + Qwen 文档理解\n- 价值：来源可溯、隐私可控、端云协同"
                )
            ),
            RealCase(
                document = DocumentEntity(
                    name = "财务数据汇总表.xlsx",
                    type = "XLSX",
                    sizeLabel = "20.8 KB",
                    status = "已解析",
                    updatedAt = "2026/05/24",
                    sourceUri = "asset://sample_docs/finance_summary.xlsx"
                ),
                markdown = """
                    # 财务数据汇总表

                    ## 数据范围
                    示例范围为 2026 年 1–6 月，包含收入、支出、部门、项目/客户、分类、账户、金额和备注等字段。

                    ## 明细字段
                    | 字段 | 说明 |
                    | --- | --- |
                    | 日期/月度 | 交易发生日期与月份 |
                    | 部门 | 销售部、市场部、运营部、研发部、行政部、财务部 |
                    | 类型 | 收入 / 支出 |
                    | 分类 | 产品销售、服务收入、采购成本、人员工资、市场推广等 |
                    | 金额 | 每笔交易金额 |

                    ## 处理后的财务观察
                    - 收入主要来自产品销售、服务收入、会员订阅和其他收入。
                    - 支出主要集中在采购成本、人员工资、市场推广、软件订阅、税费等。
                    - 3–6 月收入金额明显高于 1–2 月，适合做月度趋势分析。
                    - 表格已具备按月份、部门、分类做汇总分析的结构。

                    ## 可抽取内容
                    - 月度收入/支出/净利润。
                    - 部门贡献与成本结构。
                    - 收入分类占比和支出分类占比。
                    - 异常支出、现金流风险、预算复盘建议。
                """.trimIndent(),
                json = """{"source":"asset://sample_docs/finance_summary.xlsx","type":"XLSX","sheets":["明细数据","财务汇总","分类汇总","下拉选项","使用说明"],"fields":["日期","月份","部门","项目/客户","类型","分类","账户","金额","备注"]}""",
                extractions = listOf(
                    "表格字段抽取" to "## 财务字段抽取\n| 字段 | 示例 |\n| --- | --- |\n| 月份 | 2026-01 至 2026-06 |\n| 类型 | 收入 / 支出 |\n| 部门 | 销售部、市场部、运营部、研发部、行政部、财务部 |\n| 分类 | 产品销售、采购成本、人员工资、市场推广等 |\n| 金额 | 交易金额 |\n\n建议：按月度、部门和分类建立透视分析。",
                    "合同要点" to "## 财务风控要点\n- 检查大额采购成本与市场推广费用\n- 对收入回款账户进行核对\n- 将税费、工资、房租等固定支出单独归类"
                )
            ),
            RealCase(
                document = DocumentEntity(
                    name = "年度经营计划汇报PPT.pptx",
                    type = "PPTX",
                    sizeLabel = "432.8 KB",
                    status = "已解析",
                    updatedAt = "2026/05/24",
                    sourceUri = "asset://sample_docs/annual_plan.pptx"
                ),
                markdown = """
                    # 年度经营计划汇报

                    ## 汇报主线
                    从经营复盘、环境研判、目标设定、策略打法到保障机制，形成年度经营闭环。

                    ## 关键数据
                    - 上年度营业收入：12.8 亿，同比 +18%，完成预算 104%。
                    - 毛利率：41.2%，同比 +2.1pp。
                    - 净利润：1.48 亿，同比 +26%。
                    - NPS：58，同比 +6。
                    - 2026 年目标：收入 15.5 亿，净利 1.95 亿，毛利率 43%，回款率 96%，NPS ≥ 62。

                    ## 策略重点
                    - 业务增长：行业聚焦、客户分层、复购运营。
                    - 产品服务：核心产品 2.0、行业解决方案、交付体系、客户成功。
                    - 市场渠道：品牌内容、数字投放、伙伴生态、客户运营。
                    - 组织保障：经营驾驶舱、项目管理、绩效激励、月度经营复盘。

                    ## 风险与资源
                    | 风险 | 应对措施 |
                    | --- | --- |
                    | 需求波动 | 分层预测与回款预警 |
                    | 竞争降价 | 强化方案价值与合同边界 |
                    | 交付瓶颈 | SOP、项目看板与外包资源池 |
                    | 人才缺口 | 提前锁定岗位画像与激励方案 |
                """.trimIndent(),
                json = """{"source":"asset://sample_docs/annual_plan.pptx","type":"PPTX","slides":12,"targets":{"revenue":"15.5亿","profit":"1.95亿","grossMargin":"43%","cashCollection":"96%","nps":"≥62"}}""",
                extractions = listOf(
                    "会议纪要" to "## 经营计划评审纪要\n- 主线：高质量增长，利润率、现金流与客户生命周期价值并重\n- 指标：收入 15.5 亿，净利 1.95 亿，回款率 96%\n- 待办：确认资源预算、重点项目责任人、月度经营看板",
                    "表格字段抽取" to "## 经营指标\n| 指标 | 目标 |\n| --- | --- |\n| 收入 | 15.5亿 |\n| 净利润 | 1.95亿 |\n| 毛利率 | 43% |\n| 回款率 | 96% |\n| NPS | ≥62 |"
                )
            ),
            RealCase(
                document = DocumentEntity(
                    name = "会议白板.png",
                    type = "图片",
                    sizeLabel = "2.0 MB",
                    status = "已解析",
                    updatedAt = "2026/05/24",
                    sourceUri = "asset://sample_docs/meeting_whiteboard.png"
                ),
                markdown = """
                    # 会议白板：项目周会

                    ## 基本信息
                    - 主题：项目周会
                    - 日期：2024.05.17（周五）

                    ## 会议议程
                    1. 需求确认
                    2. 开发进度
                    3. 风险问题
                    4. 下周计划

                    ## 本周重点
                    - 完成用户模块联调
                    - 支付接口对接
                    - 性能压测未完成

                    ## 开发进度
                    | 阶段 | 进度 |
                    | --- | --- |
                    | 需求分析 | 100% |
                    | 设计 | 100% |
                    | 开发 | 80% |
                    | 测试 | 40% |
                    | 上线 | 未开始 |

                    ## 风险/问题
                    1. 支付接口超时问题（待跟进，高优先级）
                    2. 部分机型兼容性问题（Android 13）
                    3. 测试环境数据不一致

                    ## KPI 指标
                    - 需求完成率：85%
                    - 代码覆盖率：76%
                    - 缺陷关闭率：90%
                    - 构建成功率：100%

                    ## 待办事项
                    | 事项 | 负责人 | 截止 | 优先级 | 状态 |
                    | --- | --- | --- | --- | --- |
                    | 接口联调 | 张三 | 5.20 前 | P0 | 已完成 60% |
                    | 测试回归 | 李四 | 5.22 前 | P1 | 进行中 |
                    | 上线准备 | 王敏 | 5.24 前 | P0 | 未开始 |

                    ## 下周计划
                    - 完成开发联调
                    - 测试回归与缺陷修复
                    - 上线准备（灰度方案）
                """.trimIndent(),
                json = """{"source":"asset://sample_docs/meeting_whiteboard.png","type":"IMAGE","meeting":"项目周会","date":"2024-05-17","kpi":{"需求完成率":"85%","代码覆盖率":"76%","缺陷关闭率":"90%","构建成功率":"100%"},"risks":["支付接口超时","Android 13兼容","测试环境数据不一致"]}""",
                extractions = listOf(
                    "会议纪要" to "## 项目周会纪要\n- 议程：需求确认、开发进度、风险问题、下周计划\n- 本周重点：用户模块联调完成、支付接口对接、性能压测未完成\n- 风险：支付接口超时、Android 13 兼容、测试环境数据不一致\n- 下周计划：开发联调、测试回归、灰度上线准备",
                    "表格字段抽取" to "## 白板字段抽取\n| 字段 | 值 |\n| --- | --- |\n| 需求完成率 | 85% |\n| 代码覆盖率 | 76% |\n| 缺陷关闭率 | 90% |\n| 构建成功率 | 100% |\n| 高优先级风险 | 支付接口超时 |"
                )
            )
        )

        const val SAMPLE_MARKDOWN = """
## 2.2 行业增长驱动因素

行业增长主要由以下四类因素驱动：技术进步、政策支持、需求扩大、资本投入。

| 年份 | 行业规模（亿元） | 增速 |
| --- | ---: | ---: |
| 2019 | 3210 | 12.3% |
| 2020 | 3650 | 13.7% |
| 2021 | 4210 | 15.3% |
| 2022 | 4900 | 16.4% |
| 2023 | 5680 | 15.9% |
| 2024E | 6480 | 14.1% |

来源：P12
"""
        const val SAMPLE_JSON = """{"pages":[{"page":12,"title":"行业增长驱动因素"}]}"""
    }
}
    data class ParseOutcome(
        val success: Boolean,
        val markdown: String,
        val source: String,
        val message: String
    )
