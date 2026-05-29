# 第三方资产与本地模型

本文说明项目内保留的第三方动态库、模型包和清理约定。

## MNN 动态库

参与 APK 构建的动态库位于：

```text
app/src/main/jniLibs/arm64-v8a/
├─ libc++_shared.so
├─ libMNN.so
├─ libdocpilot_mnn_llm.so
└─ libmnnllmapp.so
```

`LocalModelEngine.kt` 会按以下顺序加载：

1. `c++_shared`
2. `MNN`
3. `docpilot_mnn_llm`
4. 如果 `docpilot_mnn_llm` 加载失败，则回退到 `mnnllmapp`

## 本地模型归档

开发机保留模型包：

```text
third_party/mnn/models/Qwen3.5-0.8B-MNN/
├─ config.json
├─ llm.mnn
├─ llm.mnn.weight
├─ llm.mnn.json
├─ llm_config.json
└─ tokenizer.txt
```

这个目录不直接随 APK 打包。App 运行时的模型管理逻辑默认查找：

```text
Android/data/com.docpilot.qwen/files/models/
```

因此调试本地 MNN 时，需要通过 App 下载模型，或手动把模型目录放到 App 外部文件目录下。

## 已删除的重复资产

以下内容已清理：

- `third_party/mnn/mnn_chat_0_8_0.apk`
- `third_party/mnn/extracted/`

原因：

- APK 文件不参与当前 App 构建。
- `extracted/` 是从第三方 APK 解包出的中间目录。
- 当前真正参与构建的库已经整理到 `app/src/main/jniLibs/arm64-v8a/`。

如需重新追溯第三方来源，可以重新下载或重新解包对应 MNN Chat APK，但不要把解包中间目录作为源码长期保留。

## Git 忽略策略

以下内容应保持忽略：

- 构建产物：`.gradle/`、`build/`、`app/build/`
- Android 包：`*.apk`、`*.aab`
- 本地配置：`local.properties`
- 解包中间物：`third_party/mnn/extracted/`

当前 `third_party/mnn/models/` 已保持 Git 忽略。原因是 Qwen MNN 权重单文件超过 GitHub 普通仓库 100 MB 限制；本地可以继续保留模型包调试，公开分发时建议放到 GitHub Releases、对象存储，或由 App 的模型下载流程获取。

`releases/` 下手动整理好的 APK 更新包允许进入 Git，方便课程作业展示或小规模测试；普通 `app/build/` 构建产物仍保持忽略。
