# 当前状态与优化清单

本文记录当前项目真实可用能力、已清理内容和下一阶段待办。

## 已跑通

- Debug 构建：`.\gradlew.bat :app:assembleDebug`
- 手机安装：`.\gradlew.bat :app:installDebug`
- 主 Activity 启动：`com.docpilot.qwen/.MainActivity`
- 首页、文档阅读、AI 助手、模板抽取、设置页基础 UI
- Room 本地文档、消息、抽取结果存储
- Android Keystore 保存 Qwen/TextIn 凭证
- TextIn xParse 同步解析接口
- Qwen Chat Completions 普通请求和流式请求
- MNN 本地推理动态库加载入口
- 演示文档初始化和演示问答记录
- DOCX/PDF/CSV/XLSX 简易导出

## 已清理

- 删除 `.gradle/`
- 删除 `app/build/`
- 删除 `third_party/mnn/extracted/`
- 删除 `third_party/mnn/mnn_chat_0_8_0.apk`
- `.gitignore` 增加 `third_party/mnn/extracted/`
- `seedMockData()` 改名为 `seedDemoData()`，明确这是演示数据

## 当前保留资产

- `app/src/main/jniLibs/arm64-v8a/`
  - `libMNN.so`
  - `libmnnllmapp.so`
  - `libdocpilot_mnn_llm.so`
  - `libc++_shared.so`
- `app/src/main/assets/sample_docs/`
  - 5 个首次启动演示文档
- `third_party/mnn/models/Qwen3.5-0.8B-MNN/`
  - 本地模型包归档，不直接随 APK 打包

## 仍需打通

1. 真实本地解析
   - 当前无 TextIn Key 时，对 PDF/DOCX/PPTX/XLSX 只能做文件级兜底。
   - 需要接入本地 PDF 文本抽取、Office 文本抽取、图片 OCR 或明确提示必须使用 TextIn。

2. 分享导入和拍照导入
   - Manifest 目前只有 launcher intent-filter。
   - 需要补 `ACTION_SEND`/`ACTION_SEND_MULTIPLE` 和相机/扫描入口。

3. 来源可溯
   - TextIn 返回 pages 时可生成 citation。
   - 本地 fallback citation 仍是按 Markdown 分段模拟页码，需要真实页码/表格/图片坐标。

4. 本地模型体验
   - MNN 入口已接上。
   - 需要验证模型目录、运行库 ABI、VL 输入和生成效果。
   - 需要在 UI 上区分“本地规则兜底”和“本地 MNN 实际生成”。

5. 导出保真
   - 当前 DOCX/XLSX 是最小 zip 结构，PDF 是纯文本绘制。
   - 需要完善 Markdown 样式、表格、来源脚注、图片和分页。

6. 隐私清理
   - 当前已支持复制后延迟清空剪贴板，并清理 `cache/preview`。
   - 还需要覆盖 `cache/vl_inputs`、导出文件、历史记录和远端上传提示。

7. 错误与状态
   - 需要把 TextIn/Qwen/MNN 的失败原因分层展示。
   - 需要给导入、解析、生成、导出增加更明确的用户可恢复操作。

## 推荐下一步

1. 先补系统分享导入，让外部文件能直接进 App。
2. 再把 TextIn 解析失败/无 Key 时的 UI 状态做诚实化，避免用户误以为已完整解析。
3. 然后验证 MNN 模型路径和端侧生成，把“本地规则兜底”和“MNN 就绪”彻底分开。
4. 最后补导出保真和隐私清理。
