# PhoneAgent — 跑在手机里的 AI Agent

一个完全运行在 Android 手机本地的 AI 助手。你用自然语言说一句，它自己拆步骤、调工具、读写文件，做完把文件卡片放回聊天里，点开就能在应用内预览。

不需要服务器，不需要电脑，不需要常驻 ADB。装一个 APK，授权 Shizuku 或 Root，填一个模型 API Key 就能用。

> 当前版本：**1.0.15 Beta**（versionCode 75）
> 本仓库与其它同名项目没有关系，代码与实现均为独立完成。

---

## 它能做什么

### 对话与任务执行

- 接任意 **OpenAI 兼容**接口：DeepSeek、豆包 / 火山方舟、通义千问、Ollama、自建网关
- 多步工具调用自动编排，单轮最多 24 步；长任务会先抽出你列的步骤当检查清单
- 正文流式输出，随时停止；支持排队发送、草稿保留、重新思考、复制
- 安全策略三档：全部允许 / 高危确认 / 全部确认；高危命令先弹窗再执行

### 文件与文档

- **工作区沙箱**：指定一个目录，AI 只能在这里读写；相对路径按工作区根解析，越界会被拦下
- **原生生成 Office 文件**，不依赖第三方库、不联网：
  - `doc_write` 生成 docx（标题、列表、引用、加粗、行内代码）
  - `sheet_write` 生成 xlsx（表头加粗、数字自动识别、列宽自适应）
  - `ppt_write` 生成 pptx（每页标题 + 要点，`---` 分页）
- **读取 Office**：`sheet_read` 读 xlsx（含共享字符串），`doc_extract_text` 提取 docx 正文
- **压缩三件套**：`file_zip` / `file_unzip` / `file_zip_list`，打包后自动校验 Office 根部件
- 文件操作：列目录、读、写、追加、复制、移动、查找、grep、tail、stat、du、wc、chmod、删除
- **提交卡**：交付文件只记录真实路径，不复制占空间；卡片显示文件名、大小、版本，点开即预览
- **提交前体检**：0 字节、构建目录里的中间件、结构残缺的 docx/xlsx/pptx 一律拒收

### 预览

- 文本、Markdown、HTML、图片（手势缩放、自动换行、字号调节）
- docx / xlsx / pptx 本地渲染，使用开源引擎 **Universal File Preview**（MIT），文件不上传
- 深浅色两套渲染，可一键在「深色 / 原色」之间切换

### 设备与系统

- 应用管理：列出、安装、卸载、清数据、禁用、启动、停止、发广播
- 界面操作：截图、按键、点击、滑动、长按
- 系统：读写设置项、dumpsys、logcat、进程 / 存储 / 内存 / 电池 / 网络状态、系统属性
- 网络：HTTP GET/POST、文件下载、DNS 查询、网页抓取
- 图像：OCR 中文文字识别（ML Kit 离线）、图片内容理解
- 执行通道：**Root（Magisk / KernelSU）优先，Shizuku 回退**

### 记忆与会话

- 长期记忆存在本机数据库，可按相关度检索并注入提示词
- 防串扰：只有你话里出现「之前 / 上次 / 记得 / 习惯」这类线索才注入旧记忆；"设备上没有某工具"这类过期环境结论会在读写两侧被过滤
- 多会话、会话标题、归档、删除；「本次对话的文件」单独成页

### 更新

- 从 GitHub Releases 检查新版本，支持**正式版 / Beta** 两条通道
- 设置里可开关「加入 beta 更新」：关闭时只匹配正式版，打开后才会检查预发布版本
- 应用内下载 APK 并调起系统安装器，覆盖安装保留数据与设置

### 个性化

- Material 3，深浅色跟随系统或手动切换，主色盘五个槽位
- 输出风格（自然 / 简洁 / 专业 / 活泼 / 直白）、篇幅、Emoji 用量
- AI 人格设定、AI 眼中的你、Markdown 圆角
- 运行日志分级：软件问题与环境权限问题分开记，出问题进设置就能看

## 怎么用

