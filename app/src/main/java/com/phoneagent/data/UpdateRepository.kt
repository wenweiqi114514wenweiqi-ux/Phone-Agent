package com.phoneagent.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.phoneagent.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

/** GitHub 上的一个发布版本。 */
data class RemoteRelease(
    val tag: String,
    val name: String,
    val body: String,
    val prerelease: Boolean,
    val htmlUrl: String,
    val publishedAt: Long,
    val apkUrl: String?,
    val apkName: String?,
    val apkSize: Long,
    /** 从 APK 文件名里的 vcNN 解析出来；解析不到为 0，退回版本号比较。 */
    val versionCode: Int,
    /** 展示用版本号，如 1.0.15-beta。 */
    val versionLabel: String,
)

data class UpdateUiState(
    val checking: Boolean = false,
    /** 非 null 表示正在下载，取值 0f..1f。 */
    val progress: Float? = null,
    val downloadedPath: String? = null,
    val downloadedTag: String = "",
    val stable: RemoteRelease? = null,
    val beta: RemoteRelease? = null,
    val lastCheckAt: Long = 0L,
    val lastError: String? = null,
    val hint: String? = null,
)

/**
 * 更新检测：只读 GitHub Releases，不经过任何第三方服务。
 *
 * 通道规则：
 * - 设置里「加入 beta 更新」关闭时只看正式版（release.prerelease = false），
 * - 打开后才把 beta（预发布）也纳入比较，按 versionCode 取更新的那个。
 *
 * 弹窗是否出现由这里统一决定：同一版本被「暂不更新」过就不再打扰，
 * 只有出现更新的版本才会重新提示。
 */
