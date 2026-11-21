package com.phoneagent.tools

import com.phoneagent.data.ToolDao
import com.phoneagent.data.ToolEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * 工具注册表：内置默认集 + 用户自定义（DB 持久化）。
 * 启用项的工具定义会注入 LLM 上下文（Function Calling tools 参数）。
 */
class ToolRegistry(
    private val toolDao: ToolDao,
) {
    fun observeAll(): Flow<List<ToolDefinition>> =
        toolDao.observeAll().map { list -> mergedEntities(list).map { it.toDefinition() } }

    suspend fun enabledTools(): List<ToolDefinition> =
        mergedEntities(toolDao.observeAll().first())
            .filter { it.enabled }
            .map { it.toDefinition() }

    /**
     * 代码里的内置工具与库里记录合并：库里缺的、插入失败的，一律以代码定义补齐（默认启用）。
     * 这样即使升级后初始化没跑完，新工具也不会"消失"，模型和工具页都看得见。
     */
    private fun mergedEntities(entities: List<ToolEntity>): List<ToolEntity> {
        val defs = defaults()
        val byName = entities.associateBy { it.name }
        val builtin = defs.map { def ->
            val existing = byName[def.name]
            if (existing != null) def.toEntity().copy(enabled = existing.enabled) else def.toEntity()
        }
        val extras = entities.filter { !it.builtin && it.name !in defs.map { def -> def.name }.toSet() }
        return builtin + extras
    }

    suspend fun byName(name: String): ToolDefinition? =
        toolDao.byName(name)?.toDefinition()

    suspend fun upsert(tool: ToolDefinition) {
        toolDao.upsert(tool.toEntity())
    }

    suspend fun delete(tool: ToolDefinition) {
        toolDao.delete(tool.toEntity())
    }

    /**
     * 写入/更新内置默认工具集：
     * - 新工具：直接插入；
     * - 已存在的内置工具：以代码为准覆盖（更新 handler/commandTemplate/description），
     *   但保留用户修改的 enabled 开关。
     */
    suspend fun ensureDefaults() {
        val defaults = defaults()
        val defaultNames = defaults.map { it.name }.toSet()
        defaults.forEach { def ->
            runCatching {
                val existing = toolDao.byName(def.name)
                if (existing == null) {
                    toolDao.upsert(def.toEntity())
                } else if (existing.builtin) {
                    toolDao.upsert(def.toEntity().copy(enabled = existing.enabled))
                }
            }.onFailure { e ->
                com.phoneagent.util.AppLog.error("tool", "写入内置工具失败：" + def.name, e)
            }
        }
        com.phoneagent.util.AppLog.info("tool", "内置工具同步完成，共 " + defaults.size + " 个")
        // 清理代码中已移除的内置工具（如旧版 curl_exec），保留用户自定义工具
        toolDao.observeAll().first().forEach { entity ->
            if (entity.builtin && entity.name !in defaultNames) {
                toolDao.delete(entity)
            }
        }
    }

    /** 内置默认工具集（v2 共 36 个：文件/应用/系统/输入/设置/通用）。 */
    private fun defaults(): List<ToolDefinition> = listOf(
        // ===== 应用管理 =====
        ToolDefinition(
            name = "pm_list_packages",
            description = "列出手机已安装的应用包名列表",
            handler = "shell", commandTemplate = "pm list packages",
            risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "pm_uninstall",
            description = "卸载指定应用（包名）",
            parameters = obj("pkg" to "要卸载的应用包名"),
            handler = "shell", commandTemplate = "pm uninstall {pkg}",
            risk = "high", builtin = true,
        ),
        ToolDefinition(
            name = "pm_install",
            description = "安装 APK 文件",
            parameters = obj("path" to "APK 文件路径"),
            handler = "shell", commandTemplate = "pm install -r {path}",
            risk = "high", builtin = true,
        ),
        ToolDefinition(
            name = "pm_clear",
            description = "清除指定应用的数据和缓存",
            parameters = obj("pkg" to "应用包名"),
            handler = "shell", commandTemplate = "pm clear {pkg}",
            risk = "high", builtin = true,
        ),
        ToolDefinition(
            name = "pm_disable",
            description = "禁用指定应用（包名）",
            parameters = obj("pkg" to "要禁用的应用包名"),
            handler = "shell", commandTemplate = "pm disable-user {pkg}",
            risk = "high", builtin = true,
        ),
        ToolDefinition(
            name = "am_start",
            description = "启动应用或指定界面（包名/组件）",
            parameters = obj("component" to "如 com.example.app/.MainActivity"),
            handler = "shell", commandTemplate = "am start -n {component}",
            risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "am_force_stop",
            description = "强制停止指定应用",
            parameters = obj("pkg" to "应用包名"),
            handler = "shell", commandTemplate = "am force-stop {pkg}",
            risk = "high", builtin = true,
        ),
        ToolDefinition(
            name = "am_broadcast",
            description = "发送广播 Intent",
            parameters = obj("action" to "广播 action，如 android.intent.action.MEDIA_SCANNER_SCAN_FILE"),
            handler = "shell", commandTemplate = "am broadcast -a {action}",
            risk = "low", builtin = true,
        ),
        // ===== 截图与输入 =====
        ToolDefinition(
            name = "screencap",
            description = "截取当前屏幕并保存到指定路径",
            parameters = obj("path" to "保存路径，如 /sdcard/screen.png"),
            handler = "shell", commandTemplate = "screencap -p {path}",
            risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "input_keyevent",
            description = "模拟按键（keycode 数字）",
            parameters = obj("keycode" to "按键码，如 3=返回 4=Home 26=电源"),
            handler = "shell", commandTemplate = "input keyevent {keycode}",
            risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "input_text",
            description = "向当前焦点输入文本",
            parameters = obj("text" to "要输入的文本"),
            handler = "shell", commandTemplate = "input text {text}",
            risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "input_tap",
            description = "点击屏幕指定坐标",
            parameters = obj("x" to "横坐标", "y" to "纵坐标"),
            handler = "shell", commandTemplate = "input tap {x} {y}",
            risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "input_swipe",
            description = "在屏幕上滑动",
            parameters = obj("x1" to "起点x", "y1" to "起点y", "x2" to "终点x", "y2" to "终点y", "duration" to "持续毫秒，如300"),
            handler = "shell", commandTemplate = "input swipe {x1} {y1} {x2} {y2} {duration}",
            risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "input_longpress",
            description = "长按屏幕指定坐标（1秒）",
            parameters = obj("x" to "横坐标", "y" to "纵坐标"),
            handler = "shell", commandTemplate = "input swipe {x} {y} {x} {y} 1000",
            risk = "low", builtin = true,
        ),
        // ===== 文件操作 =====
        ToolDefinition(
            name = "file_list",
            description = "列出目录下的文件（单列输出，最多显示300项，大目录请指定子目录）",
            parameters = obj("path" to "目录路径"),
            handler = "shell", commandTemplate = "echo \"共 $(ls -1 {path} 2>/dev/null | wc -l) 项（最多显示300项）:\" && ls -1 {path} 2>/dev/null | head -300",
            risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "file_mkdir",
            description = "创建目录（递归创建父目录）",
            parameters = obj("path" to "要创建的目录路径"),
            handler = "shell", commandTemplate = "mkdir -p {path} && echo \"已创建: {path}\"",
            risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "file_touch",
            description = "创建空文件",
            parameters = obj("path" to "要创建的文件路径"),
            handler = "shell", commandTemplate = "touch {path} && echo \"已创建: {path}\"",
            risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "file_copy",
            description = "复制文件或目录",
            parameters = obj("src" to "源路径", "dst" to "目标路径"),
            handler = "shell", commandTemplate = "cp -r {src} {dst} && echo \"已复制: {src} -> {dst}\"",
            risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "file_move",
            description = "移动或重命名文件/目录",
            parameters = obj("src" to "源路径", "dst" to "目标路径"),
            handler = "shell", commandTemplate = "mv {src} {dst} && echo \"已移动: {src} -> {dst}\"",
            risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "file_cat",
            description = "查看文本文件内容（最多前200行）",
            parameters = obj("path" to "文件路径"),
            handler = "shell", commandTemplate = "head -200 {path}",
            risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "file_find",
            description = "在目录下搜索文件（按名称匹配，最多100条结果）",
            parameters = obj("path" to "搜索目录", "name" to "文件名匹配模式，如 *.txt"),
            handler = "shell", commandTemplate = "find {path} -name \"{name}\" 2>/dev/null | head -100",
            risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "file_chmod",
            description = "修改文件/目录权限",
            parameters = obj("path" to "路径", "mode" to "权限模式，如 755/644"),
            handler = "shell", commandTemplate = "chmod {mode} {path} && echo \"已设置权限: {path} = {mode}\"",
            risk = "high", builtin = true,
        ),
        ToolDefinition(
            name = "file_remove",
            description = "删除文件或目录",
            parameters = obj("path" to "要删除的路径"),
            handler = "shell", commandTemplate = "rm -rf {path} && echo \"已删除: {path}\"",
            risk = "high", builtin = true,
        ),
        // ===== 系统设置 =====
        ToolDefinition(
            name = "settings_get",
            description = "读取系统设置项的值",
            parameters = obj("namespace" to "命名空间：system/secure/global", "key" to "设置键"),
            handler = "shell", commandTemplate = "settings get {namespace} {key}",
            risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "settings_put",
            description = "修改系统设置项（system 命名空间）",
            parameters = obj("key" to "设置键", "value" to "设置值"),
            handler = "shell", commandTemplate = "settings put system {key} {value}",
            risk = "high", builtin = true,
        ),
        ToolDefinition(
            name = "settings_put_global",
            description = "修改全局系统设置（global 命名空间，如动画缩放、USB调试）",
            parameters = obj("key" to "设置键", "value" to "设置值"),
            handler = "shell", commandTemplate = "settings put global {key} {value}",
            risk = "high", builtin = true,
        ),
        // ===== 系统信息 =====
        ToolDefinition(
            name = "dumpsys",
            description = "查询系统服务信息",
            parameters = obj("service" to "服务名，如 activity/window/battery"),
            handler = "shell", commandTemplate = "dumpsys {service}",
            risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "logcat",
            description = "读取最近系统日志（200行）",
            handler = "shell", commandTemplate = "logcat -d -t 200",
            risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "sys_df",
            description = "查看磁盘空间使用情况",
            handler = "shell", commandTemplate = "df -h",
            risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "sys_free",
            description = "查看内存使用情况",
            handler = "shell", commandTemplate = "free -h",
            risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "sys_ps",
            description = "查看运行中的进程（最多100条）",
            handler = "shell", commandTemplate = "ps -A | head -100",
            risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "sys_getprop",
            description = "读取系统属性（getprop）",
            parameters = obj("key" to "属性名，如 ro.build.version.release（留空列出全部）"),
            handler = "shell", commandTemplate = "getprop {key}",
            risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "sys_ip",
            description = "查看网络接口和IP地址",
            handler = "shell", commandTemplate = "ip addr show",
            risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "sys_battery",
            description = "查看电池状态信息",
            handler = "shell", commandTemplate = "dumpsys battery",
            risk = "low", builtin = true,
        ),
        // ===== 用户交互 =====
        ToolDefinition(
            name = "ask_user",
            description = "当需要向用户确认、让用户选择或补充信息时调用。会暂停等待用户回复。参数 options 可留空（自由输入）或用逗号分隔提供2-4个选项。",
            parameters = obj("question" to "要向用户展示的问题", "options" to "可选，逗号分隔的选项，如 确认,取消,再看看；留空则用户自由输入"),
            handler = "ui", risk = "low", builtin = true,
        ),
        // ===== 设备控制 =====
        ToolDefinition(
            name = "reboot",
            description = "重启设备（需要 root 或系统权限）",
            handler = "shell", commandTemplate = "reboot",
            risk = "high", builtin = true,
        ),
        // ===== 通用 Shell =====
        ToolDefinition(
            name = "shell_exec",
            description = "执行任意 shell 命令（高危，每次需确认）。当专用工具不够用时使用此工具。",
            parameters = obj("command" to "要执行的 shell 命令"),
            handler = "shell", commandTemplate = "{command}",
            risk = "high", builtin = true,
        ),
        // ===== 文件增强 =====
        ToolDefinition(
            name = "file_write",
            description = "写文本到文件（覆盖原有内容），支持多行",
            parameters = obj("path" to "文件路径", "content" to "要写入的文本内容"),
            handler = "shell", commandTemplate = "cat > \"{path}\" << 'PHONEAGENT_EOF'\n{content}\nPHONEAGENT_EOF\necho \"已写入 {path}\"",
            risk = "high", builtin = true,
        ),
        ToolDefinition(
            name = "file_append",
            description = "追加文本到文件末尾，支持多行",
            parameters = obj("path" to "文件路径", "content" to "要追加的文本内容"),
            handler = "shell", commandTemplate = "cat >> \"{path}\" << 'PHONEAGENT_EOF'\n{content}\nPHONEAGENT_EOF\necho \"已追加到 {path}\"",
            risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "file_grep",
            description = "在文件或目录中搜索文本内容",
            parameters = obj("pattern" to "搜索关键词", "path" to "文件或目录路径"),
            handler = "shell", commandTemplate = "grep -rn \"{pattern}\" \"{path}\" 2>/dev/null | head -50",
            risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "file_tail",
            description = "查看文件末尾指定行数",
            parameters = obj("path" to "文件路径", "lines" to "行数，如50"),
            handler = "shell", commandTemplate = "tail -n {lines} \"{path}\"",
            risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "file_stat",
            description = "查看文件或目录的详细信息（大小/权限/时间）",
            parameters = obj("path" to "文件或目录路径"),
            handler = "shell", commandTemplate = "ls -la \"{path}\"",
            risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "file_du",
            description = "查看文件或目录占用的磁盘空间",
            parameters = obj("path" to "文件或目录路径"),
            handler = "shell", commandTemplate = "du -sh \"{path}\"",
            risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "file_wc",
            description = "统计文件的行数/字数/字节数",
            parameters = obj("path" to "文件路径"),
            handler = "shell", commandTemplate = "wc \"{path}\"",
            risk = "low", builtin = true,
        ),
        // ===== 交付给用户 =====
        ToolDefinition(
            name = "submit_file",
            description = "把你写好的文件提交给用户：文件会以卡片形式出现在聊天里，用户点开就能在应用内预览。" +
                "当你在工作区产出用户需要看的文件（报告、代码、表格、图片、音频、视频、文档）时，写完调用一次。" +
                "不要提交临时文件、缓存或中间结果。同一路径再次提交会记为下一个版本。",
            parameters = obj(
                "path" to "工作区内的文件路径",
                "title" to "可选：一行标题，如「竞品对比结论」",
                "note" to "可选：一句话说明这个文件是什么、或本次改了什么",
            ),
            handler = "api", risk = "low", builtin = true,
        ),
        // ===== 文档处理 =====
        ToolDefinition(
            name = "doc_extract_text",
            description = "从 docx 文档中提取纯文本内容（最多200行，本地解析，不依赖设备是否有 unzip）",
            parameters = obj("path" to "docx 文件路径"),
            handler = "api", risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "file_zip",
            description = "把文件或目录打包成 zip。src 可以是单个文件、一个目录（递归整包），或用英文逗号分隔的多个路径；生成 docx/xlsx/pptx 这类「先写 XML 再打包」的文件时用它可以省掉手工拼 ZIP",
            parameters = obj(
                "src" to "要打包的源，文件/目录/逗号分隔多路径",
                "dest" to "输出 zip 的完整路径，如 /sdcard/Download/工作区/report.zip",
                "level" to "压缩级别 0-9，0 表示只存储不压缩（默认 6）",
            ),
            handler = "api", risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "file_unzip",
            description = "解压 zip 到指定目录（已做路径穿越防护，会自动建目录）",
            parameters = obj(
                "path" to "zip 文件完整路径",
                "dest" to "解压目标目录，不存在会自动创建",
            ),
            handler = "api", risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "file_zip_list",
            description = "列出 zip 内部的条目名与大小（前200条），用来先看清 docx/xlsx/apk 的结构再决定怎么改",
            parameters = obj("path" to "zip 文件完整路径"),
            handler = "api", risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "doc_write",
            description = "直接生成可打开的 Word 文档（.docx）。content 支持「# 一级标题」「## 二级」「### 三级」「- 列表」「1. 编号」「> 引用」「**加粗**」「`代码`」，空行分段。写 Word 一律用它，不要手工拼 XML+ZIP",
            parameters = obj(
                "path" to "输出的 .docx 完整路径，如 /sdcard/Download/工作区/报告.docx",
                "title" to "文档标题（可留空）",
                "content" to "正文文本，用上面那几种简单标记排版",
            ),
            handler = "api", risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "sheet_write",
            description = "直接生成可打开的 Excel 表格（.xlsx）。rows 每行一条记录、单元格用英文竖线 | 分隔，第一行默认当表头加粗；纯数字会自动写成数值",
            parameters = obj(
                "path" to "输出的 .xlsx 完整路径",
                "rows" to "表格数据，多行文本；例如「姓名|分数」换行「张三|95」",
                "sheet" to "工作表名，默认 Sheet1",
                "header" to "第一行是否作为表头，true/false，默认 true",
            ),
            handler = "api", risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "file_base64",
            description = "查看文件的 base64 编码内容（前50行）",
            parameters = obj("path" to "文件路径"),
            handler = "shell", commandTemplate = "base64 \"{path}\" 2>/dev/null | head -50",
            risk = "low", builtin = true,
        ),
        // ===== 网络与HTTP（主进程 OkHttp 执行，不依赖 Android shell 的 curl） =====
        ToolDefinition(
            name = "http_get",
            description = "发送 HTTP GET 请求并返回响应内容（超时15秒）",
            parameters = obj("url" to "请求的URL"),
            handler = "api", risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "http_post",
            description = "发送 HTTP POST JSON 请求并返回响应（超时20秒）",
            parameters = obj("url" to "请求的URL", "body" to "JSON请求体"),
            handler = "api", risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "ping",
            description = "测试网络连通性（ping 4次）",
            parameters = obj("host" to "目标主机或IP"),
            handler = "shell", commandTemplate = "ping -c 4 {host}",
            risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "netstat",
            description = "查看网络连接和监听端口",
            handler = "shell", commandTemplate = "netstat -tlnp 2>/dev/null || ss -tlnp 2>/dev/null",
            risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "dns_lookup",
            description = "DNS 域名解析查询",
            parameters = obj("domain" to "要解析的域名"),
            handler = "api", risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "file_download",
            description = "下载网络文件到手机本地（如 APK、图片、文档），下载完成后可配合 pm_install 安装 APK",
            parameters = obj("url" to "文件的下载直链", "path" to "保存到本地的完整路径，如 /sdcard/Download/app.apk"),
            handler = "api", risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "ocr_image",
            description = "OCR 图片文字识别：识别图片（截图/照片/扫描件）中的文字内容并返回文本，支持中文。图片需是本地完整路径（如 /sdcard/Download/xxx.png），也可配合截屏工具 screencap 使用",
            parameters = obj("path" to "要识别的图片文件完整路径，如 /sdcard/Download/screenshot.png"),
            handler = "api", risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "memory_save",
            description = "把用户希望长期记住的信息写进 App 内置记忆库（跨会话生效，不是文件）。用户说「记住…」「以后记得…」时必须用这个工具，不要写文档/文件",
            parameters = obj(
                "content" to "要记住的一句话，尽量写成陈述句，如「用户喜欢简洁的回复」",
                "weight" to "重要程度 1-5，默认 1，越重要越容易被检索到",
            ),
            handler = "api", risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "web_fetch",
            description = "实时抓取网页正文：自动去掉脚本/样式/HTML标签，返回标题与纯文本正文，适合读新闻、百科、博客、文档页。问题涉及时效信息时用它拿真实内容",
            parameters = obj(
                "url" to "要抓取的网页完整地址，必须以 http:// 或 https:// 开头",
                "max_chars" to "最多返回多少字正文，默认 12000，可不填",
            ),
            handler = "api", risk = "low", builtin = true,
        ),
        ToolDefinition(
            name = "image_describe",
            description = "看图理解：把本地图片交给当前模型看图并描述内容、读出图中所有文字（比 ocr_image 更全面，能理解画面而非只认字）。需要当前模型支持图片输入",
            parameters = obj(
                "path" to "本地图片完整路径，如 /sdcard/Download/a.jpg",
                "question" to "想问的关于这张图的问题；留空则描述画面并读文字",
            ),
            handler = "api", risk = "low", builtin = true,
        ),
        // ===== 系统设置 / 后台保活 =====
        ToolDefinition(
            name = "set_system_setting",
            description = "修改系统设置。需授予「修改系统设置」权限",
            parameters = obj(
                "key" to "设置项：brightness(0-255)/screen_timeout(毫秒)/auto_rotate(true/false)",
                "value" to "目标值",
            ),
            handler = "api", risk = "medium", builtin = true,
        ),
        ToolDefinition(
            name = "set_keepalive",
            description = "开启/关闭后台保活（前台服务常驻），用于长时间连续执行任务",
            parameters = obj("enable" to "true 开启保活，false 关闭"),
            handler = "api", risk = "low", builtin = true,
        ),
    )

    /** 简易参数描述工具函数（生成 JSON Schema 骨架）。 */
    private fun obj(vararg pairs: Pair<String, String>): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                pairs.forEach { (k, v) ->
                    put(k, buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive(v))
                    })
                }
            })
            put("required", buildJsonArray {
                pairs.forEach { (k, _) -> add(JsonPrimitive(k)) }
            })
        }

    private fun ToolDefinition.toEntity() = ToolEntity(
        id = name,
        name = name,
        description = description,
        parameters = parameters.toString(),
        handler = handler,
        commandTemplate = commandTemplate,
        risk = risk,
        enabled = enabled,
        builtin = builtin,
    )

    private fun ToolEntity.toDefinition() = ToolDefinition(
        name = name,
        description = description,
        parameters = try {
            kotlinx.serialization.json.Json.parseToJsonElement(parameters).jsonObject
        } catch (_: Exception) { JsonObject(emptyMap()) },
        handler = handler,
        commandTemplate = commandTemplate,
        risk = risk,
        enabled = enabled,
        builtin = builtin,
    )
}
