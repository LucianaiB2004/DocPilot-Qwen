# 架构说明

DocPilot Qwen 当前采用单 App 模块架构，按 UI、数据、网络、本地模型和安全存储分层。

## 模块划分

```text
app/src/main/java/com/docpilot/qwen/
├─ MainActivity.kt
├─ DocPilotApplication.kt
├─ ui/
│  ├─ DocPilotApp.kt
│  ├─ DocPilotViewModel.kt
│  └─ theme/
├─ data/
│  ├─ AppContainer.kt
│  ├─ DocumentRepository.kt
│  ├─ export/
│  ├─ local/
│  ├─ network/
│  └─ settings/
└─ security/
```

## 数据流

1. UI 层
   - `DocPilotApp.kt` 使用 Jetpack Compose 构建首页、阅读、AI 助手、模板和设置。
   - UI 只调用 `DocPilotViewModel` 暴露的方法。

2. ViewModel 层
   - `DocPilotViewModel.kt` 聚合 Room Flow、设置 Flow、模型状态和临时 UI 状态。
   - 负责导入文件、读取 bytes、触发解析、问答、模板抽取和导出。

3. Repository 层
   - `DocumentRepository.kt` 是主要业务编排点。
   - 负责 TextIn 解析、Qwen 问答、本地兜底、演示数据、模板结果保存和 citation 生成。

4. Local 层
   - `DocPilotDatabase.kt`、`DocumentDao.kt`、`Entities.kt` 定义 Room 数据库。
   - `LocalModelManager.kt` 管理 MNN 模型下载、选择和状态。
   - `LocalModelEngine.kt` 负责 MNN 动态库加载和本地生成入口。

5. Network 层
   - `QwenApi.kt`：Qwen 兼容 OpenAI Chat Completions 普通请求。
   - `QwenStreamClient.kt`：Qwen SSE 流式请求。
   - `TextInApi.kt`：TextIn xParse 同步解析。
   - `NetworkModule.kt`：Retrofit/OkHttp 初始化。

6. Security/Settings
   - `ApiKeyStore.kt` 使用 Android Keystore + EncryptedSharedPreferences 保存密钥。
   - `SettingsStore.kt` 使用 DataStore 保存云端开关、性能模式、线程数、模型选择等偏好。

## 关键链路

### 导入解析

```text
OpenDocument -> DocPilotViewModel.importDocument()
  -> registerImportedDocument()
  -> read bytes by ContentResolver
  -> DocumentRepository.parseWithTextIn()
  -> TextIn success: save markdown/json/citations
  -> TextIn missing/fail: save local fallback markdown/json/citations
```

### 文档问答

```text
Assistant UI -> DocPilotViewModel.ask()
  -> DocumentRepository.askQwen()
  -> cloud enabled + Qwen key: streamChat()
  -> stream fail: normal chat()
  -> cloud unavailable/fail: MNN complete()
  -> MNN unavailable/fail: localAnswer()
```

### 模板抽取

```text
Template UI -> DocPilotViewModel.extractTemplate()
  -> optional TextIn re-parse
  -> completeWithQwenOrLocal()
  -> save ExtractionEntity if new
```

### 导出

```text
Reader/Template UI -> DocPilotViewModel.exportSelectedDocument()
  -> DocumentExporter.export()
  -> FileProvider ACTION_SEND
```

## 设计注意事项

- UI 上展示“本地优先”时，要区分本地规则兜底和 MNN 实际生成。
- TextIn 是当前真实结构化解析主路径。
- Qwen 是当前复杂问答、摘要和模板抽取主路径。
- 无 Key 场景必须保持诚实提示，不能把有限兜底说成完整解析。
- `third_party/mnn/models/` 是开发机归档，App 实际运行时读取外部文件目录下的模型包。