class UpdateRepository(
    context: Context,
    private val settings: SettingsRepository,
) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Suppress("DEPRECATION")
    val currentVersionCode: Int = runCatching {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
    }.getOrDefault(0)

    val currentVersionName: String = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    val currentVersionLabel: String
        get() = "v$currentVersionName · vc$currentVersionCode"

    private val _state = MutableStateFlow(
        UpdateUiState(lastCheckAt = settings.lastUpdateCheckAt)
    )
    val state = _state.asStateFlow()

    /** 待用户处理的"发现新版本"弹窗；null 表示不弹。 */
    private val _prompt = MutableStateFlow<RemoteRelease?>(null)
    val prompt = _prompt.asStateFlow()

    /** 当前通道下的可更新版本（已排除"不比当前新"的情况）。 */
    fun candidate(): RemoteRelease? {
        val s = _state.value.stable
        val b = _state.value.beta
        val best = if (settings.betaUpdates) {
            listOfNotNull(s, b).maxByOrNull { rankOf(it) }
        } else {
            s
        }
        return best?.takeIf { it.isNewerThanCurrent() }
    }

    /** 启动时静默检查：24 小时内不重复请求。 */
    suspend fun maybeAutoCheck() {
        if (!settings.autoCheckUpdate) return
        val now = System.currentTimeMillis()
        val last = settings.lastUpdateCheckAt
        if (last > 0 && now - last < AUTO_CHECK_INTERVAL_MS) return
        val found = runCatching { check(silent = true) }.getOrNull()
        if (found != null && found.tag != settings.dismissedUpdateTag) {
            _prompt.value = found
        }
    }

    /**
     * 拉取 releases 并刷新状态。
     * @return 当前通道下可更新的版本（没有则 null）
     */
    suspend fun check(silent: Boolean = false): RemoteRelease? {
        if (_state.value.checking) return candidate()
        _state.update { it.copy(checking = true, lastError = null) }
        try {
            val body = withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(RELEASES_URL)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "PhoneAgent-Updater")
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IllegalIOException("GitHub 返回 HTTP ${resp.code}")
                    }
                    resp.body?.string().orEmpty()
                }
            }
            val releases = parseReleases(body)
            val stable = releases.filter { !it.prerelease }.maxByOrNull { rankOf(it) }
            val beta = releases.filter { it.prerelease }.maxByOrNull { rankOf(it) }
            settings.lastUpdateCheckAt = System.currentTimeMillis()
            _state.update {
                it.copy(
                    checking = false,
                    stable = stable,
                    beta = beta,
                    lastCheckAt = settings.lastUpdateCheckAt,
                    lastError = null,
                )
            }
            val found = candidate()
            if (!silent && found != null) _prompt.value = found
            return found
        } catch (c: CancellationException) {
            _state.update { it.copy(checking = false) }
            throw c
        } catch (t: Throwable) {
            val msg = t.message ?: t.javaClass.simpleName
            // 断网/限流属于环境问题，不算软件缺陷
            AppLog.env("Update", "检查更新失败：$msg", t)
            _state.update { it.copy(checking = false, lastError = msg) }
            return null
        }
    }

    /** 用户在弹窗里点了「更新」：已下载就装，没有就下载。 */
    fun startUpdate(release: RemoteRelease, noBeta: Boolean = false) {
        if (noBeta) settings.betaUpdates = false
        settings.dismissedUpdateTag = release.tag
        val downloaded = _state.value
        if (downloaded.downloadedPath != null && downloaded.downloadedTag == release.tag) {
            installDownloaded()
            return
        }
        val url = release.apkUrl
        if (url.isNullOrBlank()) {
            _state.update { it.copy(hint = "这个版本没有可下载的 APK 附件") }
            return
        }
        if (_state.value.progress != null) return
        scope.launch { download(release, url) }
    }

    /** 「暂不更新」：记住这个版本，不再弹同一个；勾了"不再提示 beta"就关掉 beta 通道。 */
    fun dismiss(noBeta: Boolean = false) {
        val tag = _prompt.value?.tag
        if (!tag.isNullOrBlank()) settings.dismissedUpdateTag = tag
        if (noBeta) settings.betaUpdates = false
        _prompt.value = null
        _state.update { it.copy(hint = null) }
    }

    /** 设置页手动检查后，把结果弹出来。 */
    fun showPrompt(release: RemoteRelease) {
        settings.dismissedUpdateTag = ""
        _prompt.value = release
    }

    fun clearHint() = _state.update { it.copy(hint = null) }

    // ---------------- 下载 / 安装 ----------------

    private suspend fun download(release: RemoteRelease, url: String) {
        val dir = (appContext.getExternalFilesDir("updates") ?: File(appContext.filesDir, "updates"))
            .apply { mkdirs() }
        val name = (release.apkName ?: "PhoneAgent-${release.versionLabel}.apk")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(dir, name)
        val part = File(dir, "$name.part")
        _state.update { it.copy(progress = 0f, downloadedPath = null, downloadedTag = "", hint = null) }
        try {
            withContext(Dispatchers.IO) {
                val request = Request.Builder().url(url).header("User-Agent", "PhoneAgent-Updater").build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalIOException("下载失败 HTTP ${resp.code}")
                    val total = resp.body?.contentLength() ?: -1L
                    resp.body?.byteStream()?.use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var read = 0L
                            var lastPercent = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                read += n
                                if (total > 0) {
                                    val p = (read * 100 / total).toInt()
                                    if (p != lastPercent) {
                                        lastPercent = p
                                        _state.update { it.copy(progress = p / 100f) }
                                    }
                                }
                            }
                        }
                    }
                }
                if (part.length() <= 0) throw IllegalIOException("下载到的文件是空的")
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) throw IllegalIOException("无法保存安装包")
            }
            AppLog.info("Update", "已下载 ${release.versionLabel}：${target.length()} B")
            _state.update {
                it.copy(
                    progress = null,
                    downloadedPath = target.absolutePath,
                    downloadedTag = release.tag,
                    hint = null,
                )
            }
            installDownloaded()
        } catch (c: CancellationException) {
            _state.update { it.copy(progress = null) }
            throw c
        } catch (t: Throwable) {
            val msg = t.message ?: t.javaClass.simpleName
            AppLog.env("Update", "下载失败：$msg", t)
            _state.update { it.copy(progress = null, lastError = msg, hint = "下载失败：$msg") }
        }
    }

    /** 调起系统安装器；没给「安装未知应用」权限就先跳授权页。 */
    fun installDownloaded() {
        val path = _state.value.downloadedPath ?: return
        val file = File(path)
        if (!file.exists() || file.length() <= 0) {
            _state.update { it.copy(hint = "安装包不存在，请重新下载") }
            return
        }
        if (!appContext.packageManager.canRequestPackageInstalls()) {
            _state.update { it.copy(hint = "需要先允许「安装未知应用」，已为你打开设置") }
            runCatching {
                appContext.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${appContext.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                file,
            )
            appContext.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            _prompt.value = null
        }.onFailure {
            AppLog.error("Update", "调起安装器失败：${it.message}", it)
            _state.update { s -> s.copy(hint = "调起安装器失败：${it.message}") }
        }
    }

    // ---------------- 解析 ----------------

    private fun parseReleases(raw: String): List<RemoteRelease> {
        val arr = Json.parseToJsonElement(raw).jsonArray
        return arr.mapNotNull { el ->
            runCatching {
                val o = el.jsonObject
                if (o["draft"]?.jsonPrimitive?.booleanOrNull == true) return@runCatching null
                val tag = o["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
                val name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val body = o["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val pre = o["prerelease"]?.jsonPrimitive?.booleanOrNull ?: false
                val html = o["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val published = o["published_at"]?.jsonPrimitive?.contentOrNull
                val assets = o["assets"]?.jsonArray ?: emptyList<JsonElement>()
                val apk = assets.firstOrNull {
                    it.jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        .endsWith(".apk", ignoreCase = true)
                }?.jsonObject
                val apkName = apk?.get("name")?.jsonPrimitive?.contentOrNull
                val apkUrl = apk?.get("browser_download_url")?.jsonPrimitive?.contentOrNull
                val apkSize = apk?.get("size")?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                RemoteRelease(
                    tag = tag,
                    name = name,
                    body = body,
                    prerelease = pre,
                    htmlUrl = html,
                    publishedAt = runCatching {
                        published?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() } ?: 0L
                    }.getOrDefault(0L),
                    apkUrl = apkUrl,
                    apkName = apkName,
                    apkSize = apkSize,
                    versionCode = parseVersionCode(apkName ?: tag),
                    versionLabel = prettyLabel(tag),
                )
            }.getOrNull()
        }
    }

    private fun rankOf(r: RemoteRelease): Long =
        if (r.versionCode > 0) r.versionCode.toLong() else r.publishedAt

    private fun RemoteRelease.isNewerThanCurrent(): Boolean {
        if (versionCode > 0 && currentVersionCode > 0) return versionCode > currentVersionCode
        return compareLabels(versionLabel, currentVersionName) > 0
    }

    private fun parseVersionCode(text: String): Int =
        Regex("vc(\\d+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** v1.0.15beta → 1.0.15-beta；v1.0.15-beta.1 原样。 */
    private fun prettyLabel(tag: String): String {
        val s = tag.trim().removePrefix("v").removePrefix("V")
        return Regex("(\\d)(beta|rc|alpha)", RegexOption.IGNORE_CASE).replace(s) {
            it.groupValues[1] + "-" + it.groupValues[2]
        }
    }

    private fun compareLabels(a: String, b: String): Int {
        val na = numbersOf(a)
        val nb = numbersOf(b)
        for (i in 0 until maxOf(na.size, nb.size)) {
            val x = na.getOrElse(i) { 0 }
            val y = nb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        // 数字相同：带 beta/rc 的预发布版算更旧
        val suffixA = a.any { it.isLetter() }
        val suffixB = b.any { it.isLetter() }
        return when {
            suffixA && !suffixB -> -1
            !suffixA && suffixB -> 1
            else -> 0
        }
    }

    private fun numbersOf(s: String): List<Int> =
        Regex("\\d+").findAll(s).mapNotNull { it.value.toIntOrNull() }.toList()

    private class IllegalIOException(message: String) : Exception(message)

    companion object {
        private const val OWNER = "wenweiqi114514wenweiqi-ux"
        private const val REPO = "Phone-Agent"
        private const val API = "https://api.github.com/repos/$OWNER/$REPO"
        private const val RELEASES_URL = "$API/releases?per_page=20"

        private const val HOUR = 60L * 60L * 1000L
        private const val AUTO_CHECK_INTERVAL_MS = 24 * HOUR

        /** 仓库主页，设置页里放个入口。 */
        const val REPO_URL = "https://github.com/$OWNER/$REPO"
    }
}
