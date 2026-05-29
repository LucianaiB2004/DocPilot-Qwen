package com.docpilot.qwen.data

import android.net.Uri
import com.docpilot.qwen.data.local.ChatMessageEntity
import com.docpilot.qwen.data.local.DocumentDao
import com.docpilot.qwen.data.local.DocumentEntity
import com.docpilot.qwen.data.local.ExtractionEntity
import com.docpilot.qwen.data.local.LocalGenerationConfig
import com.docpilot.qwen.data.local.LocalModelEngine
import com.docpilot.qwen.data.local.PageCitation
import com.docpilot.qwen.data.network.QwenApi
import com.docpilot.qwen.data.network.QwenChatRequest
import com.docpilot.qwen.data.network.QwenMessage
import com.docpilot.qwen.data.network.QwenStreamClient
import com.docpilot.qwen.data.network.TextInApi
import com.docpilot.qwen.security.ApiKeyStore
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
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
            val answer = qwenApi.chat("Bearer $apiKey", request).choices.firstOrNull()?.message?.content.orEmpty()
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
            val response = textInApi.parseSync(appId = appId, secretCode = secret, file = part)
            if (response.result != null || response.code == 200) {
                "测试通过：TextIn 可连接"
            } else {
                "测试失败：${response.message ?: "服务返回 ${response.code}"}"
            }
        }.getOrElse { "测试失败：${it.readableMessage()}" }
    }

    suspend fun registerImportedDocument(uri: Uri, displayName: String, type: String, sizeLabel: String): Long {
        return documentDao.insertDocument(
            DocumentEntity(
                name = displayName,
                type = type,
                sizeLabel = sizeLabel,
                status = "待解析",
                updatedAt = "刚刚",
                sourceUri = uri.toString()
            )
        )
    }

    suspend fun parseWithTextIn(documentId: Long, fileName: String, bytes: ByteArray) {
        val appId = apiKeyStore.getTextInAppId()
        val secret = apiKeyStore.getTextInSecret()
        val currentDocument = documentDao.getDocument(documentId)
        val safeFallback = localFallbackMarkdown(fileName, bytes, currentDocument?.markdown.orEmpty())
        documentDao.updateParseProgress(documentId, 3, "准备解析")
        if (appId.isBlank() || secret.isBlank()) {
            val citations = fallbackCitations(fileName, safeFallback)
            documentDao.updateParsedContent(
                documentId,
                safeFallback,
                documentStructureJson(fileName, safeFallback, citations.size, "本地兜底", citations),
                citations.size,
                gson.toJson(citations),
                100
            )
            documentDao.updateStatus(documentId, "缺少 TextIn Key")
            return
        }

        documentDao.updateParseProgress(documentId, 8, "TextIn 分析中")
        runCatching {
            val body = bytes.toRequestBody("application/octet-stream".toMediaTypeOrNull())
            val part = MultipartBody.Part.createFormData("file", fileName, body)
            textInApi.parseSync(appId = appId, secretCode = secret, file = part)
        }.onSuccess { response ->
            documentDao.updateParseProgress(documentId, 70, "整理页级结构")
            val markdown = response.result?.markdown.orEmpty().ifBlank {
                safeFallback
            }
            val citations = citationsFromTextIn(fileName, markdown, response.result?.pages.orEmpty())
            documentDao.updateParsedContent(
                documentId,
                markdown,
                documentStructureJson(fileName, markdown, citations.size, "TextIn xParse", citations),
                citations.size,
                gson.toJson(citations),
                100
            )
            documentDao.updateStatus(documentId, "已解析")
        }.onFailure { error ->
            val citations = fallbackCitations(fileName, safeFallback)
            documentDao.updateParsedContent(
                documentId,
                safeFallback,
                documentStructureJson(fileName, safeFallback, citations.size, "本地兜底：${error.readableMessage()}", citations),
                citations.size,
                gson.toJson(citations),
                100
            )
            documentDao.updateStatus(documentId, "解析失败")
        }
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
        if (!useCloud || apiKey.isBlank()) {
            val assistantId = documentDao.insertMessage(
                ChatMessageEntity(documentId = documentId, role = "assistant", content = "", source = "MNN", streaming = true)
            )
            val local = localModelEngine.answer(usableContext, question, localConfig) { partial ->
                documentDao.updateMessageContent(assistantId, partial, streaming = true)
            }
            val answer = local ?: localAnswer(question, usableContext)
            documentDao.updateMessageContent(assistantId, answer, streaming = false)
            return answer
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
                QwenMessage(role = "user", content = prompt)
            )
        )

        val assistantId = documentDao.insertMessage(
            ChatMessageEntity(documentId = documentId, role = "assistant", content = "", source = cloudModel, streaming = true)
        )
        val citations = documentDao.getDocument(documentId)?.citationsJson.orEmpty().ifBlank { "[]" }
        val answer = runCatching {
            val buffer = StringBuilder()
            withContext(Dispatchers.IO) {
                qwenStreamClient.streamChat("Bearer $apiKey", request) { delta ->
                    buffer.append(delta)
                    documentDao.updateMessageContent(assistantId, buffer.toString(), streaming = true, citationsJson = citations)
                }
            }
        }.getOrElse {
            runCatching {
                qwenApi.chat("Bearer $apiKey", request).choices.firstOrNull()?.message?.content.orEmpty()
            }.getOrDefault("")
        }.ifBlank { localAnswer(question, usableContext) }

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

    suspend fun generateDocumentInsight(
        documentId: Long,
        mode: String,
        useCloud: Boolean,
        cloudModel: String,
        localConfig: LocalGenerationConfig
    ): String {
        val document = documentDao.getDocument(documentId) ?: return "请先选择一个文档。"
        val context = document.markdown.ifBlank { localEmptyDocumentMessage(document.name) }
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
        val textInMarkdown = parseTemplateSourceWithTextIn(documentId, fileName.ifBlank { document.name }, fileBytes)
        val context = textInMarkdown.ifBlank { document.markdown.ifBlank { localEmptyDocumentMessage(document.name) } }
        val instruction = when (templateName) {
            "会议纪要" -> """
                请按“会议纪要”模板抽取。
                输出结构：
                ## 会议基本信息
                主题、时间、参会方/角色、会议背景；缺失则写“待确认”。
                ## 关键决策
                用清单列出已经形成决定的事项，并标注依据。
                ## 待办事项
                用表格输出：事项 | 负责人 | 截止时间 | 优先级 | 状态 | 来源。
                ## 风险与阻塞
                列出风险、影响、建议跟进动作。
                ## 下一次跟进
                给出 3 条以内后续会议或执行建议。
            """.trimIndent()
            "合同要点" -> """
                请按“合同审阅要点”模板抽取。
                输出结构：
                ## 基本信息
                合同名称、编号、甲乙方、金额、期限、生效条件；缺失则写“待确认”。
                ## 核心条款
                用表格输出：条款 | 内容摘要 | 权利义务 | 来源。
                ## 付款与交付
                输出付款节点、交付物、验收标准、违约处理。
                ## 风险点
                重点检查违约责任、知识产权、保密、数据安全、解除条件、争议解决。
                ## 行动建议
                给法务/业务/财务分别列出需要确认的问题。
            """.trimIndent()
            "论文笔记" -> """
                请按“论文/研究笔记”模板抽取。
                输出结构：
                ## 研究问题
                ## 方法与数据
                ## 核心贡献
                ## 关键结论
                ## 可引用句
                ## 局限性与后续研究
                要求：尽量保留术语、指标、实验对象和来源线索，不要把普通背景写成贡献。
            """.trimIndent()
            "表格字段抽取" -> """
                请按“表格字段抽取”模板处理。
                输出结构：
                ## 字段字典
                用表格输出：字段名 | 类型 | 示例值 | 含义 | 是否需要校验。
                ## 关键数值
                抽取金额、比例、日期、数量、状态等字段。
                ## 异常与缺失
                标出空值、口径不一致、异常值、单位不清。
                ## 可导出建议
                说明适合导出为 CSV/表格的列。
            """.trimIndent()
            "自定义提取" -> """
                请严格按用户自定义要求抽取信息。
                用户要求：${customInstruction.ifBlank { "用户未填写具体要求，请返回文档中最可结构化的信息。" }}
                输出结构：
                ## 提取结果
                用清单或表格呈现。
                ## 匹配依据
                说明结果来自哪些段落、字段或来源。
                ## 未找到/待确认
                对未能在文档中找到的项目明确标注“未找到”，不要猜测补全。
            """.trimIndent()
            else -> "请抽取文档中的结构化关键信息，输出结论、依据、风险和下一步建议。"
        }
        val result = completeWithQwenOrLocal(
            system = "你是 DocPilot Qwen 的中文文档结构化抽取助手。输出必须准确、可复核、适合手机阅读。不要编造缺失字段；缺失内容统一写“待确认”或“未找到”。",
            user = """
                文档名称：${document.name}

                $instruction

                通用要求：
                - 优先使用 Markdown 表格和短清单。
                - 尽量标注来源页码、章节、表格或字段。
                - 对用户可执行的下一步给出明确动作。
                - 不要输出与模板无关的泛泛说明。

                文档内容：
                $contentBoundary
                $context
                $contentBoundary
            """.trimIndent(),
            fallback = if (templateName == "自定义提取") {
                localCustomTemplateResult(document, customInstruction, context)
            } else {
                localTemplateResult(templateName, document)
            },
            useCloud = useCloud,
            cloudModel = cloudModel,
            localConfig = localConfig
        )
        insertExtractionIfNew(
            ExtractionEntity(
                documentId = documentId,
                templateName = templateName,
                content = result,
                source = when {
                    textInMarkdown.isNotBlank() && hasQwenApiKey() && useCloud -> "TextIn xParse + Qwen"
                    textInMarkdown.isNotBlank() -> "TextIn xParse"
                    hasQwenApiKey() && useCloud -> cloudModel
                    else -> "本地兜底"
                }
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

    private suspend fun parseTemplateSourceWithTextIn(documentId: Long, fileName: String, fileBytes: ByteArray?): String {
        val appId = apiKeyStore.getTextInAppId()
        val secret = apiKeyStore.getTextInSecret()
        if (appId.isBlank() || secret.isBlank() || fileBytes == null) return ""

        val response = runCatching {
            val body = fileBytes.toRequestBody("application/octet-stream".toMediaTypeOrNull())
            val part = MultipartBody.Part.createFormData("file", fileName, body)
            textInApi.parseSync(appId = appId, secretCode = secret, file = part)
        }.getOrNull() ?: return ""

        val current = documentDao.getDocument(documentId)
        val parsedMarkdown = response.result?.markdown.orEmpty()
        if (parsedMarkdown.isNotBlank()) {
            val citations = citationsFromTextIn(fileName, parsedMarkdown, response.result?.pages.orEmpty())
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

    private suspend fun completeWithQwenOrLocal(
        system: String,
        user: String,
        fallback: String,
        useCloud: Boolean,
        cloudModel: String,
        localConfig: LocalGenerationConfig
    ): String {
        if (!useCloud) return localModelEngine.complete("$system\n\n$user", localConfig) ?: fallback
        val apiKey = apiKeyStore.getQwenApiKey()
        if (apiKey.isBlank()) return localModelEngine.complete("$system\n\n$user", localConfig) ?: fallback
        val request = QwenChatRequest(
            model = cloudModel,
            messages = listOf(
                QwenMessage(role = "system", content = system),
                QwenMessage(role = "user", content = user)
            ),
            temperature = 0.2
        )
        return runCatching {
            qwenApi.chat("Bearer $apiKey", request).choices.firstOrNull()?.message?.content.orEmpty()
        }.getOrDefault("").ifBlank { localModelEngine.complete("$system\n\n$user", localConfig) ?: fallback }
    }

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
        val base = "文档：${document.name}"
        return when (templateName) {
            "会议纪要" -> "$base\n\n## 会议纪要\n- 主题：待从文档确认\n- 关键决策：请复核解析内容中的决议类语句\n- 待办：补充负责人、截止时间、验收标准\n\n## 复核建议\n- 优先确认决策是否已有责任人和时间点。"
            "合同要点" -> "$base\n\n## 合同要点\n- 合同主体：待复核\n- 金额/期限：请检查金额、日期、付款节点\n- 风险点：违约责任、知识产权、数据安全、保密条款\n\n## 复核建议\n- 对金额、期限、违约和争议解决条款做人工确认。"
            "论文笔记" -> "$base\n\n## 论文笔记\n- 研究问题：待提炼\n- 方法：请结合摘要/方法章节复核\n- 贡献与局限：建议配置 Qwen API 后自动生成\n\n## 复核建议\n- 区分作者结论、实验结果和背景介绍。"
            "表格字段抽取" -> "$base\n\n## 表格字段\n| 字段 | 状态 | 建议 |\n| --- | --- | --- |\n| 字段名 | 待识别 | 检查表头 |\n| 数值范围 | 待校验 | 核对单位和异常值 |\n| 缺失值 | 待检查 | 导出后复核 |\n\n## 复核建议\n- 导出前核对单位、日期格式和空值。"
            else -> "$base\n\n已生成本地抽取结果。"
        }
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
