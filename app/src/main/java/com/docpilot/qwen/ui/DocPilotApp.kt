package com.docpilot.qwen.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.docpilot.qwen.data.AppContainer
import com.docpilot.qwen.data.export.ExportFormat
import com.docpilot.qwen.data.local.ChatMessageEntity
import com.docpilot.qwen.data.local.DocumentEntity
import com.docpilot.qwen.data.local.ExtractionEntity
import com.docpilot.qwen.data.local.LocalModelSpec
import com.docpilot.qwen.ui.theme.BrandBlue
import com.docpilot.qwen.ui.theme.DangerRed
import com.docpilot.qwen.ui.theme.Ink
import com.docpilot.qwen.ui.theme.Muted
import com.docpilot.qwen.ui.theme.SoftBlue
import com.docpilot.qwen.ui.theme.SuccessGreen
import com.docpilot.qwen.ui.theme.WarningOrange
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private enum class Screen(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "首页", Icons.Default.Home),
    Reader("reader", "文档", Icons.Default.Article),
    Assistant("assistant", "AI 助手", Icons.Default.AutoAwesome),
    Templates("templates", "模板", Icons.Default.FileCopy),
    Settings("settings", "设置", Icons.Default.Settings)
}

@Composable
fun DocPilotApp(container: AppContainer) {
    val context = LocalContext.current
    val viewModel: DocPilotViewModel = viewModel(
        factory = DocPilotViewModel.Factory(
            application = context.applicationContext as Application,
            repository = container.repository,
            settingsStore = container.settingsStore,
            localModelManager = container.localModelManager
        )
    )
    val state by viewModel.uiState.collectAsState()
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination?.route ?: Screen.Home.route
    LaunchedEffect(state.autoClearClipboardAndCache) {
        if (state.autoClearClipboardAndCache) {
            File(context.cacheDir, "preview").deleteRecursively()
        }
    }
    val openScreen: (Screen) -> Unit = { screen ->
        navController.navigate(screen.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                Screen.values().forEach { screen ->
                    NavigationBarItem(
                        selected = current == screen.route,
                        onClick = {
                            if (screen == Screen.Assistant) viewModel.openAssistantConversation()
                            openScreen(screen)
                        },
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label, fontSize = 11.sp) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    state = state,
                    onSelect = viewModel::selectDocument,
                    onDelete = viewModel::deleteDocument,
                    onAnalyze = viewModel::analyzeDocument,
                    onImport = viewModel::importDocument,
                    onOpenReader = { openScreen(Screen.Reader) },
                    onOpenAssistant = {
                        viewModel.openAssistantConversation()
                        openScreen(Screen.Assistant)
                    },
                    onOpenTemplates = { openScreen(Screen.Templates) },
                    onOpenRecentQuestion = {
                        viewModel.openRecentQuestion(it)
                        openScreen(Screen.Assistant)
                    }
                )
            }
            composable(Screen.Reader.route) {
                ReaderScreen(
                    state = state,
                    onBackHome = { navController.popBackStack(Screen.Home.route, inclusive = false) },
                    onOpenAssistant = {
                        viewModel.openAssistantConversation()
                        openScreen(Screen.Assistant)
                    },
                    onOpenTemplates = { openScreen(Screen.Templates) },
                    autoClearClipboard = state.autoClearClipboardAndCache,
                    onRenameDocument = viewModel::renameSelectedDocument,
                    onUpdateOrganization = viewModel::updateSelectedDocumentOrganization,
                    onExport = viewModel::exportSelectedDocument
                )
            }
            composable(Screen.Assistant.route) {
                AssistantScreen(
                    state = state,
                    onBackHome = { navController.popBackStack(Screen.Home.route, inclusive = false) },
                    onSelectDocument = viewModel::selectDocument,
                    onCloudChange = viewModel::toggleCloud,
                    onAsk = viewModel::ask,
                    onGenerateInsight = viewModel::generateInsight,
                    onDeleteMessage = viewModel::deleteMessage
                )
            }
            composable(Screen.Templates.route) {
                TemplatesScreen(
                    state = state,
                    onSelectDocument = viewModel::selectDocument,
                    onExtractTemplate = viewModel::extractTemplate,
                    onSaveTemplateResult = viewModel::saveTemplateResult,
                    onDeleteTemplateHistory = viewModel::deleteTemplateHistory,
                    autoClearClipboard = state.autoClearClipboardAndCache,
                    onExport = viewModel::exportSelectedDocument
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    state = state,
                    onCloudChange = viewModel::toggleCloud,
                    onSaveKeys = viewModel::saveApiKeys,
                    onTestQwen = viewModel::testQwenApiKey,
                    onTestTextIn = viewModel::testTextInCredentials,
                    onSelectLocalModel = viewModel::selectLocalModel,
                    onDownloadLocalModel = viewModel::downloadLocalModel,
                    onPauseLocalModelDownload = viewModel::pauseLocalModelDownload,
                    onUninstallLocalModel = viewModel::uninstallLocalModel,
                    onDefaultLocalModeChange = viewModel::setDefaultLocalMode,
                    onAutoClearChange = viewModel::setAutoClearClipboardAndCache,
                    onPerformanceModeChange = viewModel::setPerformanceMode,
                    onParseThreadCountChange = viewModel::setParseThreadCount,
                    onAccelerationEngineChange = viewModel::setAccelerationEngine,
                    onCloudModelChange = viewModel::setCloudModel,
                    onDeleteKeys = viewModel::deleteApiKeys
                )
            }
        }
    }
}

