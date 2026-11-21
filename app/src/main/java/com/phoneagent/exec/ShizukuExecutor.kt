package com.phoneagent.exec

import com.phoneagent.tools.ParsedCommand
import com.phoneagent.tools.ToolHandler
import com.phoneagent.tools.ToolParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Shizuku 执行器：通过 [ShizukuManager] 持有的 Shell 服务（运行在 Shizuku 进程）执行命令。
 * API 类工具（HTTP/DNS）在主进程直接执行，不依赖 shell。
 * 状态与授权见 [ShizukuManager.status]，UI 据此引导用户激活/授权。
 */
class ShizukuExecutor(
    private val manager: ShizukuManager,
    private val parser: ToolParser,
    private val downloadDir: java.io.File,
    private val context: android.content.Context,
    private val memoryRepo: com.phoneagent.memory.MemoryRepository,
    private val settings: com.phoneagent.data.SettingsRepository,
) : CommandExecutor {

    override val isReady: Boolean
        get() = RootShell.available == true || manager.status.value == ShizukuStatus.READY

    /** 下载进度回调（percent 0-100，bytes 已下载字节）。由 AgentController 在执行 file_download 时注入。 */
    @Volatile
    override var onProgress: ((percent: Int, bytes: Long) -> Unit)? = null

    /** HTTP 客户端（主进程直接用，不依赖 Android shell 的 curl）。下载单独用长超时客户端。 */
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** 模拟浏览器的常见请求头，避免部分 API 因 UA 缺失拒绝连接。 */
    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to "*/*",
        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
    )

    override suspend fun execute(command: ParsedCommand): ExecResult = withContext(Dispatchers.IO) {
        when (ToolHandler.valueOf(command.tool.handler.uppercase())) {
            ToolHandler.SHELL -> {
                if (!isReady) {
                    return@withContext ExecResult(
                        ok = false, output = "",
                        error = "无执行权限：请开启 Root（授权管理器允许本应用）或在设置页完成 Shizuku 授权",
                    )
                }
                execShell(parser.renderShell(command.tool, command.args))
            }
            ToolHandler.API -> execApi(command)
            ToolHandler.UI -> ExecResult(
                ok = false, output = "",
                error = "界面操控已下线：本版本移除了无障碍点击/滑动能力，请用文件、网络或看图类工具完成任务",
            )
            ToolHandler.COMPOSE -> ExecResult(ok = false, output = "", error = "compose 工具暂未实现")
        }
    }

    /**
     * submit_file：把工作区里的文件「提交」给用户。
     * 不复制文件（省空间），只回传路径与元信息，
     * AgentController 据此记一条记录，聊天里出现可点开的卡片。
     */
    private suspend fun submitFile(command: ParsedCommand): ExecResult = withContext(Dispatchers.IO) {
        val rawPath = command.args["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (rawPath.isBlank()) return@withContext ExecResult(false, "", "缺少 path 参数")
        val src = java.io.File(rawPath)
        if (!src.exists()) return@withContext ExecResult(false, "", "文件不存在：$rawPath")
        if (src.isDirectory) return@withContext ExecResult(false, "", "只能提交单个文件，不能提交目录：$rawPath")
        if (!src.canRead()) return@withContext ExecResult(false, "", "文件不可读：$rawPath")
        // 交付前体检：0 字节、构建中间件、结构坏掉的 Office 一律拒收，别让半成品变成提交卡
        OfficeCheck.issue(src)?.let { return@withContext ExecResult(false, "", it) }

        val name = src.name
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(ext) ?: "application/octet-stream"

        // 路径原样返回：预览时直接读这个文件，不占额外空间
        ExecResult(
            ok = true,
            output = "SUBMIT|${src.absolutePath}|${src.length()}|$mime|$name|${src.absolutePath}",
        )
    }

    private fun execShell(cmd: String): ExecResult {
        // 1) 优先 Root 通道（Magisk / KernelSU / APatch）
        if (RootShell.ensureDetected()) {
            val raw = RootShell.exec(cmd)
            return if (raw.startsWith("ERROR(") || raw.startsWith("EXCEPTION:")) {
                ExecResult(ok = false, output = "", error = raw)
            } else {
                ExecResult(ok = true, output = raw.ifBlank { "(执行成功，无输出)" })
            }
        }
        // 2) 回退 Shizuku
        val svc = manager.shellService
            ?: return ExecResult(ok = false, output = "", error = "无 Root 且 Shizuku 未连接（请到设置页授权其一）")
        val raw = svc.exec(cmd)
        return if (raw.startsWith("ERROR(") || raw.startsWith("EXCEPTION:")) {
            ExecResult(ok = false, output = "", error = raw)
        } else {
            ExecResult(ok = true, output = raw.trim())
        }
    }

    /** 原生 API 工具：HTTP 请求、DNS 解析、OCR 识别等（无需 shell 权限，主进程直接执行）。 */
    private suspend fun execApi(command: ParsedCommand): ExecResult {
        return when (command.tool.name) {
            "http_get" -> httpGet(command)
            "http_post" -> httpPost(command)
            "dns_lookup" -> dnsLookup(command)
            "file_download" -> fileDownload(command)
            "ocr_image" -> ocrImage(command)
            "web_fetch" -> webFetch(command)
            "submit_file" -> submitFile(command)
            "image_describe" -> imageDescribe(command)
            "memory_save" -> memorySave(command)
            "doc_extract_text" -> docExtractText(command)
            "doc_write" -> docWrite(command)
            "sheet_write" -> sheetWrite(command)
            "ppt_write" -> pptWrite(command)
            "sheet_read" -> sheetRead(command)
            "file_zip" -> zipCreate(command)
            "file_unzip" -> zipExtract(command)
            "file_zip_list" -> zipList(command)
            "set_system_setting" -> setSystemSetting(command)
            "set_keepalive" -> setKeepAlive(command)
            else -> ExecResult(ok = false, output = "", error = "API 工具 ${command.tool.name} 尚未实现")
        }
    }

    /** 修改系统设置（需要 WRITE_SETTINGS 权限）。 */
    private fun setSystemSetting(command: ParsedCommand): ExecResult {
        val key = command.args["key"]?.jsonPrimitive?.contentOrNull
            ?: return ExecResult(false, "", "缺少 key 参数")
        val value = command.args["value"]?.jsonPrimitive?.contentOrNull
            ?: return ExecResult(false, "", "缺少 value 参数")
        if (!android.provider.Settings.System.canWrite(context)) {
            return ExecResult(
                false, "",
                "缺少修改系统设置权限：请到「设置 → Phone Use → 修改系统设置」授权",
            )
        }
        return try {
            when (key.lowercase()) {
                "brightness" -> {
                    val v = value.toIntOrNull()?.coerceIn(0, 255)
                        ?: return ExecResult(false, "", "brightness 需要 0-255 的整数")
                    android.provider.Settings.System.putInt(
                        context.contentResolver,
                        android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                        android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                    )
                    android.provider.Settings.System.putInt(
                        context.contentResolver,
                        android.provider.Settings.System.SCREEN_BRIGHTNESS,
                        v,
                    )
                    ExecResult(true, "已设置屏幕亮度为 $v/255")
                }
                "screen_timeout" -> {
                    val v = value.toIntOrNull()
                        ?: return ExecResult(false, "", "screen_timeout 需要毫秒整数")
                    android.provider.Settings.System.putInt(
                        context.contentResolver,
                        android.provider.Settings.System.SCREEN_OFF_TIMEOUT,
                        v,
                    )
                    ExecResult(true, "已设置息屏时间为 ${v / 1000} 秒")
                }
                "auto_rotate" -> {
                    val on = value.equals("true", true) || value == "1" || value == "on"
                    android.provider.Settings.System.putInt(
                        context.contentResolver,
                        android.provider.Settings.System.ACCELEROMETER_ROTATION,
                        if (on) 1 else 0,
                    )
                    ExecResult(true, "已${if (on) "开启" else "关闭"}自动旋转")
                }
                else -> ExecResult(
                    false, "",
                    "不支持的 key：$key（可用 brightness / screen_timeout / auto_rotate）",
                )
            }
        } catch (e: Exception) {
            ExecResult(false, "", "修改系统设置失败: ${e.message}")
        }
    }

    /** 后台保活开关。 */
    private fun setKeepAlive(command: ParsedCommand): ExecResult {
        val enable = command.args["enable"]?.jsonPrimitive?.contentOrNull
            ?.let { it.equals("true", true) || it == "1" }
            ?: return ExecResult(false, "", "缺少 enable 参数（true/false）")
        return try {
            if (enable) {
                com.phoneagent.service.KeepAliveService.start(context)
                ExecResult(true, "已开启后台保活（前台服务运行中）")
            } else {
                com.phoneagent.service.KeepAliveService.stop(context)
                ExecResult(true, "已关闭后台保活")
            }
        } catch (e: Exception) {
            ExecResult(false, "", "切换保活失败: ${e.message}")
        }
    }

    private fun httpGet(command: ParsedCommand): ExecResult {
        val url = command.args["url"]?.jsonPrimitive?.contentOrNull
            ?: return ExecResult(ok = false, output = "", error = "缺少 url 参数")
        return try {
            val request = Request.Builder().url(url).apply {
                browserHeaders.forEach { (k, v) -> header(k, v) }
            }.build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                ExecResult(
                    ok = response.isSuccessful,
                    output = body.take(50000),
                    error = if (!response.isSuccessful) "HTTP ${response.code}" else null,
                )
            }
        } catch (e: Exception) {
            ExecResult(ok = false, output = "", error = "HTTP 请求失败: ${e.message}")
        }
    }

    private fun httpPost(command: ParsedCommand): ExecResult {
        val url = command.args["url"]?.jsonPrimitive?.contentOrNull
            ?: return ExecResult(ok = false, output = "", error = "缺少 url 参数")
        val body = command.args["body"]?.jsonPrimitive?.contentOrNull ?: "{}"
        return try {
            val requestBody = body.toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(url).post(requestBody).apply {
                browserHeaders.forEach { (k, v) -> header(k, v) }
            }.build()
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                ExecResult(
                    ok = response.isSuccessful,
                    output = responseBody.take(50000),
                    error = if (!response.isSuccessful) "HTTP ${response.code}" else null,
                )
            }
        } catch (e: Exception) {
            ExecResult(ok = false, output = "", error = "HTTP 请求失败: ${e.message}")
        }
    }


    /** 写入 App 内置记忆库（不是手机文件）。 */
    private suspend fun memorySave(command: ParsedCommand): ExecResult {
        val raw = command.args["content"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ExecResult(ok = false, output = "", error = "缺少 content 参数")
        if (raw.isBlank()) return ExecResult(ok = false, output = "", error = "content 不能为空")
        if (!settings.memoryEnabled) {
            return ExecResult(
                ok = false, output = "",
                error = "记忆功能已关闭：请到「设置 → 记忆 · 关于你」里打开长期记忆后再写入",
            )
        }
        // 环境探测结论是易变事实，写进长期记忆后会在别的会话里被当成"已确认"抛出，
        // 典型症状就是新对话开口就说"我之前确认过设备没有 Python"。这类内容一律拒写。
        val volatileEnv = VOLATILE_ENV_WORDS.any { raw.contains(it, ignoreCase = true) } &&
            VOLATILE_VERDICT_WORDS.any { raw.contains(it, ignoreCase = true) }
        if (volatileEnv) {
            return ExecResult(
                ok = false, output = "",
                error = "这类内容属于环境探测结论，会随版本/组件变化，不要写入长期记忆；" +
                    "需要时在本轮重新执行工具确认即可",
            )
        }
        val weight = command.args["weight"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 5) ?: 1
        return try {
            val clean = memoryRepo.sanitize(raw).take(500)
            memoryRepo.add(clean, category = "user", weight = weight)
            ExecResult(ok = true, output = "已写入 App 记忆库（权重 $weight）：" + clean.take(80))
        } catch (e: Exception) {
            ExecResult(ok = false, output = "", error = "写入记忆失败：" + e.message)
        }
    }

    /**
     * docx 取文本：本地读 word/document.xml，剥标签。设备上没有 unzip 也能用。
     */
    private suspend fun docExtractText(command: ParsedCommand): ExecResult = withContext(Dispatchers.IO) {
        val raw = command.args["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (raw.isBlank()) return@withContext ExecResult(false, "", "缺少 path 参数")
        val file = java.io.File(raw)
        if (!file.exists()) return@withContext ExecResult(false, "", "文件不存在：" + raw)
        runCatching {
            java.util.zip.ZipFile(file).use { zip ->
                val entry = zip.getEntry("word/document.xml")
                    ?: return@withContext ExecResult(
                        false, "",
                        "不是 docx 结构：缺少 word/document.xml（旧版 .doc 需要先另存为 docx）",
                    )
                val xml = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                val plain = xml
                    .replace("</w:p>", "\n")
                    .replace(Regex("<[^>]+>"), "")
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace(Regex("\n{3,}"), "\n\n")
                    .trim()
                val text = plain.lines().take(200).joinToString("\n")
                ExecResult(true, text.ifBlank { "(空文档)" })
            }
        }.getOrElse { ExecResult(false, "", "解析失败：" + (it.message ?: it.javaClass.simpleName)) }
    }

    /**
     * 打包 zip。src 支持文件/目录/逗号分隔多路径；level 0 = 只存储（最快、最省电），
     * 生成 docx/xlsx/pptx 时用 0 就够，模型不必再手工拼 ZIP。
     */
    private suspend fun zipCreate(command: ParsedCommand): ExecResult = withContext(Dispatchers.IO) {
        val src = command.args["src"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val dest = command.args["dest"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (src.isBlank()) return@withContext ExecResult(false, "", "缺少 src 参数")
        if (dest.isBlank()) return@withContext ExecResult(false, "", "缺少 dest 参数")
        val level = command.args["level"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(0, 9) ?: 6
        val roots = src.split(',').map { it.trim().trim('"') }.filter { it.isNotBlank() }.map { java.io.File(it) }
        if (roots.isEmpty()) return@withContext ExecResult(false, "", "src 为空")
        val missing = roots.filter { !it.exists() }
        if (missing.isNotEmpty()) {
            return@withContext ExecResult(false, "", "找不到：" + missing.joinToString(", ") { it.path })
        }
        val destFile = java.io.File(dest)
        destFile.parentFile?.mkdirs()
        val destCanonical = runCatching { destFile.canonicalPath }.getOrDefault(destFile.absolutePath)
        var count = 0
        var bytes = 0L
        runCatching {
            java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(destFile))).use { zos ->
                zos.setLevel(level)
                // 只传一个目录时，把目录「里面的东西」放到压缩包根目录：
                // docx/xlsx/pptx 要求 [Content_Types].xml 直接在根，多套一层目录名就会变成"损坏文件"。
                val stripRootName = roots.size == 1 && roots[0].isDirectory
                fun put(file: java.io.File, relative: String) {
                    if (runCatching { file.canonicalPath }.getOrDefault(file.absolutePath) == destCanonical) return
                    if (file.isDirectory) {
                        file.listFiles()?.sortedBy { it.name }?.forEach { child ->
                            val rel = if (relative.isEmpty()) child.name else relative + "/" + child.name
                            put(child, rel)
                        }
                    } else {
                        if (relative.isEmpty()) return
                        val entryName = relative.trimStart('/')
                        if (entryName.isBlank()) return
                        zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                        count++
                        bytes += file.length()
                    }
                }
                roots.forEach { root ->
                    if (stripRootName) put(root, "") else put(root, root.name)
                }
            }
        }.getOrElse {
            runCatching { destFile.delete() }
            return@withContext ExecResult(false, "", "打包失败：" + (it.message ?: it.javaClass.simpleName))
        }
        if (count == 0) return@withContext ExecResult(false, "", "源里没有可打包的文件")
        // 打成 Office 文件时顺便体检：部件不在根目录直接报错，别交出打不开的文件
        val ext = destFile.name.substringAfterLast('.', "").lowercase()
        if (ext == "docx" || ext == "xlsx" || ext == "pptx") {
            val required = when (ext) {
                "docx" -> listOf("[Content_Types].xml", "word/document.xml")
                "xlsx" -> listOf("[Content_Types].xml", "xl/workbook.xml")
                else -> listOf("[Content_Types].xml", "ppt/presentation.xml")
            }
            val missing = runCatching {
                java.util.zip.ZipFile(destFile).use { z -> required.filter { z.getEntry(it) == null } }
            }.getOrDefault(required)
            if (missing.isNotEmpty()) {
                return@withContext ExecResult(
                    false, "",
                    "打包层级不对：" + missing.joinToString("、") + " 不在压缩包根目录。" +
                        "Office 文件要求这些部件直接在根；src 请传构建目录本身（例如 work/build_docx），不要传它的父目录。",
                )
            }
        }
        val sizeMb = String.format("%.2f", destFile.length() / 1024.0 / 1024.0)
        ExecResult(true, "已打包 " + count + " 个文件 → " + destFile.absolutePath + "（" + sizeMb + " MB，压缩级别 " + level + "）")
    }

    /** 解压 zip，带路径穿越防护（拒绝 .. 与绝对路径条目）。 */
    private suspend fun zipExtract(command: ParsedCommand): ExecResult = withContext(Dispatchers.IO) {
        val raw = command.args["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val dest = command.args["dest"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (raw.isBlank()) return@withContext ExecResult(false, "", "缺少 path 参数")
        if (dest.isBlank()) return@withContext ExecResult(false, "", "缺少 dest 参数")
        val file = java.io.File(raw)
        if (!file.exists()) return@withContext ExecResult(false, "", "文件不存在：" + raw)
        val destDir = java.io.File(dest)
        destDir.mkdirs()
        val destPrefix = runCatching { destDir.canonicalPath + java.io.File.separator }
            .getOrDefault(destDir.absolutePath + "/")
        var count = 0
        var badEntry: String? = null
        runCatching {
            java.util.zip.ZipFile(file).use { zip ->
                for (entry in zip.entries()) {
                    val name = entry.name
                    if (name.contains("..") || name.startsWith("/") || name.startsWith("\\")) {
                        badEntry = name
                        break
                    }
                    val out = java.io.File(destDir, name)
                    val canonical = runCatching { out.canonicalPath }.getOrNull()
                        ?: (destDir.absolutePath + "/" + name)
                    if (!canonical.startsWith(destPrefix)) {
                        badEntry = name
                        break
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            out.outputStream().use { output -> input.copyTo(output) }
                        }
                        count++
                    }
                }
            }
        }.getOrElse { return@withContext ExecResult(false, "", "解压失败：" + (it.message ?: it.javaClass.simpleName)) }
        badEntry?.let { return@withContext ExecResult(false, "", "压缩包里有危险路径，已中止：" + it) }
        ExecResult(true, "已解压 " + count + " 个文件到 " + destDir.absolutePath)
    }

    /** 列出 zip 条目，先看清结构再改（改 docx/xlsx 必备）。 */
    private suspend fun zipList(command: ParsedCommand): ExecResult = withContext(Dispatchers.IO) {
        val raw = command.args["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (raw.isBlank()) return@withContext ExecResult(false, "", "缺少 path 参数")
        val file = java.io.File(raw)
        if (!file.exists()) return@withContext ExecResult(false, "", "文件不存在：" + raw)
        runCatching {
            java.util.zip.ZipFile(file).use { zip ->
                val lines = zip.entries().asSequence().take(200).map { e ->
                    (if (e.isDirectory) "DIR  " else "     ") + e.name + "  " + e.size + "B"
                }.toList()
                ExecResult(true, "共 " + zip.size() + " 个条目：\n" + lines.joinToString("\n"))
            }
        }.getOrElse { ExecResult(false, "", "读取失败：" + (it.message ?: it.javaClass.simpleName)) }
    }


    /** doc_write：直接用原生 OOXML 写 docx，模型不用再手工拼 ZIP。 */
    private suspend fun docWrite(command: ParsedCommand): ExecResult = withContext(Dispatchers.IO) {
        val path = command.args["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val title = command.args["title"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        val content = command.args["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (path.isBlank()) return@withContext ExecResult(false, "", "缺少 path 参数（要写出的 .docx 完整路径）")
        if (!path.lowercase().endsWith(".docx")) {
            return@withContext ExecResult(false, "", "path 必须以 .docx 结尾：$path")
        }
        if (content.isBlank() && title.isBlank()) {
            return@withContext ExecResult(false, "", "title 和 content 都是空的，没有内容可写")
        }
        val (ok, message) = OfficeWriter.writeDocx(java.io.File(path), title, content)
        if (ok) ExecResult(true, message) else ExecResult(false, "", message)
    }

    /** sheet_write：把「每行一条、单元格用 | 分隔」的文本写成真正的 xlsx。 */
    private suspend fun sheetWrite(command: ParsedCommand): ExecResult = withContext(Dispatchers.IO) {
        val path = command.args["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val sheet = command.args["sheet"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val rows = command.args["rows"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val header = command.args["header"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() != "false"
        if (path.isBlank()) return@withContext ExecResult(false, "", "缺少 path 参数（要写出的 .xlsx 完整路径）")
        if (!path.lowercase().endsWith(".xlsx")) {
            return@withContext ExecResult(false, "", "path 必须以 .xlsx 结尾：$path")
        }
        if (rows.isBlank()) return@withContext ExecResult(false, "", "rows 是空的：每行一条记录，单元格之间用 | 分隔")
        val (ok, message) = OfficeWriter.writeXlsx(java.io.File(path), sheet, rows, header)
        if (ok) ExecResult(true, message) else ExecResult(false, "", message)
    }


    /** ppt_write：一页标题 + 若干要点，每页用一行 --- 分隔。 */
    private suspend fun pptWrite(command: ParsedCommand): ExecResult = withContext(Dispatchers.IO) {
        val path = command.args["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val slides = command.args["slides"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (path.isBlank()) return@withContext ExecResult(false, "", "缺少 path 参数（要写出的 .pptx 完整路径）")
        if (!path.lowercase().endsWith(".pptx")) {
            return@withContext ExecResult(false, "", "path 必须以 .pptx 结尾：$path")
        }
        if (slides.isBlank()) return@withContext ExecResult(false, "", "slides 是空的")
        val (ok, message) = PptxWriter.write(java.io.File(path), slides)
        if (ok) ExecResult(true, message) else ExecResult(false, "", message)
    }

    /** sheet_read：把 xlsx 读成文本表格（支持共享字符串，Excel/WPS 生成的文件也能读）。 */
    private suspend fun sheetRead(command: ParsedCommand): ExecResult = withContext(Dispatchers.IO) {
        val path = command.args["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val sheet = command.args["sheet"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (path.isBlank()) return@withContext ExecResult(false, "", "缺少 path 参数")
        val (ok, message) = OfficeReader.readSheet(java.io.File(path), sheet)
        if (ok) ExecResult(true, message) else ExecResult(false, "", message)
    }

    /** 读取当前激活的模型配置（多模态看图需要）。 */
    private fun activeModelProfile(): com.phoneagent.data.ModelProfile? {
        val prefs = context.getSharedPreferences("phone_agent_models", android.content.Context.MODE_PRIVATE)
        val raw = prefs.getString("model_list", null)
        if (raw.isNullOrBlank()) return null
        val list = runCatching {
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .decodeFromString<List<com.phoneagent.data.ModelProfile>>(raw)
        }.getOrNull() ?: return null
        val activeId = prefs.getString("active_model", null)
        return list.firstOrNull { it.id == activeId } ?: list.firstOrNull()
    }

    /** 抓网页正文：去脚本/样式/标签，输出标题 + 正文纯文本。 */
    private fun webFetch(command: ParsedCommand): ExecResult {
        val url = command.args["url"]?.jsonPrimitive?.contentOrNull
            ?: return ExecResult(ok = false, output = "", error = "缺少 url 参数")
        val maxChars = command.args["max_chars"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 12000
        return try {
            val request = Request.Builder().url(url).apply {
                browserHeaders.forEach { (k, v) -> header(k, v) }
                header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
            }.build()
            httpClient.newCall(request).execute().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                if (!response.isSuccessful) {
                    return ExecResult(ok = false, output = "", error = "抓取失败：HTTP ${response.code}")
                }
                val contentType = response.header("Content-Type") ?: ""
                val charset = Regex("charset=[\"']?([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE)
                    .find(contentType)?.groupValues?.get(1)
                    ?: Regex("<meta[^>]+charset=[\"']?([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE)
                        .find(String(bytes, Charsets.UTF_8))?.groupValues?.get(1)
                    ?: "UTF-8"
                val raw = runCatching { String(bytes, charset(charset)) }.getOrElse { String(bytes, Charsets.UTF_8) }

                val isHtml = contentType.contains("html", ignoreCase = true) ||
                    raw.trimStart().startsWith("<")
                val title: String
                val body: String
                if (isHtml) {
                    title = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)
                        .find(raw)?.groupValues?.get(1)?.let { cleanText(it) } ?: ""
                    body = cleanText(stripHtml(raw))
                } else {
                    title = ""
                    body = raw.trim()
                }
                val trimmed = if (body.length > maxChars) body.take(maxChars) + "\n…（正文过长已截断）" else body
                val out = buildString {
                    if (title.isNotBlank()) append("### ").append(title).append("\n")
                    append("来源：").append(url).append("\n\n")
                    append(trimmed.ifBlank { "(没有提取到正文内容)" })
                }
                ExecResult(ok = true, output = out)
            }
        } catch (e: Exception) {
            ExecResult(ok = false, output = "", error = "抓取失败：${e.message}")
        }
    }

    private fun charset(name: String): java.nio.charset.Charset = runCatching {
        java.nio.charset.Charset.forName(name.trim())
    }.getOrElse { Charsets.UTF_8 }

    private fun stripHtml(html: String): String = html
        .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<noscript[^>]*>[\\s\\S]*?</noscript>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<!--[\\s\\S]*?-->"), " ")
        .replace(Regex("<(br|/p|/div|/li|/tr|/h\\d)[^>]*>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), " ")

    private fun cleanText(text: String): String = text
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .lines().joinToString("\n") { it.trim() }.trim()


    /** 看图：压缩图片 → base64 → 调用当前模型的多模态接口。 */
    private fun imageDescribe(command: ParsedCommand): ExecResult {
        val path = command.args["path"]?.jsonPrimitive?.contentOrNull
            ?: return ExecResult(ok = false, output = "", error = "缺少 path 参数")
        val question = command.args["question"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: "请详细描述这张图片的内容，并完整读出图中出现的所有文字。"
        val profile = activeModelProfile()
            ?: return ExecResult(
                ok = false, output = "",
                error = "还没有配置模型：请到「设置 → 模型与接入」添加一个支持图片输入的多模态模型（如 GPT-4o / Qwen-VL / GLM-4V）",
            )
        return try {
            val file = java.io.File(path)
            if (!file.exists()) return ExecResult(ok = false, output = "", error = "图片不存在：$path")
            val bitmap = android.graphics.BitmapFactory.decodeFile(path)
                ?: return ExecResult(
                    ok = false, output = "",
                    error = "无法读取该图片（路径可能在受限目录）：请先复制到 /sdcard/Download 再试",
                )
            val maxSide = 1024
            val scale = minOf(1f, maxSide.toFloat() / maxOf(bitmap.width, bitmap.height))
            val scaled = if (scale < 1f) {
                android.graphics.Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                bitmap
            }
            val bos = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, bos)
            val b64 = android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
            val dataUrl = "data:image/jpeg;base64,$b64"

            val payload = kotlinx.serialization.json.buildJsonObject {
                put("model", profile.model)
                put("stream", false)
                put("max_tokens", 1500)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "system")
                        put("content", "你是严谨的看图助手：先描述画面主体、场景与关键细节，再逐条列出图中文字，不要编造看不清的内容。")
                    }
                    addJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            addJsonObject {
                                put("type", "text")
                                put("text", question)
                            }
                            addJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") { put("url", dataUrl) }
                            }
                        }
                    }
                }
            }
            val endpoint = if (profile.baseUrl.contains("/chat/completions")) {
                profile.baseUrl.trimEnd('/')
            } else {
                profile.baseUrl.trimEnd('/') + "/chat/completions"
            }
            val req = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer " + profile.apiKey)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            downloadClient.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    val hint = if (resp.code == 400 || resp.code == 404) {
                        "（当前模型可能不支持图片输入，换一个多模态模型试试）"
                    } else ""
                    return ExecResult(ok = false, output = "", error = "看图失败：HTTP " + resp.code + hint + " " + text.take(300))
                }
                val content = runCatching {
                    val root = kotlinx.serialization.json.Json.parseToJsonElement(text)
                        .let { it as kotlinx.serialization.json.JsonObject }
                    val msg = (root["choices"] as kotlinx.serialization.json.JsonArray)[0]
                        .let { it as kotlinx.serialization.json.JsonObject }["message"]
                        .let { it as kotlinx.serialization.json.JsonObject }
                    when (val c = msg["content"]) {
                        is kotlinx.serialization.json.JsonPrimitive -> c.content
                        is kotlinx.serialization.json.JsonArray -> c.joinToString("\n") {
                            ((it as kotlinx.serialization.json.JsonObject)["text"]
                                as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                        }
                        else -> ""
                    }
                }.getOrElse { "" }
                if (content.isBlank()) {
                    ExecResult(ok = false, output = "", error = "看图没有返回内容：" + text.take(300))
                } else {
                    ExecResult(ok = true, output = content)
                }
            }
        } catch (e: Exception) {
            ExecResult(ok = false, output = "", error = "看图失败：" + e.message)
        }
    }

    private fun dnsLookup(command: ParsedCommand): ExecResult {
        val domain = command.args["domain"]?.jsonPrimitive?.contentOrNull
            ?: return ExecResult(ok = false, output = "", error = "缺少 domain 参数")
        return try {
            val addresses = java.net.InetAddress.getAllByName(domain)
            val result = addresses.joinToString("\n") { "${it.hostName} -> ${it.hostAddress}" }
            ExecResult(ok = true, output = result)
        } catch (e: Exception) {
            ExecResult(ok = false, output = "", error = "DNS 解析失败: ${e.message}")
        }
    }

    /**
     * 下载文件：先流式写入 App 缓存目录（无需存储权限），
     * 再通过 Shizuku shell 的 cp 复制到用户指定的目标路径（system 身份可写 /sdcard）。
     * 下载过程中实时回调进度（onProgress），供 UI 显示"正在下载 xx%"。
     */
    private fun fileDownload(command: ParsedCommand): ExecResult {
        val url = command.args["url"]?.jsonPrimitive?.contentOrNull
            ?: return ExecResult(ok = false, output = "", error = "缺少 url 参数")
        val target = command.args["path"]?.jsonPrimitive?.contentOrNull
            ?: return ExecResult(ok = false, output = "", error = "缺少 path 参数")
        return try {
            val fileName = target.substringAfterLast('/').ifBlank { "download_${System.currentTimeMillis()}" }
            if (!downloadDir.exists()) downloadDir.mkdirs()
            val tmpFile = java.io.File(downloadDir, fileName)

            val request = Request.Builder().url(url).apply {
                browserHeaders.forEach { (k, v) -> header(k, v) }
            }.build()
            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return ExecResult(ok = false, output = "", error = "下载失败: HTTP ${response.code}")
                }
                val body = response.body ?: return ExecResult(ok = false, output = "", error = "响应体为空")
                val totalBytes = body.contentLength().let { if (it > 0) it else -1 }
                java.io.FileOutputStream(tmpFile).use { output ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(8192)
                        var written = 0L
                        var lastReport = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            written += n
                            // 每 256KB 或末尾回调一次进度，避免高频回调
                            if (written - lastReport >= 262144 || n < 0) {
                                lastReport = written
                                val percent = if (totalBytes > 0) ((written * 100) / totalBytes).toInt() else -1
                                onProgress?.invoke(percent, written)
                            }
                        }
                    }
                }
            }
            val sizeKb = tmpFile.length() / 1024
            onProgress?.invoke(100, tmpFile.length())

            // 通过 Shizuku shell 复制到目标路径（创建父目录）
            val svc = manager.shellService
            return if (svc != null) {
                val targetDir = target.substringBeforeLast('/', "")
                val cpCmd = buildString {
                    if (targetDir.isNotEmpty()) append("mkdir -p \"$targetDir\" && ")
                    append("cp \"${tmpFile.absolutePath}\" \"$target\" && chmod 644 \"$target\"")
                }
                val cpResult = svc.exec(cpCmd)
                if (cpResult.startsWith("ERROR(") || cpResult.startsWith("EXCEPTION:")) {
                    ExecResult(ok = false, output = "", error = "保存到目标路径失败: $cpResult")
                } else {
                    ExecResult(ok = true, output = "已下载到 $target（${sizeKb} KB）")
                }
            } else {
                // 无 Shizuku 时直接返回缓存路径
                ExecResult(ok = true, output = "已下载到 ${tmpFile.absolutePath}（${sizeKb} KB）。注：未激活 Shizuku，无法复制到 $target")
            }
        } catch (e: Exception) {
            ExecResult(ok = false, output = "", error = "下载失败: ${e.message}")
        }
    }

    /**
     * OCR 图片文字识别（ML Kit 本地离线，中文+拉丁）。
     *
     * 读取策略：优先直接解码原图；无权限时经 Root/Shizuku 复制到**外部缓存目录**
     * （/storage/emulated/0/Android/data/<pkg>/cache，shell 可写、App 可读），
     * 避免复制到 App 私有 cache —— 后者 shell 身份无写权限，必然 Permission denied。
     */
    private fun ocrImage(command: ParsedCommand): ExecResult {
        val path = command.args["path"]?.jsonPrimitive?.contentOrNull
            ?: return ExecResult(ok = false, output = "", error = "缺少 path 参数")
        return try {
            val srcFile = java.io.File(path)
            if (!srcFile.exists()) {
                return ExecResult(ok = false, output = "", error = "图片不存在：$path")
            }
            var tmpFile: java.io.File? = null
            var bitmap = android.graphics.BitmapFactory.decodeFile(path)

            if (bitmap == null) {
                val cacheDir = context.externalCacheDir ?: java.io.File(context.cacheDir, "ocr")
                if (!cacheDir.exists()) cacheDir.mkdirs()
                val tmp = java.io.File(cacheDir, "ocr_${System.currentTimeMillis()}.img")
                tmpFile = tmp

                val copyOutcome: String = when {
                    RootShell.ensureDetected() ->
                        RootShell.exec("cp \"$path\" \"${tmp.absolutePath}\"")
                    manager.shellService != null ->
                        manager.shellService!!.exec("cp \"$path\" \"${tmp.absolutePath}\"")
                    else -> try {
                        srcFile.inputStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                        ""
                    } catch (e: Exception) {
                        tmp.delete()
                        return ExecResult(false, "", "读取图片失败（需 Root 或 Shizuku 授权）: ${e.message}")
                    }
                }
                if (copyOutcome.startsWith("ERROR(") || copyOutcome.startsWith("EXCEPTION:")) {
                    tmp.delete()
                    return ExecResult(false, "", "读取图片失败：$copyOutcome")
                }
                if (!tmp.exists() || tmp.length() == 0L) {
                    tmp.delete()
                    return ExecResult(
                        false, "",
                        "读取图片失败：复制后文件为空（请确认图片路径正确、且已授予 Root 或 Shizuku）",
                    )
                }
                bitmap = android.graphics.BitmapFactory.decodeFile(tmp.absolutePath)
            }

            if (bitmap == null) {
                tmpFile?.delete()
                return ExecResult(
                    false, "",
                    "无法解码图片：$path（文件可能已损坏或不是图片格式）",
                )
            }

            // ML Kit 中文+拉丁识别（process 返回 Task，用 Tasks.await 阻塞等待结果）
            val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
                com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions.Builder().build()
            )
            val image = com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)
            val result = com.google.android.gms.tasks.Tasks.await(recognizer.process(image))
            val text = result.text.trim()
            bitmap.recycle()
            tmpFile?.delete()

            if (text.isBlank()) {
                ExecResult(ok = true, output = "未在图片中识别到文字（图片可能没有文字或过于模糊）")
            } else {
                ExecResult(ok = true, output = "识别结果（${text.length} 字）：\n$text")
            }
        } catch (e: Exception) {
            ExecResult(ok = false, output = "", error = "OCR 识别失败: ${e.message}")
        }
    }

    private companion object {
        /** 环境相关名词。 */
        private val VOLATILE_ENV_WORDS = listOf(
            "python", "pip", "zip", "unzip", "java", "jar", "busybox", "toybox",
            "shizuku", "root", "adb", "ffmpeg", "node", "npm",
        )

        /** 易变的判定词。 */
        private val VOLATILE_VERDICT_WORDS = listOf(
            "没有", "未安装", "不支持", "不可用", "不存在", "无法安装", "已确认", "检测到",
        )
    }
}
