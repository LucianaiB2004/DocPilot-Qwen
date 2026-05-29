# DocPilotQwen 1.0.0 Debug 更新包

生成时间：2026-05-28  
包名：`com.docpilot.qwen`  
版本：`1.0.0` / `versionCode 1`  
文件：`DocPilotQwen-1.0.0-debug.apk`  
大小：`34,406,799 bytes`  
SHA-256：`1CD673F9C9182DDD0E9B11CE756E26549E81E47B44A088433663A3DFD92C3685`

## 本包包含

- Android 原生应用：
  - Kotlin + Jetpack Compose UI
  - Room 本地数据库
  - DataStore 设置项
  - Android Keystore 凭证存储
  - 文档导入、阅读、AI 助手、模板抽取、设置页面
- 网络能力：
  - TextIn xParse API 接入
  - Qwen 兼容 OpenAI Chat Completions API 接入
  - 普通请求与流式问答逻辑
- 内置演示文档：
  - `2024_it_industry_report.pdf`
  - `annual_plan.pptx`
  - `docpilot_qwen_prd.docx`
  - `finance_summary.xlsx`
  - `meeting_whiteboard.png`
- MNN arm64-v8a 动态库：
  - `libMNN.so`
  - `libmnnllmapp.so`
  - `libdocpilot_mnn_llm.so`
  - `libc++_shared.so`

## 本次已包含的近期修复

- 修复 Debug 构建中 `PageCitation` 缺少 `id` 的问题。
- 修复 AI 助手摘要/提纲等模式点击后不进入聊天记录的问题。
- 修复应用重启后进入 AI 助手可能显示空白聊天的问题。
- 修复 AI 助手发送问题时异常静默、界面像卡住的问题。
- 优化 AI 助手文档问答区域、入口状态和模型设置页面展示。
- 精简 README，补充背景、技术栈、使用方法和 APK 内容说明。

## 安装方式

连接 Android 手机并打开 USB 调试后，在项目根目录执行：

```powershell
.\gradlew.bat :app:installDebug
```

也可以直接安装本目录下的 APK：

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r releases\DocPilotQwen-1.0.0-debug\DocPilotQwen-1.0.0-debug.apk
```

这是 Debug 签名包，适合测试、课程作业展示和开源项目演示；正式分发前建议生成 Release 签名包。