private val assistantModes = listOf("问答", "摘要", "提纲", "重点句", "待办/风险点")
@Composable
private fun HomeScreen(
    state: DocPilotUiState,
    onSelect: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onAnalyze: (Long) -> Unit,
    onImport: (android.net.Uri, String, String) -> Unit,
    onOpenReader: () -> Unit,
    onOpenAssistant: () -> Unit,
    onOpenTemplates: () -> Unit,
    onOpenRecentQuestion: (ChatMessageEntity) -> Unit
) {
    var selectedFilter by rememberSaveable { mutableStateOf("全部") }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var sortMode by rememberSaveable { mutableStateOf("最近打开") }
    var showNotifications by rememberSaveable { mutableStateOf(false) }
    var showFilterTools by rememberSaveable { mutableStateOf(false) }
    val filters = listOf("全部", "PDF", "DOCX", "PPTX", "XLSX", "图片")
    val sortModes = listOf("最近打开", "文件大小", "名称")
    val visibleDocuments = remember(state.documents, selectedFilter, searchQuery, sortMode, state.recentOpenedIds) {
        val filtered = state.documents
            .filter { selectedFilter == "全部" || it.type.equals(selectedFilter, ignoreCase = true) || it.type == selectedFilter }
            .filter { it.name.contains(searchQuery, ignoreCase = true) || it.status.contains(searchQuery, ignoreCase = true) }
        when (sortMode) {
            "文件大小" -> filtered.sortedByDescending { parseSizeBytes(it.sizeLabel) }
            "名称" -> filtered.sortedBy { it.name }
            else -> filtered.sortedWith(
                compareBy<DocumentEntity> {
                    val index = state.recentOpenedIds.indexOf(it.id)
                    if (index < 0) Int.MAX_VALUE else index
                }.thenByDescending { it.id }
            )
        }
    }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
        } ?: "导入文档"
        val size = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst() && index >= 0) cursor.getLong(index) else 0L
        } ?: 0L
        onImport(uri, name, formatBytes(size))
    }

    ScreenFrame {
        Header(
            title = "DocPilot Qwen",
            subtitle = "随身文档研究员",
            trailing = Icons.Default.NotificationsNone,
            onTrailingClick = { showNotifications = !showNotifications }
        )
        if (showNotifications) {
            NotificationPanel(state)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(icon = Icons.Default.Security, text = "本地优先", color = SuccessGreen)
            StatusPill(icon = Icons.Default.Cloud, text = "云端增强可选", color = BrandBlue)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                SearchBox(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    onFilterClick = { showFilterTools = !showFilterTools }
                )
            }
            Button(
                onClick = { picker.launch(arrayOf("application/pdf", "image/*", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.height(48.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("导入")
            }
        }
        if (showFilterTools) {
            CardBlock {
                SectionHeader("筛选与排序", "$selectedFilter · $sortMode")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filters) { filter ->
                        SelectableChip(
                            text = filter,
                            selected = filter == selectedFilter,
                            onClick = { selectedFilter = filter }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("排序", color = Muted, fontSize = 12.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                        items(sortModes) { item ->
                            SelectableChip(text = item, selected = item == sortMode, onClick = { sortMode = item })
                        }
                    }
                }
            }
        }
        SectionHeader("文档列表", "${visibleDocuments.size} 个 · $sortMode")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
            items(items = visibleDocuments, key = { it.id }) { doc: DocumentEntity ->
                DocumentRow(
                    doc = doc,
                    selected = doc.id == state.selectedDocument?.id,
                    onRead = {
                        onSelect(doc.id)
                        onOpenReader()
                    },
                    onAnalyze = { onAnalyze(doc.id) },
                    onAsk = {
                        onSelect(doc.id)
                        onOpenAssistant()
                    },
                    onExtract = {
                        onSelect(doc.id)
                        onOpenTemplates()
                    },
                    onDelete = { onDelete(doc.id) }
                )
            }
            item {
                SectionHeader("最近提问", if (state.recentQuestions.isEmpty()) "" else "点击继续")
                if (state.recentQuestions.isEmpty()) {
                    EmptyHint("暂无提问")
                } else {
                    state.recentQuestions.take(4).forEach { message ->
                        RecentQuestion(
                            question = message.content,
                            source = "来源：${message.source}",
                            onClick = { onOpenRecentQuestion(message) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderScreen(
    state: DocPilotUiState,
    onBackHome: () -> Unit,
    onOpenAssistant: () -> Unit,
    onOpenTemplates: () -> Unit,
    autoClearClipboard: Boolean,
    onRenameDocument: (String) -> Unit,
    onUpdateOrganization: (String, String) -> Unit,
    onExport: (ExportFormat) -> Unit
) {
    val doc = state.selectedDocument
    var readerTab by rememberSaveable { mutableStateOf("原文预览") }
    var showReaderTools by rememberSaveable { mutableStateOf(false) }
    var editDocumentInfo by rememberSaveable(doc?.id) { mutableStateOf(false) }
    var editName by rememberSaveable(doc?.id) { mutableStateOf(doc?.name.orEmpty()) }
    var editTags by rememberSaveable(doc?.id) { mutableStateOf(doc?.tags.orEmpty()) }
    var editFolder by rememberSaveable(doc?.id) { mutableStateOf(doc?.folder ?: "默认") }
    val docMetaSummary = remember(doc?.folder, doc?.tags) { documentMetaSummary(doc) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    ScreenFrame {
        Header(
            title = doc?.name ?: "文档阅读",
            subtitle = "原文对照 + 结构化解析",
            trailing = Icons.Default.MoreVert,
            leading = Icons.Default.ArrowBack,
            onLeadingClick = onBackHome,
            onTrailingClick = { showReaderTools = !showReaderTools }
        )
        if (showReaderTools) {
            ReaderActionPanel(
                onShowPreview = { readerTab = "原文预览" },
                onShowMarkdown = { readerTab = "Markdown" },
                onShowJson = { readerTab = "JSON" },
                onShowSources = { readerTab = "来源" },
                onCopyMarkdown = {
                    val copied = doc?.markdown?.ifBlank { "暂无 Markdown" } ?: "未选择文档"
                    clipboard.setText(AnnotatedString(copied))
                    if (autoClearClipboard || copied.containsSensitiveData()) {
                        scope.launch {
                            delay(30_000)
                            clipboard.setText(AnnotatedString(""))
                        }
                    }
                    showReaderTools = false
                },
                onOpenAssistant = onOpenAssistant,
                onOpenTemplates = onOpenTemplates,
                onExport = onExport
            )
        }
        CardBlock {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { editDocumentInfo = !editDocumentInfo },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("文档信息", color = Ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(
                        doc?.name ?: "未选择文档",
                        color = Ink,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (docMetaSummary.isNotBlank()) {
                        Text(
                            docMetaSummary,
                            color = BrandBlue,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Icon(
                    Icons.Default.KeyboardArrowRight,
                    contentDescription = if (editDocumentInfo) "收起文档信息" else "编辑文档信息",
                    tint = BrandBlue,
                    modifier = Modifier
                        .size(24.dp)
                        .rotate(if (editDocumentInfo) 90f else 0f)
                )
            }
            if (editDocumentInfo) {
                OutlinedTextField(
                    value = editName,
                    onValueChange = { editName = it },
                    label = { Text("文档名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = editFolder,
                        onValueChange = { editFolder = it },
                        label = { Text("文件夹") },
                        modifier = Modifier.weight(0.42f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = editTags,
                        onValueChange = { editTags = it },
                        label = { Text("标签") },
                        placeholder = { Text("逗号分隔") },
                        modifier = Modifier.weight(0.58f),
                        singleLine = true
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            editName = doc?.name.orEmpty()
                            editTags = doc?.tags.orEmpty()
                            editFolder = doc?.folder ?: "默认"
                            editDocumentInfo = false
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("取消") }
                    Button(
                        onClick = {
                            onRenameDocument(editName)
                            onUpdateOrganization(editTags, editFolder)
                            editDocumentInfo = false
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("保存修改") }
                }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("原文预览", "AI 摘要", "Markdown", "JSON", "表格", "来源")) { tab ->
                SelectableChip(text = tab, selected = tab == readerTab, onClick = { readerTab = tab })
            }
        }
        CardBlock(modifier = Modifier.weight(1f)) {
            SectionHeader(readerTab, "")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                item {
                    when (readerTab) {
                        "原文预览" -> DocumentFullPreview(doc)
                        "AI 摘要" -> ReaderSummary(doc, state.insightResults[insightResultKey(doc?.id ?: 0L, "摘要")])
                        "Markdown" -> MarkdownText(doc?.markdown?.ifBlank { "暂无 Markdown" } ?: "")
                        "JSON" -> ReadableJsonText(doc?.parseJson?.ifBlank { """{"message":"暂无 JSON"}""" } ?: "")
                        "表格" -> ReaderTablePreview(doc)
                        else -> ReaderSources(doc)
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantScreen(
    state: DocPilotUiState,
    onBackHome: () -> Unit,
    onSelectDocument: (Long) -> Unit,
    onCloudChange: (Boolean) -> Unit,
    onAsk: (String) -> Unit,
    onGenerateInsight: (String) -> Unit,
    onDeleteMessage: (Long) -> Unit
) {
    var question by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf("问答") }
    var showAssistantSettings by rememberSaveable { mutableStateOf(false) }
    ScreenFrame {
        Header(
            title = "AI 助手",
            subtitle = state.status,
            trailing = Icons.Default.Settings,
            leading = Icons.Default.ArrowBack,
            onLeadingClick = onBackHome,
            onTrailingClick = { showAssistantSettings = !showAssistantSettings }
        )
        if (showAssistantSettings) {
            ActionPanel(
                title = "AI 助手设置",
                lines = listOf(
                    "TextIn xParse：先解析文档结构、表格与页码",
                    "Qwen：基于解析后的 Markdown 进行问答",
                    "本地优先：无 API Key 时使用本地兜底结果",
                    "云端增强：复杂摘要、合同抽取、论文笔记更准确"
                )
            )
        }
        DocumentPickerStrip(
            documents = state.documents,
            selectedId = state.selectedDocument?.id ?: 0L,
            onSelect = onSelectDocument,
            compact = true
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            item {
            StatusPill(icon = Icons.Default.AutoAwesome, text = "本地规则", color = Ink)
            }
            item {
            StatusPill(icon = Icons.Default.TextSnippet, text = "TextIn 解析上下文", color = SuccessGreen)
            }
            item {
            CompactCloudToggle(enabled = state.isCloudEnabled, onClick = { onCloudChange(!state.isCloudEnabled) })
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(assistantModes) { item ->
                SelectableChip(text = item, selected = item == mode, onClick = { mode = item })
            }
        }
        Text("上滑问答区域查看更多历史，下方可继续输入问题", color = Muted, fontSize = 11.sp)
        CardBlock(modifier = Modifier.weight(1f)) {
            if (mode == "问答") {
                SectionHeader("文档问答", state.selectedDocument?.name ?: "请选择文档")
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    item {
                        SourceTag("当前文档：${state.selectedDocument?.name ?: "未选择文档"}")
                    }
                    items(items = state.messages, key = { it.id }) { msg: ChatMessageEntity ->
                        ChatBubble(
                            user = msg.role == "user",
                            content = msg.content,
                            source = msg.source,
                            streaming = msg.streaming,
                            onDelete = { onDeleteMessage(msg.id) }
                        )
                    }
                }
            } else {
                val currentDocId = state.selectedDocument?.id ?: 0L
                val insightKey = insightResultKey(currentDocId, mode)
                SectionHeader(mode, if (state.workingTask == insightKey) "生成中..." else "来源可溯")
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    item {
                        val generated = state.insightResults[insightKey]
                        if (generated.isNullOrBlank()) {
                            AssistantModeContent(mode)
                            Button(
                                onClick = {
                                    question = assistantModePrompt(mode)
                                    onGenerateInsight(mode)
                                },
                                enabled = state.workingTask != insightKey,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            ) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(if (state.workingTask == insightKey) "正在生成 $mode..." else "基于当前文档生成 $mode")
                            }
                        } else {
                            MarkdownText(generated)
                            OutlinedButton(
                                onClick = {
                                    question = assistantModePrompt(mode)
                                    onGenerateInsight(mode)
                                },
                                enabled = state.workingTask != insightKey,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            ) {
                                Text(if (state.workingTask == insightKey) "正在重新生成..." else "重新生成")
                            }
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                placeholder = { Text("基于文档回答问题...") },
                modifier = Modifier.weight(1f).height(56.dp),
                singleLine = true,
                shape = RoundedCornerShape(18.dp)
            )
            IconButton(
                onClick = {
                if (question.isNotBlank()) {
                    onAsk(question)
                    question = ""
                }
            },
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(BrandBlue)
            ) {
                Icon(Icons.Default.Send, contentDescription = "发送", tint = Color.White)
            }
        }
    }
}

@Composable
private fun TemplatesScreen(
    state: DocPilotUiState,
    onSelectDocument: (Long) -> Unit,
    onExtractTemplate: (String, String) -> Unit,
    onSaveTemplateResult: (String, String) -> Unit,
    onDeleteTemplateHistory: (Long) -> Unit,
    autoClearClipboard: Boolean,
    onExport: (ExportFormat, String) -> Unit
) {
    var selectedTemplate by rememberSaveable { mutableStateOf("合同要点") }
    var customInstruction by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val currentDocId = state.selectedDocument?.id ?: 0L
    val resultKey = templateResultKey(currentDocId, selectedTemplate, if (selectedTemplate == "自定义提取") customInstruction else "")
    val latestResult = state.templateResults[resultKey].orEmpty()
    var showTemplateHistory by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val documentNames = state.documents.associate { it.id to it.name }
    val templateHistory = state.allExtractions
        .sortedByDescending { it.id }
    ScrollScreenFrame {
        Header(
            title = "提取模板",
            subtitle = state.selectedDocument?.name ?: "未选择文档",
            trailing = Icons.Default.Save,
            onTrailingClick = {
                if (latestResult.isNotBlank()) {
                    clipboard.setText(AnnotatedString(latestResult))
                    onSaveTemplateResult(selectedTemplate, latestResult)
                    if (autoClearClipboard || latestResult.containsSensitiveData()) {
                        scope.launch {
                            delay(30_000)
                            clipboard.setText(AnnotatedString(""))
                        }
                    }
                }
            }
        )
        DocumentPickerStrip(
            documents = state.documents,
            selectedId = state.selectedDocument?.id ?: 0L,
            onSelect = onSelectDocument
        )
        val templates: List<Triple<String, ImageVector, Color>> = listOf(
            Triple("会议纪要", Icons.Default.Description, SuccessGreen),
            Triple("合同要点", Icons.Default.TextSnippet, BrandBlue),
            Triple("论文笔记", Icons.Default.Article, WarningOrange),
            Triple("表格字段抽取", Icons.Default.TableChart, SuccessGreen),
            Triple("自定义提取", Icons.Default.Search, BrandBlue)
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items = templates, key = { it.first }) { item: Triple<String, ImageVector, Color> ->
                TemplateCard(
                    title = item.first,
                    icon = item.second,
                    color = item.third,
                    selected = item.first == selectedTemplate,
                    onClick = { selectedTemplate = item.first }
                )
            }
        }
        if (selectedTemplate == "自定义提取") {
            OutlinedTextField(
                value = customInstruction,
                onValueChange = { customInstruction = it },
                label = { Text("输入要提取的信息") },
                placeholder = { Text("例如：提取票据号码、金额、日期、销售方、购买方") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
        }
        Text("基于 ${state.selectedDocument?.name ?: "当前文档"} 抽取：$selectedTemplate", fontWeight = FontWeight.Bold, color = Ink)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { showTemplateHistory = !showTemplateHistory }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.History, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (showTemplateHistory) "收起历史" else "查看历史 (${templateHistory.size})")
            }
            Button(
                onClick = {
                    if (latestResult.isNotBlank()) onSaveTemplateResult(selectedTemplate, latestResult)
                },
                enabled = latestResult.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("保存")
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ExportFormat.values().toList()) { format ->
                OutlinedButton(
                    onClick = { onExport(format, latestResult) },
                    enabled = latestResult.isNotBlank()
                ) {
                    Text("导出 ${format.extension.uppercase()}")
                }
            }
        }
        if (showTemplateHistory) {
            TemplateHistoryList(
                items = templateHistory,
                documentNames = documentNames,
                onDelete = onDeleteTemplateHistory
            )
        }
        TemplateResult(
            template = selectedTemplate,
            generated = latestResult,
            isWorking = state.workingTask == resultKey,
            onExtract = { onExtractTemplate(selectedTemplate, if (selectedTemplate == "自定义提取") customInstruction else "") }
        )
    }
}

@Composable
private fun SettingsScreen(
    state: DocPilotUiState,
    onCloudChange: (Boolean) -> Unit,
    onSaveKeys: (String, String, String) -> Unit,
    onTestQwen: (String) -> Unit,
    onTestTextIn: (String, String) -> Unit,
    onSelectLocalModel: (String) -> Unit,
    onDownloadLocalModel: (String) -> Unit,
    onPauseLocalModelDownload: (String) -> Unit,
    onUninstallLocalModel: (String) -> Unit,
    onDefaultLocalModeChange: (Boolean) -> Unit,
    onAutoClearChange: (Boolean) -> Unit,
    onPerformanceModeChange: (String) -> Unit,
    onParseThreadCountChange: (Int) -> Unit,
    onAccelerationEngineChange: (String) -> Unit,
    onCloudModelChange: (String) -> Unit,
    onDeleteKeys: () -> Unit
) {
    var qwenKey by rememberSaveable { mutableStateOf(state.qwenKeyValue) }
    var textInAppId by rememberSaveable { mutableStateOf(state.textInAppIdValue) }
    var textInSecret by rememberSaveable { mutableStateOf(state.textInSecretValue) }
    var showCloudModelPicker by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val performanceModes = listOf("省电模式", "均衡模式", "极速模式")
    val accelerationEngines = listOf("系统 IO", "并行队列", "MNN (Arm SME2)", "省电队列")
    val cloudModels = listOf(
        CloudModelOption("qwen3.7-max", "Qwen3.7 Max", "复杂推理、合同/报告分析，质量优先"),
        CloudModelOption("qwen3.6-plus", "Qwen3.6 Plus", "通用文档问答与摘要，默认推荐"),
        CloudModelOption("qwen3.6-flash", "Qwen3.6 Flash", "快速问答、轻量抽取，速度优先"),
        CloudModelOption("qwen3.5-plus", "Qwen3.5 Plus", "稳定通用任务，兼顾成本与质量"),
        CloudModelOption("qwen3.5-flash", "Qwen3.5 Flash", "低延迟批量摘要和短问答")
    )
    val currentCloudModel = cloudModels.firstOrNull { it.id == state.cloudModel }
        ?: CloudModelOption(state.cloudModel, state.cloudModel, "当前已保存模型")
    LaunchedEffect(state.qwenKeyValue, state.textInAppIdValue, state.textInSecretValue) {
        if (qwenKey.isBlank()) qwenKey = state.qwenKeyValue
        if (textInAppId.isBlank()) textInAppId = state.textInAppIdValue
        if (textInSecret.isBlank()) textInSecret = state.textInSecretValue
    }

    ScrollScreenFrame {
        Header(
            title = "设置",
            subtitle = "本地优先 · 安全可控 · 性能优化",
            trailing = Icons.Default.Security,
            onTrailingClick = {
                Toast.makeText(context, "API Key 使用 Android Keystore 加密保存。", Toast.LENGTH_SHORT).show()
            }
        )
        SettingsGroup("本地模型包") {
            SettingsRow("当前模型", state.selectedLocalModel)
            val specs = state.localModelSpecs.ifEmpty { emptyList() }
            specs.forEach { model ->
                val runtime = state.localModelStates[model.name]
                ModelOptionRow(
                    model = model,
                    selected = runtime?.selected == true,
                    downloaded = runtime?.downloaded == true,
                    downloading = runtime?.downloading == true,
                    status = runtime?.status ?: "可下载",
                    progress = runtime?.progress ?: 0,
                    message = runtime?.message.orEmpty(),
                    onSelect = { onSelectLocalModel(model.name) },
                    onDownload = { onDownloadLocalModel(model.name) },
                    onPause = { onPauseLocalModelDownload(model.name) },
                    onUninstall = { onUninstallLocalModel(model.name) }
                )
            }
            SettingsRow(
                "当前推理",
                when {
                    state.isCloudEnabled -> "云端 Qwen"
                    state.localRuntimeStatus == "MNN 就绪" -> "本地 MNN"
                    else -> "本地规则兜底"
                }
            )
            SettingsRow(
                "MNN 运行状态",
                state.localRuntimeStatus,
                if (state.localRuntimeStatus == "MNN 就绪") SuccessGreen else WarningOrange
            )
            SettingsRow(
                "模型状态",
                state.localModelStates[state.selectedLocalModel]?.status ?: "未初始化",
                if (state.localModelStates[state.selectedLocalModel]?.downloaded == true) SuccessGreen else WarningOrange
            )
        }
        SettingsGroup("云端增强（可选）") {
            SettingsRow("云端 API Key", if (state.hasQwenKey) "已设置" else "未设置", if (state.hasQwenKey) SuccessGreen else WarningOrange)
            SettingsRow(
                "云端模型",
                currentCloudModel.title,
                onClick = { showCloudModelPicker = true }
            )
            Text(currentCloudModel.description, color = Muted, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("切换云端增强", fontWeight = FontWeight.SemiBold)
                    Text("开启后使用 ${state.cloudModel}", color = Muted, fontSize = 12.sp)
                }
                Switch(
                    checked = state.isCloudEnabled,
                    onCheckedChange = onCloudChange,
                    enabled = state.hasQwenKey
                )
            }
            if (!state.hasQwenKey) {
                Text("保存并测试 Qwen API Key 后才能开启云端增强。", color = WarningOrange, fontSize = 12.sp)
            }
        }
        SettingsGroup("API Key 安全存储") {
            OutlinedTextField(
                value = qwenKey,
                onValueChange = { qwenKey = it },
                label = { Text("阿里云 Qwen API Key") },
                placeholder = { Text("输入后可直接查看与测试") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            SavedValueLine("当前保存", state.qwenKeyValue)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { onTestQwen(qwenKey) }, modifier = Modifier.weight(1f)) {
                    Text("测试 ${state.cloudModel}")
                }
                TestStatusPill(state.qwenTestStatus)
            }
            OutlinedTextField(
                value = textInAppId,
                onValueChange = { textInAppId = it },
                label = { Text("TextIn x-ti-app-id") },
                placeholder = { Text("输入 app id") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            SavedValueLine("当前保存", state.textInAppIdValue)
            OutlinedTextField(
                value = textInSecret,
                onValueChange = { textInSecret = it },
                label = { Text("TextIn x-ti-secret-code") },
                placeholder = { Text("输入 secret") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            SavedValueLine("当前保存", state.textInSecretValue)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { onTestTextIn(textInAppId, textInSecret) }, modifier = Modifier.weight(1f)) {
                    Text("测试 TextIn")
                }
                TestStatusPill(state.textInTestStatus)
            }
            Button(
                onClick = {
                    onSaveKeys(qwenKey, textInAppId, textInSecret)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Lock, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("保存到 Android Keystore")
            }
            OutlinedButton(onClick = onDeleteKeys, modifier = Modifier.fillMaxWidth()) {
                Text("删除全部 API Key")
            }
        }
        SettingsGroup("隐私与安全") {
            SettingsToggle("默认模式（本地优先）", state.defaultLocalMode, onDefaultLocalModeChange)
            SettingsToggle("剪贴板与缓存自动清理", state.autoClearClipboardAndCache, onAutoClearChange)
        }
        SettingsGroup("性能与优化") {
            SettingsRow(
                "性能模式",
                "${state.performanceMode}${if (state.performanceMode == "均衡模式") "（推荐）" else ""} · 偏好",
                onClick = {
                    val current = performanceModes.indexOf(state.performanceMode).takeIf { it >= 0 } ?: 1
                    onPerformanceModeChange(performanceModes[(current + 1) % performanceModes.size])
                }
            )
            SettingsRow(
                "加速引擎",
                state.accelerationEngine,
                onClick = {
                    val current = accelerationEngines.indexOf(state.accelerationEngine).takeIf { it >= 0 } ?: 0
                    onAccelerationEngineChange(accelerationEngines[(current + 1) % accelerationEngines.size])
                }
            )
            MnnInfoBlock(selected = state.accelerationEngine == "MNN (Arm SME2)")
            SettingsRow(
                "并行解析线程偏好",
                "${state.parseThreadCount} 线程 · 实际 ${effectiveParseParallelism(state)}",
                onClick = {
                    val next = if (state.parseThreadCount >= 8) 1 else state.parseThreadCount + 1
                    onParseThreadCountChange(next)
                }
            )
        }
        SettingsGroup("TextIn xParse") {
            SettingsRow("解析引擎版本", "v2.3.1")
            SettingsRow("支持格式", "PDF / 图片 / Word / PPT / Excel")
            SettingsRow("凭证状态", if (state.hasTextInKey) "已设置" else "未设置", if (state.hasTextInKey) SuccessGreen else WarningOrange)
        }
        SettingsGroup("网络与区域（云端）") {
            SettingsRow("区域", "中国大陆（默认）")
            SettingsRow("API 端点", "local.properties / BuildConfig")
        }
        SettingsGroup("关于") {
            SettingsRow("版本", "1.0.0 (100)")
        }
    }
    if (showCloudModelPicker) {
        CloudModelPickerDialog(
            options = cloudModels,
            selected = state.cloudModel,
            onDismiss = { showCloudModelPicker = false },
            onSelect = { model ->
                onCloudModelChange(model)
                showCloudModelPicker = false
            }
        )
    }
}

@Composable
private fun ScreenFrame(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
private fun ScrollScreenFrame(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
private fun Header(
    title: String,
    subtitle: String,
    trailing: ImageVector,
    leading: ImageVector? = null,
    onLeadingClick: () -> Unit = {},
    onTrailingClick: () -> Unit = {}
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (leading != null) {
            IconButton(onClick = onLeadingClick) {
                Icon(leading, contentDescription = "返回", tint = Ink)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 13.sp, color = Muted)
        }
        IconButton(onClick = onTrailingClick) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SoftBlue),
                contentAlignment = Alignment.Center
            ) {
                Icon(trailing, contentDescription = null, tint = BrandBlue, modifier = Modifier.size(19.dp))
            }
        }
    }
}

private data class CloudModelOption(
    val id: String,
    val title: String,
    val description: String
)

@Composable
private fun ActionPanel(title: String, lines: List<String>) {
    CardBlock {
        SectionHeader(title, "")
        lines.forEach { Bullet(it) }
    }
}

@Composable
private fun NotificationPanel(state: DocPilotUiState) {
    val latestDocument = state.documents.maxByOrNull { it.id }
    val latestExtraction = state.allExtractions.maxByOrNull { it.id }
    CardBlock {
        SectionHeader("通知中心", state.status)
        Bullet("文档：${state.documents.size} 个，当前选择 ${state.selectedDocument?.name ?: "无"}")
        latestDocument?.let {
            Bullet("最近导入：${it.name}（${it.status}）")
        }
        latestExtraction?.let {
            Bullet("最近模板：${it.templateName}，来源 ${it.source}")
        }
        if (state.recentQuestions.isNotEmpty()) {
            Bullet("最近提问：${state.recentQuestions.first().content.take(36)}")
        }
    }
}

@Composable
private fun ReaderActionPanel(
    onShowPreview: () -> Unit,
    onShowMarkdown: () -> Unit,
    onShowJson: () -> Unit,
    onShowSources: () -> Unit,
    onCopyMarkdown: () -> Unit,
    onOpenAssistant: () -> Unit,
    onOpenTemplates: () -> Unit,
    onExport: (ExportFormat) -> Unit
) {
    CardBlock {
        SectionHeader("文档操作", "")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onShowPreview, modifier = Modifier.weight(1f)) { Text("预览") }
            OutlinedButton(onClick = onShowMarkdown, modifier = Modifier.weight(1f)) { Text("Markdown") }
            OutlinedButton(onClick = onShowJson, modifier = Modifier.weight(1f)) { Text("JSON") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onCopyMarkdown, modifier = Modifier.weight(1f)) { Text("复制") }
            OutlinedButton(onClick = onShowSources, modifier = Modifier.weight(1f)) { Text("来源") }
            Button(onClick = onOpenAssistant, modifier = Modifier.weight(1f)) { Text("问答") }
        }
        Button(onClick = onOpenTemplates, modifier = Modifier.fillMaxWidth()) {
            Text("进入模板抽取")
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ExportFormat.values().toList()) { format ->
                OutlinedButton(onClick = { onExport(format) }) {
                    Text("导出 ${format.extension.uppercase()}")
                }
            }
        }
    }
}

private fun String?.ifNullOrBlank(fallback: String): String = if (isNullOrBlank()) fallback else this

private fun documentMetaSummary(doc: DocumentEntity?): String {
    if (doc == null) return ""
    val parts = buildList {
        doc.folder.trim().takeIf { it.isNotBlank() && it != "默认" }?.let { add(it) }
        doc.tags.trim().takeIf { it.isNotBlank() }?.let { add(it) }
    }
    return parts.joinToString(" · ")
}

private fun String.containsSensitiveData(): Boolean {
    return Regex("""(?i)(api[-_ ]?key|secret|token|authorization|bearer\s+)""").containsMatchIn(this) ||
        Regex("""sk-[A-Za-z0-9_-]{12,}""").containsMatchIn(this) ||
        Regex("""\b\d{15,18}[\dXx]\b""").containsMatchIn(this) ||
        Regex("""\b1[3-9]\d{9}\b""").containsMatchIn(this)
}

@Composable
private fun DocumentPickerStrip(
    documents: List<DocumentEntity>,
    selectedId: Long,
    onSelect: (Long) -> Unit,
    compact: Boolean = false
) {
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("选择文档", fontWeight = FontWeight.Bold, color = Ink, fontSize = if (compact) 12.sp else 13.sp)
            if (compact) {
                Spacer(Modifier.width(6.dp))
                Text("左右滑动切换", color = Muted, fontSize = 10.sp)
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items = documents, key = { it.id }) { doc ->
                Surface(
                    modifier = Modifier.clickable { onSelect(doc.id) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (doc.id == selectedId) BrandBlue else Color.White,
                    border = BorderStroke(1.dp, if (doc.id == selectedId) BrandBlue else Color(0xFFE5E7EB))
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = if (compact) 8.dp else 10.dp, vertical = if (compact) 6.dp else 8.dp)) {
                        FileBadge(doc.type)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.width(if (compact) 124.dp else 150.dp)) {
                            Text(doc.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (doc.id == selectedId) Color.White else Ink)
                            Text(doc.status.shortStatus(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp, color = if (doc.id == selectedId) Color.White.copy(alpha = 0.85f) else Muted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchBox(value: String, onValueChange: (String) -> Unit, onFilterClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = Color.White, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Icon(Icons.Default.Search, contentDescription = null, tint = Muted)
            Spacer(Modifier.width(8.dp))
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = Ink, fontSize = 14.sp),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (value.isBlank()) Text("搜索本地文档...", color = Muted)
                    inner()
                }
            )
            IconButton(onClick = onFilterClick, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.FilterList, contentDescription = "切换筛选", tint = Ink)
            }
        }
    }
}

@Composable
private fun StatusPill(icon: ImageVector, text: String, color: Color) {
    Surface(shape = RoundedCornerShape(999.dp), color = color.copy(alpha = 0.1f)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
            Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CompactCloudToggle(enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick).widthIn(min = 92.dp),
        shape = RoundedCornerShape(999.dp),
        color = if (enabled) BrandBlue else SoftBlue,
        border = BorderStroke(1.dp, if (enabled) BrandBlue else Color(0xFFD7E3FF))
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Cloud, contentDescription = null, tint = if (enabled) Color.White else BrandBlue, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (enabled) "云端增强" else "云端设置", color = if (enabled) Color.White else BrandBlue, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (selected) BrandBlue else Color.White,
        border = BorderStroke(1.dp, if (selected) BrandBlue else Color(0xFFE5E7EB))
    ) {
        Text(text, color = if (selected) Color.White else Ink, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
    }
}

@Composable
private fun SelectableChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = if (selected) BrandBlue else Color.White,
        border = BorderStroke(1.dp, if (selected) BrandBlue else Color(0xFFE5E7EB))
    ) {
        Text(text, color = if (selected) Color.White else Ink, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
    }
}

@Composable
private fun SectionHeader(title: String, action: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(title, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
        if (action.isNotBlank()) Text(action, color = BrandBlue, fontSize = 12.sp)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun DocumentRow(
    doc: DocumentEntity,
    selected: Boolean,
    onRead: () -> Unit,
    onAnalyze: () -> Unit,
    onAsk: () -> Unit,
    onExtract: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = if (selected) BorderStroke(1.dp, BrandBlue.copy(alpha = 0.35f)) else null,
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FileBadge(doc.type)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(doc.name, fontWeight = FontWeight.SemiBold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${doc.updatedAt} · ${doc.sizeLabel}", color = Muted, fontSize = 12.sp)
                    val shortStatus = doc.status.shortStatus()
                    Text(shortStatus, color = if (shortStatus == "已解析") SuccessGreen else BrandBlue, fontSize = 12.sp)
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SoftBlue),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(if (doc.type == "图片") Icons.Default.Image else Icons.Default.Article, contentDescription = null, tint = BrandBlue)
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                MiniAction("阅读", onRead)
                MiniAction("分析", onAnalyze)
                MiniAction("问答", onAsk)
                MiniAction("抽取", onExtract)
                MiniAction("移除", onDelete, DangerRed)
            }
        }
    }
}

@Composable
private fun MiniAction(text: String, onClick: () -> Unit, color: Color = BrandBlue) {
    Text(
        text = text,
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.1f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

@Composable
private fun FileBadge(type: String) {
    val color = when (type) {
        "PDF" -> DangerRed
        "DOCX", "DOC" -> BrandBlue
        "PPTX", "PPT" -> WarningOrange
        "XLSX", "XLS", "CSV" -> SuccessGreen
        else -> Color(0xFF7C3AED)
    }
    Box(Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)).background(color), contentAlignment = Alignment.Center) {
        Icon(if (type == "PDF") Icons.Default.PictureAsPdf else Icons.Default.Description, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun RecentQuestion(question: String, source: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(question, color = Ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(source, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.ChatBubbleOutline, contentDescription = null, tint = BrandBlue, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun CardBlock(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun ChartMock() {
    Row(
        modifier = Modifier.fillMaxWidth().height(120.dp).padding(top = 10.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        listOf(44, 52, 66, 78, 92, 106).forEachIndexed { index, height ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .width(20.dp)
                        .height(height.dp)
                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                        .background(Brush.verticalGradient(listOf(BrandBlue, Color(0xFF8BC5FF))))
                )
                Text("${2019 + index}", fontSize = 9.sp, color = Muted)
            }
        }
    }
}

@Composable
private fun ReaderSummary(doc: DocumentEntity?, analysis: String?) {
    Text(doc?.name ?: "未选择文档", fontWeight = FontWeight.Bold, color = Ink)
    Spacer(Modifier.height(8.dp))
    Bullet("解析状态：${doc?.status ?: "未知"}")
    Bullet("格式：${doc?.type ?: "-"}，大小：${doc?.sizeLabel ?: "-"}")
    if (!analysis.isNullOrBlank()) {
        Spacer(Modifier.height(8.dp))
        MarkdownText(analysis)
    } else {
        Bullet("请在首页点击“分析”：先调用 TextIn 解析文档，再使用 AI 生成摘要。")
    }
    SourceTag("来源：${doc?.sourceUri?.ifBlank { "本地文档" } ?: "未选择"}")
}

@Composable
private fun DocumentFullPreview(doc: DocumentEntity?) {
    if (doc == null) {
        EmptyHint("未选择文档")
        return
    }
    SourceTag("正在阅读：${doc.name}")
    val context = LocalContext.current
    OutlinedButton(onClick = { openOriginalDocumentPreview(context, doc) }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Article, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("打开原文件预览（只读）")
    }
    if (doc.type == "图片" && doc.sourceUri.startsWith("asset://")) {
        AssetImagePreview(doc.sourceUri.removePrefix("asset://"))
        Spacer(Modifier.height(8.dp))
    }
    if (doc.markdown.isBlank()) {
        EmptyHint("暂无可阅读文本")
    } else {
        DocumentReadablePreview(doc)
    }
}

@Composable
private fun DocumentReadablePreview(doc: DocumentEntity) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SourceTag("分析结果预览 · ${doc.type} · ${doc.sizeLabel}")
        CardBlock {
            MarkdownText(doc.markdown)
        }
    }
}

@Composable
private fun AssetImagePreview(assetPath: String) {
    val context = LocalContext.current
    val bitmap = remember(assetPath) {
        runCatching {
            context.assets.open(assetPath).use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "文档图片预览",
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.FillWidth
        )
    }
}

@Composable
private fun ReaderTablePreview(doc: DocumentEntity?) {
    Text("表格预览", fontWeight = FontWeight.Bold, color = Ink)
    Spacer(Modifier.height(8.dp))
    val rows = extractMarkdownTableRows(doc?.markdown.orEmpty())
    if (rows.isEmpty()) {
        EmptyHint("当前文档没有识别到 Markdown 表格。")
        return
    }
    rows.forEachIndexed { index, row ->
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (index == 0) SoftBlue else Color.White).padding(8.dp)) {
            row.forEach {
                Text(it, modifier = Modifier.weight(1f), fontSize = 12.sp, fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal, color = Ink)
            }
        }
    }
}

@Composable
private fun ReaderSources(doc: DocumentEntity?) {
    Bullet("本地文件：${doc?.sourceUri?.ifBlank { "示例文档" } ?: "未选择"}")
    Bullet("解析结果：${doc?.status?.shortStatus() ?: "未知"}")
    Bullet("解析进度：${doc?.parseProgress ?: 0}%")
    Bullet("引用依据：${doc?.pageCount ?: 0} 个位置")
    doc?.citationsJson?.let { citations ->
        Regex(""""page"\s*:\s*(\d+).*?"title"\s*:\s*"([^"]*)".*?"snippet"\s*:\s*"([^"]*)"""")
            .findAll(citations)
            .take(6)
            .forEach { match ->
                Bullet("P${match.groupValues[1]} · ${match.groupValues[2]}：${match.groupValues[3]}")
            }
    }
}

@Composable
private fun Bullet(text: String) {
    Row {
        Text("•", color = BrandBlue)
        Spacer(Modifier.width(6.dp))
        Text(markdownInline(text), color = Ink, fontSize = 13.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun AssistantModeContent(mode: String) {
    when (mode) {
        "摘要" -> {
            Bullet("报告分析了 2019-2024 年行业发展现状与趋势。")
            Bullet("行业规模持续增长，2024 年预计达 6,480 亿元。")
            Bullet("技术、政策、需求、资本是核心驱动因素。")
            SourceTag("来源：P3-P4")
        }
        "提纲" -> {
            Bullet("一、行业概况：规模、增速、竞争格局。")
            Bullet("二、增长驱动：技术、政策、需求、资本。")
            Bullet("三、风险与机会：成本、数据安全、行业集中度。")
            Bullet("四、行动建议：优先验证高增长细分方向。")
        }
        "重点句" -> {
            Bullet("AI 与云计算成为提升效率与体验的关键变量。")
            Bullet("政策支持推动行业进入规范化发展阶段。")
            Bullet("头部企业加大研发与并购力度，竞争分化加剧。")
            SourceTag("来源：P12-P16")
        }
        else -> {
            Bullet("复核预测假设与引用数据口径。")
            Bullet("补充竞品对比与成本敏感性分析。")
            Bullet("确认报告中涉及的合同、预算和隐私风险。")
            SourceTag("来源：P9-P12")
        }
    }
}

@Composable
private fun SourceTag(text: String) {
    Text(text, color = Muted, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(SoftBlue).padding(horizontal = 8.dp, vertical = 4.dp))
}

@Composable
private fun ChatBubble(user: Boolean, content: String, source: String, streaming: Boolean, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (user) BrandBlue else SoftBlue,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(if (user) 0.82f else 0.9f)
        ) {
            Column(Modifier.padding(12.dp)) {
                if (user) {
                    Text(content, color = Color.White, fontSize = 13.sp, lineHeight = 20.sp)
                } else {
                    MarkdownText(content.ifBlank { "正在生成..." })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "来源：${source.shortSource()}${if (streaming) " · 流式生成中" else ""}",
                        color = if (user) Color.White.copy(alpha = 0.8f) else Muted,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除回答",
                        tint = if (user) Color.White.copy(alpha = 0.85f) else DangerRed,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable(onClick = onDelete)
                    )
                }
            }
        }
    }
}

@Composable
private fun TemplateCard(title: String, icon: ImageVector, color: Color, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, if (selected) BrandBlue else Color(0xFFE5E7EB)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.width(88.dp).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = color)
            }
            Spacer(Modifier.height(8.dp))
            Text(title, fontSize = if (title.length >= 6) 10.sp else 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Clip)
        }
    }
}

@Composable
private fun TemplateResult(template: String, generated: String?, isWorking: Boolean, onExtract: () -> Unit) {
    if (!generated.isNullOrBlank()) {
        CardBlock {
            SectionHeader("抽取结果", "本次结果")
            MarkdownText(generated)
        }
        OutlinedButton(onClick = onExtract, modifier = Modifier.fillMaxWidth()) {
            Text("重新抽取 $template")
        }
        return
    }

    Button(onClick = onExtract, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.AutoAwesome, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(if (isWorking) "TextIn 正在抽取..." else "基于 TextIn 抽取 $template")
    }
}

@Composable
private fun TemplateHistoryList(
    items: List<ExtractionEntity>,
    documentNames: Map<Long, String>,
    onDelete: (Long) -> Unit
) {
    var expandedId by rememberSaveable { mutableStateOf<Long?>(null) }
    CardBlock {
        SectionHeader("保存历史", "${items.size} 条")
        if (items.isEmpty()) {
            EmptyHint("暂无历史")
        } else {
            items.forEach { item ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SoftBlue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            expandedId = if (expandedId == item.id) null else item.id
                        }
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(item.templateName, color = Ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(if (expandedId == item.id) "收起" else "查看", color = BrandBlue, fontSize = 12.sp)
                            Spacer(Modifier.width(10.dp))
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除模板历史",
                                tint = DangerRed,
                                modifier = Modifier.size(20.dp).clickable { onDelete(item.id) }
                            )
                        }
                        if (expandedId == item.id) {
                            MarkdownText(item.content)
                        } else {
                            Text(item.content.take(180), color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                        }
                        SourceTag("来源：${item.source.shortSource()}")
                        Text(
                            "文档：${documentNames[item.documentId] ?: "已删除文档"}",
                            color = Muted,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(key, color = Muted, fontSize = 13.sp, modifier = Modifier.width(80.dp))
        Text(value, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MarkdownText(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val lines = text.lines()
        var index = 0
        while (index < lines.size) {
            val line = lines[index].trim()
            if (line.isBlank()) {
                index += 1
                continue
            }
            if (line.isMarkdownTableRow()) {
                val tableLines = mutableListOf<String>()
                while (index < lines.size && lines[index].trim().isMarkdownTableRow()) {
                    tableLines += lines[index].trim()
                    index += 1
                }
                MarkdownTable(tableLines.toMarkdownRows())
                continue
            }
            when {
                line.startsWith("##") -> Text(line.trimStart('#', ' '), fontWeight = FontWeight.Bold, color = Ink)
                line.startsWith("#") -> Text(line.trimStart('#', ' '), fontWeight = FontWeight.Bold, color = Ink, fontSize = 16.sp)
                line.startsWith("-") || line.startsWith("•") -> Bullet(line.trimStart('-', '•', ' '))
                line.matches(Regex("""^\d+[.、]\s+.*""")) -> Bullet(line)
                else -> Text(markdownInline(line), color = Ink, fontSize = 13.sp, lineHeight = 20.sp)
            }
            index += 1
        }
    }
}

@Composable
private fun MarkdownTable(rows: List<List<String>>) {
    if (rows.isEmpty()) return
    val scrollState = rememberScrollState()
    Surface(
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Color(0xFFE5E7EB)),
        color = Color.White,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
    ) {
        Column {
            rows.forEachIndexed { rowIndex, cells ->
                Row(
                    modifier = Modifier.background(if (rowIndex == 0) SoftBlue else Color.White)
                ) {
                    cells.forEach { cell ->
                        Text(
                            markdownInline(cell),
                            color = Ink,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            fontWeight = if (rowIndex == 0) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier
                                .width(132.dp)
                                .padding(horizontal = 8.dp, vertical = 7.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadableJsonText(text: String) {
    val cleaned = text
        .replace("TextInParseResponse(", "")
        .replace("TextInParseResult(", "")
        .replace("),", ",")
        .replace(")", "")
    Text(
        cleaned.ifBlank { """{"message":"暂无 JSON"}""" },
        color = Ink,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        fontFamily = FontFamily.Monospace
    )
}

private fun markdownInline(line: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    val boldPattern = Regex("""\*\*(.+?)\*\*""")
    boldPattern.findAll(line).forEach { match ->
        append(line.substring(cursor, match.range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(match.groupValues[1])
        }
        cursor = match.range.last + 1
    }
    append(line.substring(cursor))
}

private fun String.isMarkdownTableRow(): Boolean {
    val trimmed = trim()
    return trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.count { it == '|' } >= 2
}

private fun List<String>.toMarkdownRows(): List<List<String>> {
    return map { row -> row.trim().trim('|').split('|').map { it.trim() } }
        .filterNot { cells -> cells.all { it.replace("-", "").replace(":", "").isBlank() } }
        .filter { cells -> cells.any { it.isNotBlank() } }
}

@Composable
private fun CheckLine(text: String, checked: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (checked) BrandBlue else Color.White)
                .clickable(onClick = onClick)
                .padding(2.dp),
            contentAlignment = Alignment.Center
        ) {
            if (checked) Text("✓", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Text(text, color = if (checked) Muted else Ink, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onClick))
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = BrandBlue, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        CardBlock(content = content)
    }
}

@Composable
private fun SettingsRow(label: String, value: String, color: Color = Ink, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (onClick != null) {
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun CloudModelPickerDialog(
    options: List<CloudModelOption>,
    selected: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
        title = { Text("选择云端模型") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { option ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(option.id) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (option.id == selected) SoftBlue else Color.White,
                        border = BorderStroke(1.dp, if (option.id == selected) BrandBlue else Color(0xFFE5E7EB))
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(option.title, color = Ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                if (option.id == selected) Text("当前", color = BrandBlue, fontSize = 12.sp)
                            }
                            Text(option.id, color = BrandBlue, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text(option.description, color = Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun MnnInfoBlock(selected: Boolean) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) SoftBlue else Color.White,
        border = BorderStroke(1.dp, if (selected) BrandBlue else Color(0xFFE5E7EB)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("MNN (Arm SME2)", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text("端侧 Qwen 推理入口。App 会查找已选模型包里的 config.json，并调用 libdocpilot_mnn_llm.so 进行本地生成。", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
            Text("Arm SME2 模式会使用更高并行度和低精度偏好；云端增强仍由所选 Qwen 云端模型处理。", color = BrandBlue, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ModelOptionRow(
    model: LocalModelSpec,
    selected: Boolean,
    downloaded: Boolean,
    downloading: Boolean,
    status: String,
    progress: Int,
    message: String,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onUninstall: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) SoftBlue else Color.White,
        border = BorderStroke(1.dp, if (selected) BrandBlue else Color(0xFFE5E7EB))
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(if (selected) BrandBlue else SoftBlue),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Bolt, contentDescription = null, tint = if (selected) Color.White else BrandBlue, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(model.name, fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 13.sp)
                Text("${model.sizeLabel} · $status · ${model.usage}", color = Muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (downloading || status == "已暂停") {
                    Text("下载进度 $progress%", color = BrandBlue, fontSize = 11.sp)
                } else if (message.isNotBlank()) {
                    val messageColor = when {
                        downloaded -> SuccessGreen
                        status.contains("失败") -> DangerRed
                        status.contains("完成") -> WarningOrange
                        else -> Muted
                    }
                    val displayMessage = when {
                        downloaded -> message
                        status.contains("失败") || status.contains("完成") -> message
                        else -> ""
                    }
                    if (displayMessage.isNotBlank()) {
                        Text(displayMessage, color = messageColor, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (downloading) {
                OutlinedButton(onClick = onPause) {
                    Text("暂停", fontSize = 12.sp)
                }
            } else if (downloaded) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(onClick = onSelect, enabled = !selected) {
                        Text(if (selected) "使用中" else "切换", fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = onUninstall) {
                        Text("卸载", color = DangerRed, fontSize = 12.sp)
                    }
                }
            } else {
                OutlinedButton(onClick = onDownload) {
                    Text("下载", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun SavedValueLine(label: String, value: String) {
    Text(
        text = "$label：${value.maskSecret()}",
        color = if (value.isBlank()) Muted else Ink,
        fontSize = 12.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(SoftBlue).padding(8.dp)
    )
}

private fun String.maskSecret(): String {
    if (isBlank()) return "未保存"
    if (length <= 8) return "已保存（已隐藏）"
    return "${take(4)}****${takeLast(4)}"
}

@Composable
private fun TestStatusPill(status: String) {
    val color = when (status) {
        "测试中..." -> BrandBlue
        else -> when {
            status.startsWith("测试通过") -> SuccessGreen
            status.startsWith("测试失败") -> DangerRed
            status.startsWith("请输入") -> WarningOrange
            else -> Muted
        }
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.1f)
    ) {
        Text(status, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
    }
}

private fun formatBytes(size: Long): String {
    if (size <= 0) return "未知大小"
    val kb = size / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1) "%.1f MB".format(mb) else "%.0f KB".format(kb)
}

private fun parseSizeBytes(sizeLabel: String): Long {
    val value = Regex("""\d+(\.\d+)?""").find(sizeLabel)?.value?.toDoubleOrNull() ?: return 0L
    return when {
        sizeLabel.contains("GB", ignoreCase = true) -> (value * 1024 * 1024 * 1024).toLong()
        sizeLabel.contains("MB", ignoreCase = true) -> (value * 1024 * 1024).toLong()
        sizeLabel.contains("KB", ignoreCase = true) -> (value * 1024).toLong()
        else -> value.toLong()
    }
}

private fun String.shortStatus(): String {
    return when {
        startsWith("已解析") -> "已解析"
        contains("TextIn") && contains("Key") -> "待配置"
        contains("失败") -> "解析失败"
        contains("解析中") -> "解析中"
        else -> this
    }
}

private fun effectiveParseParallelism(state: DocPilotUiState): Int {
    val base = state.parseThreadCount.coerceIn(1, 8)
    return when (state.accelerationEngine) {
        "省电队列" -> 1
        "MNN (Arm SME2)" -> maxOf(base, 4).coerceAtMost(8)
        "并行队列" -> base
        else -> when (state.performanceMode) {
            "省电模式" -> 1
            "极速模式" -> maxOf(base, 4).coerceAtMost(8)
            else -> base.coerceAtMost(4)
        }
    }
}

private fun String.shortSource(): String {
    return when {
        contains("真实案例") -> "已解析"
        contains("本地兜底") || contains("本地优先") -> "本地"
        contains("TextIn") && contains("Qwen") -> "TextIn + Qwen"
        else -> this
    }
}

private fun templateResultKey(documentId: Long, templateName: String, customInstruction: String = ""): String {
    return "$documentId::$templateName::${customInstruction.trim()}"
}

private fun insightResultKey(documentId: Long, mode: String): String = "$documentId::$mode"

private fun assistantModePrompt(mode: String): String = when (mode) {
    "摘要" -> "请基于当前文档生成摘要"
    "提纲" -> "请基于当前文档生成提纲"
    "重点句" -> "请提取当前文档的重点句"
    "待办/风险点" -> "请提取当前文档的待办和风险点"
    else -> "请基于当前文档生成$mode"
}

private fun openOriginalDocumentPreview(context: Context, doc: DocumentEntity) {
    runCatching {
        val uri = if (doc.sourceUri.startsWith("asset://")) {
            val assetPath = doc.sourceUri.removePrefix("asset://")
            val file = File(context.cacheDir, "preview/${doc.name}").also { target ->
                target.parentFile?.mkdirs()
                context.assets.open(assetPath).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } else {
            Uri.parse(doc.sourceUri)
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, doc.type.toMimeType())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "预览 ${doc.name}"))
    }.onFailure {
        Toast.makeText(context, "手机上没有可用的只读预览器，已在页面展示解析内容。", Toast.LENGTH_LONG).show()
    }
}

private fun String.toMimeType(): String = when (uppercase()) {
    "PDF" -> "application/pdf"
    "DOCX", "DOC" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "PPTX", "PPT" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "XLSX", "XLS", "CSV" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "图片", "IMAGE", "PNG", "JPG", "JPEG" -> "image/*"
    else -> "*/*"
}

private fun extractMarkdownTableRows(markdown: String): List<List<String>> {
    return markdown.lines()
        .map { it.trim() }
        .filter { it.startsWith("|") && it.endsWith("|") }
        .filterNot { it.replace("|", "").replace("-", "").replace(":", "").trim().isBlank() }
        .take(12)
        .map { row ->
            row.trim('|').split('|').map { it.trim() }
        }
        .filter { cells -> cells.any { it.isNotBlank() } }
}

private fun markdownToPreviewHtml(title: String, markdown: String): String {
    val body = buildString {
        var inTable = false
        markdown.lines().forEach { raw ->
            val line = raw.trim()
            if (line.isBlank()) return@forEach
            if (line.startsWith("|") && line.endsWith("|")) {
                val cells = line.trim('|').split('|').map { it.trim() }
                val divider = cells.all { it.replace("-", "").replace(":", "").isBlank() }
                if (!divider) {
                    if (!inTable) {
                        append("<table>")
                        inTable = true
                    }
                    append("<tr>")
                    cells.forEach { append("<td>${it.escapeHtml().markdownInlineHtml()}</td>") }
                    append("</tr>")
                }
                return@forEach
            }
            if (inTable) {
                append("</table>")
                inTable = false
            }
            when {
                line.startsWith("##") -> append("<h2>${line.trimStart('#', ' ').escapeHtml().markdownInlineHtml()}</h2>")
                line.startsWith("#") -> append("<h1>${line.trimStart('#', ' ').escapeHtml().markdownInlineHtml()}</h1>")
                line.startsWith("-") || line.startsWith("•") -> append("<p class='bullet'>• ${line.trimStart('-', '•', ' ').escapeHtml().markdownInlineHtml()}</p>")
                Regex("""^\d+\.""").containsMatchIn(line) -> append("<p class='bullet'>${line.escapeHtml().markdownInlineHtml()}</p>")
                else -> append("<p>${line.escapeHtml().markdownInlineHtml()}</p>")
            }
        }
        if (inTable) append("</table>")
    }
    return """
        <!doctype html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
            <style>
                body { font-family: sans-serif; color: #111827; margin: 16px; line-height: 1.65; font-size: 15px; }
                h1 { font-size: 22px; margin: 0 0 14px; }
                h2 { font-size: 18px; margin: 18px 0 8px; color: #0B5FFF; }
                p { margin: 7px 0; }
                .doc-title { color: #64748B; font-size: 13px; margin-bottom: 12px; }
                .bullet { padding-left: 8px; }
                table { border-collapse: collapse; width: 100%; margin: 12px 0; font-size: 13px; }
                td { border: 1px solid #D9E2F1; padding: 8px; vertical-align: top; }
                tr:first-child td { background: #EFF6FF; font-weight: 700; }
                strong { font-weight: 700; }
            </style>
        </head>
        <body>
            <div class="doc-title">在线预览 · ${title.escapeHtml()}</div>
            $body
        </body>
        </html>
    """.trimIndent()
}

private fun String.escapeHtml(): String = replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

private fun String.markdownInlineHtml(): String =
    replace(Regex("""\*\*(.+?)\*\*"""), "<strong>$1</strong>")

@Composable
private fun EmptyHint(text: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = SoftBlue, modifier = Modifier.fillMaxWidth()) {
        Text(text, color = Muted, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun SettingsToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