1. 安装 APK（Android 8.0 及以上，minSdk 26）
2. 授权执行通道：Root 直接授权；没有 Root 就装 Shizuku，用 adb 启动一次服务并在应用内授权
3. 开启「所有文件访问权限」，否则工作区在 `/storage/emulated/0` 下时预览读不到文件
4. 设置 → 模型与接入：填 Base URL、API Key、模型名
5. 选一个工作区目录，回到聊天页说话即可

## 技术栈

| 层 | 选型 |
|---|---|
| 语言 / UI | Kotlin 2.x + Jetpack Compose（Material 3） |
| 数据 | Room（SQLite），迁移带 schema 自校验 |
| 网络 | OkHttp + SSE 手写流式解析（含 `reasoning_content` 与 `tool_calls` 增量聚合） |
| 执行 | Shizuku API + Root shell（`ShizukuExecutor`） |
| Office | 纯 Kotlin 写 docx / xlsx / pptx（ZIP + OOXML），预览用 Universal File Preview |
| 架构 | 单 Activity + Compose、ViewModel + StateFlow、手写 DI 容器 |

## 工程结构

```
app/src/main/java/com/phoneagent/
├── agent/          AgentController：多步循环、工具裁决、工作区沙箱、提示词、提交卡
├── llm/            OpenAI 兼容客户端（SSE 解析、可取消）
├── tools/          工具注册表、参数解析、安全策略
├── exec/           Shizuku / Root 执行器；OfficeWriter / PptxWriter / OfficeReader / OfficeCheck
├── data/           Room 实体、DAO、迁移、设置、更新检测
├── memory/         长期记忆：检索、写入护栏、过期结论过滤
├── ui/
│   ├── chat/       聊天页、流式渲染、Markdown、提交卡
│   ├── preview/    内置预览器（文本 / Markdown / HTML / 图片 / Office）
│   ├── settings/   设置页（模型、工具、工作区、记忆、更新、日志等）
│   ├── update/     更新弹窗
│   └── artifacts/  「本次对话的文件」
├── util/           运行日志、存储权限入口
└── PhoneAgentApp   初始化、默认工具注入、崩溃恢复
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

## 版本

**1.0.15 Beta** 的主要变化：

- 新增原生 Office 工具：`doc_write`、`sheet_write`、`ppt_write`、`sheet_read`
- 新增压缩工具：`file_zip`、`file_unzip`、`file_zip_list`
- 新增 Office 三格式内置预览；修复预览页取文件 404
- 修复设置二级页返回跳顶、文件覆盖页残留、顶栏换主题慢半拍
- 修复跨对话记忆串扰、xlsx 结构错误、zip 多套一层目录、docx 文本转义
- 优化 AI 输出与工具使用：步数上限提高、步骤清单、收尾纪律、提交前体检、自动补卡
- 新增应用内更新检测与 beta 通道

## 已知限制

- 不做无障碍 UI 自动化，不能逐屏点按第三方 App 界面；跨 App 操作靠 HTTP、网页抓取、OCR 或 shell 指令绕行
- Office 生成对复杂排版、图表、公式、多工作表联动、母版样式的支持有限，重要文档建议用办公软件复核
- 音视频暂无内置预览；PDF 暂无内置渲染
- 组件商店目前是空壳，只有入口没有内容
- Beta 与正式版共用包名 `com.phoneagent`，不能同时安装；系统只允许升级到更高 versionCode

## 免责声明

本应用会以较高权限在设备上执行命令、读写文件。请仅在**你自己的设备**上使用，并保持「高危确认」或更严格的安全策略。因误操作导致的数据丢失、设备异常等后果由使用者自行承担。

## 联系方式

| 渠道 | 地址 |
|---|---|
| GitHub Issues | https://github.com/wenweiqi114514wenweiqi-ux/Phone-Agent/issues |
| 邮箱 | 2483807557@qq.com |
| Telegram | [@Phone_Agent_1](https://t.me/Phone_Agent_1) |

Bug、建议、使用问题都可以从上面任意一个渠道发过来；反馈时附上版本号（设置 → 关于页有 `v版本号 · vc构建号`）和运行日志里的错误条目，定位会快很多。

## License

[MIT](LICENSE) © 2026 PhoneAgent contributors
