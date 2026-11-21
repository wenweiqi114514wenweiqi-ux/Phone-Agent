# PhoneAgent — 手机里的 AI Agent

一个跑在 Android 手机上的 AI 助手：**说一句话，它就能自己动手改文件、跑脚本、操作工作区**，改完把文件卡片交回聊天里，点开就能在应用内预览。

不是"云端聊天窗口"，而是把手机当成一台可以被 AI 直接操作的小电脑。

> 当前版本：**1.0.13**（versionCode 61）

---

## 它能做什么

- **自然语言任务**：接任意 OpenAI 兼容接口（DeepSeek / 豆包 / 通义 / Ollama / 自建），多步工具调用自动编排
- **工作区沙箱**：指定一个目录作为工作区，AI 只能在这个目录里读写；相对路径按工作区根解析，越界/只读会被拦下
- **文件交付**：AI 改过的文件不会复制一份占空间，只记录真实路径；聊天里出「文件名 + 大小 + 版本」的卡片，点开用内置预览器打开
- **流式输出**：正文逐字长出来，代码第一行起就进代码框、随行数向下延伸；可随时停止
- **消息操作**：用户消息可复制/重新输入，AI 回答可重新思考；回答正文长按可选中复制
- **内置预览**：文本 / Markdown / HTML / 图片（含手势缩放），文本支持自动换行与字号调节
- **运行日志**：崩溃与异常分级记录（软件问题 / 环境权限问题分开），设置里可查
- **执行通道**：Shizuku（免 root，一次 adb 授权长期可用）优先，Root 回退
- **安全策略**：三档执行策略（全部允许 / 需要确认 / 全部拒绝），高危命令弹窗确认

## 技术栈

| 层 | 选型 |
|---|---|
| 语言 / UI | Kotlin 2.x + Jetpack Compose（Material 3） |
| 数据 | Room（SQLite），迁移带 schema 自校验与兜底修复 |
| 网络 | OkHttp + SSE（手写流式解析，含 reasoning_content 与 tool_calls 增量聚合） |
| 执行 | Shizuku API（`ShizukuExecutor`），失败回退 Root |
| 架构 | 单 Activity + Compose、ViewModel + StateFlow、轻量手写 DI 容器 |

最低支持 Android 8.0（minSdk 26），targetSdk 36。

## 工程结构

```
app/src/main/java/com/phoneagent/
├── agent/          AgentController：多步循环、工具裁决、工作区沙箱、交付物、流式回调
├── llm/            OpenAI 兼容客户端（SSE 解析、可取消）
├── tools/          工具注册表、参数解析、安全策略
├── exec/           Shizuku / Root 执行器
├── data/           Room 实体、DAO、数据库与迁移
├── ui/
│   ├── chat/       聊天页、消息分组、流式渲染、Markdown、提交卡
│   ├── preview/    内置文件预览器（文本/Markdown/HTML/图片）
│   ├── settings/   设置页（模型、工具、工作区、记忆、日志等）
│   └── artifacts/  「本次对话的文件」清单
├── util/           运行日志、存储权限入口等
└── PhoneAgentApp   应用初始化、默认工具注入、崩溃恢复
```

## 构建

前置：JDK 17、Android SDK（compileSdk 36、build-tools 35.0.0）。

```bash
# 1) 指向本机 SDK（该文件不入库）
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 2) 打包 debug
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

首次运行需要在系统里做两件事：

1. **Shizuku 授权**（免 root 执行能力）：安装 Shizuku，用 adb 启动一次服务，在应用内授权；
2. **文件访问权限**：工作区位于 `/storage/emulated/0` 下时，应用内预览需要「所有文件访问权限」——首次启动会弹窗引导开启。

## 版本要点

| 版本 | 关键变化 |
|---|---|
| 1.0.13 | 流式输出（含逐行成形的代码框）、停止生成、输入草稿与排队发送、消息操作栏（复制 / 重新输入 / 重新思考）、文件卡重做、启动即检测文件权限、文本预览自动排版 |
| 1.0.12 | 工作区相对路径误判修复、写类工具成功后自动出提交卡 |
| 1.0.11 | 交付物改为「只记路径不复制」，同路径同会话天然去重 |
| 1.0.10 | 交付物 / 提交卡 / 内置预览器骨架，运行日志分级 |
| 1.0.9  | 启动崩溃修复（Room 迁移 schema 对齐 + 数据库自愈） |

## Roadmap

- [ ] Office 内置预览（docx / xlsx / pptx，纯 Kotlin 解析，不引 POI）
- [ ] 音视频预览（ExoPlayer，倍速 / 进度 / 横屏 / 音量）
- [ ] 流式阶段的表格成形（避免闭合瞬间的排版跳变）
- [ ] 预览器里选中文本回注对话

## 免责声明

本项目会以较高权限在设备上执行命令并读写文件。请仅在**你自己的设备**上使用，并保持「需要确认」或更严格的安全策略。因误操作导致的数据丢失、设备异常等后果由使用者自行承担。

## License

[MIT](LICENSE) © 2026 PhoneAgent contributors

## 发布

```bash
# 建仓库 + 推代码 + 建 Release + 上传 APK（需要 repo 权限的 token）
GITHUB_TOKEN=ghp_xxx ./scripts/publish_github.sh
```
