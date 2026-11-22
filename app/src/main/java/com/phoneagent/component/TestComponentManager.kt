package com.phoneagent.component

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.phoneagent.data.SettingsRepository
import com.phoneagent.exec.RootShell
import com.phoneagent.exec.ShizukuManager
import com.phoneagent.util.AppLog
import com.phoneagent.util.StorageAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * 测试组件：目前只有离线 Python 运行时。
 *
 * 安装位置固定在 /data/local/tmp/phoneagent：
 *   - Android 10+ 不允许执行 App 私有目录里的二进制；
 *   - /data/local/tmp 对 Shell UID（Shizuku）和 Root 都可执行；
 *   - 卸载 App 不会自动删除，所以提供「清理全部」。
 *
 * 组件包内置在 APK assets 中，不联网、不安装第三方包管理器。
 * App 负责解压到公开下载目录，再交给现有 Shell 通道复制、chmod、自检、软链。
 */
class TestComponentManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val shizukuManager: ShizukuManager,
) {

    enum class Status {
        UNKNOWN,
        NOT_INSTALLED,
        PREPARING,
        READY,
        NEED_PERMISSION,
        NEED_SHELL,
        ERROR,
    }

    data class State(
        val status: Status = Status.UNKNOWN,
        val version: String? = null,
        /** 0..1；null 表示进度未知。 */
        val progress: Float? = null,
        val message: String = "",
        val detail: String = "",
        val installedBytes: Long = 0L,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val mutex = Mutex()

    /** 给 Agent 的提示块：只有确认 READY 才写，避免旧结论污染下一轮。 */
    fun promptBlock(): String {
        val s = _state.value
        if (s.status != Status.READY) return ""
        return buildString {
            append("\n## 本机测试组件\n")
            append("- Python ")
            append(s.version ?: VERSION)
            append("：已就绪，可直接在 shell_exec 里使用 python3 / pip3。\n")
            append("- 不要再用 printf/echo 逐字节拼二进制；需要音频、脚本、数据处理时优先写 Python 脚本。\n")
            append("- 组件由 App 自动准备，不需要你下载或安装任何东西。\n")
        }
    }

    /** 是否已就绪（线程安全快照）。 */
    fun isReady(): Boolean = _state.value.status == Status.READY

    /** Shell 环境前缀：只在就绪时注入，避免半成品影响所有命令。 */
    fun shellEnvPrefix(): String {
        if (!isReady()) return ""
        return "export PATH=$ROOT/bin:$ROOT/home/.local/bin:\$PATH; " +
            "export HOME=$ROOT/home; export TMPDIR=$ROOT/tmp; "
    }

    fun isPythonCommand(command: String): Boolean {
        return PYTHON_REF.containsMatchIn(command)
    }

    /** 刷新安装状态。执行通道没就绪时不误报「已安装」。 */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (_state.value.status == Status.PREPARING) return@withContext
        val block = executionBlocker()
        if (block != null) {
            _state.value = State(status = block.first, message = block.second)
            return@withContext
        }
        val result = runShell("$ROOT/bin/python3 -V 2>&1")
        if (result.ok && result.output.contains("Python 3.")) {
            val version = result.output.lineSequence().firstOrNull { it.contains("Python 3.") }
                ?.trim() ?: VERSION
            settings.testPythonVersion = version
            _state.value = State(
                status = Status.READY,
                version = version,
                message = "已就绪，可直接使用 python3",
                installedBytes = installedBytes(),
            )
        } else if (result.output.contains("No such file") || result.output.contains("not found")) {
            _state.value = State(status = Status.NOT_INSTALLED, message = "尚未安装")
        } else {
            _state.value = State(
                status = Status.NOT_INSTALLED,
                message = "尚未安装",
                detail = result.output.take(300),
            )
        }
    }

    /**
     * 自动准备。用户不需要点安装；页面打开或模型第一次用 python3 时调用。
     * force=true 用于「重新安装 / 修复」。
     */
    suspend fun prepare(force: Boolean = false): Boolean = mutex.withLock {
        if (_state.value.status == Status.READY && !force) return@withLock true
        withContext(Dispatchers.IO) {
            val block = executionBlocker()
            if (block != null) {
                _state.value = State(status = block.first, message = block.second)
                return@withContext false
            }
            if (!StorageAccess.hasAccess(context)) {
                _state.value = State(
                    status = Status.NEED_PERMISSION,
                    message = "需要「所有文件访问权限」，用于把内置运行时解压到下载目录",
                )
                return@withContext false
            }
            val free = freeBytes()
            if (free in 1L until MIN_FREE_BYTES) {
                _state.value = State(
                    status = Status.ERROR,
                    message = "存储空间不足，至少需要 ${MIN_FREE_BYTES / 1024 / 1024} MB",
                )
                return@withContext false
            }

            _state.value = State(status = Status.PREPARING, progress = 0.02f, message = "正在解压内置运行时…")
            val staging = stagingDir()
            try {
                extractAsset(staging)
                _state.value = State(status = Status.PREPARING, progress = 0.62f, message = "正在安装到执行环境…")
                val result = runShell(installScript(staging.absolutePath))
                if (!result.ok || !result.output.contains(SELF_TEST_MARK)) {
                    throw IllegalStateException(result.output.ifBlank { "安装命令没有返回结果" }.take(1200))
                }
                _state.value = State(status = Status.PREPARING, progress = 0.96f, message = "正在自检…")
                val check = runShell("$ROOT/bin/python3 -c \"print('$SELF_TEST_MARK', 6*7)\"")
                if (!check.ok || !check.output.contains(SELF_TEST_MARK)) {
                    throw IllegalStateException(check.output.ifBlank { "python3 自检没有输出" }.take(800))
                }
                settings.testPythonVersion = VERSION
                _state.value = State(
                    status = Status.READY,
                    version = VERSION,
                    message = "已就绪，可直接使用 python3",
                    installedBytes = installedBytes(),
                )
                AppLog.info("component", "Python 运行时已就绪：$VERSION")
                true
            } catch (e: Exception) {
                AppLog.error("component", "Python 运行时安装失败", e)
                _state.value = State(
                    status = Status.ERROR,
                    message = "准备失败，可点「重新准备」",
                    detail = (e.message ?: e.javaClass.simpleName).take(1200),
                )
                false
            } finally {
                runCatching { staging.deleteRecursively() }
            }
        }
    }

    /** 模型调用 python3 前确保可用；失败时外层会拿到原命令的报错并附加提示。 */
    suspend fun ensurePython(): Boolean {
        if (isReady()) return true
        if (!settings.testComponentsAuto) return false
        return prepare(force = false)
    }

    suspend fun selfTest(): String = withContext(Dispatchers.IO) {
        val result = runShell("$ROOT/bin/python3 -c \"print('$SELF_TEST_MARK', 6*7)\"")
        if (result.ok && result.output.contains(SELF_TEST_MARK)) {
            result.output.trim()
        } else {
            "自检失败：${result.output.take(500)}"
        }
    }

    suspend fun uninstall() = withContext(Dispatchers.IO) {
        val result = runShell(
            "rm -rf $ROOT/opt/python; " +
                "rm -f $ROOT/bin/python3 $ROOT/bin/python $ROOT/bin/pip3 $ROOT/bin/pip"
        )
        if (result.ok) {
            settings.testPythonVersion = ""
            _state.value = State(status = Status.NOT_INSTALLED, message = "已卸载")
        } else {
            _state.value = State(
                status = Status.ERROR,
                message = "卸载失败",
                detail = result.output.take(500),
            )
        }
    }

    /** 高级：删除整个组件目录（包括用户用 pip 装到 home 的包）。 */
    suspend fun cleanAll() = withContext(Dispatchers.IO) {
        val result = runShell("rm -rf $ROOT")
        if (result.ok) {
            settings.testPythonVersion = ""
            _state.value = State(status = Status.NOT_INSTALLED, message = "已清理全部组件")
        } else {
            _state.value = State(status = Status.ERROR, message = "清理失败", detail = result.output.take(500))
        }
    }

    // ---------------- 内部实现 ----------------

    private fun executionBlocker(): Pair<Status, String>? {
        if (!Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
            return Status.ERROR to "当前设备不是 arm64，暂不支持这个测试组件"
        }
        if (RootShell.ensureDetected() || shizukuManager.shellService != null) return null
        runCatching { shizukuManager.retryBind() }
        if (shizukuManager.shellService != null) return null
        return Status.NEED_SHELL to "需要先启用执行通道（Root 或 Shizuku），组件才能安装到可执行目录"
    }

    private fun extractAsset(dest: File) {
        dest.deleteRecursively()
        dest.mkdirs()
        val count = runCatching {
            context.assets.open(ASSET).use { input ->
                ZipInputStream(input).use { zip ->
                    var n = 0
                    while (zip.nextEntry != null) n++
                    n
                }
            }
        }.getOrDefault(0)
        var index = 0
        context.assets.open(ASSET).use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (name.isBlank() || name.startsWith("__MACOSX")) continue
                    val out = File(dest, name)
                    if (!out.canonicalPath.startsWith(dest.canonicalPath)) {
                        throw IllegalStateException("组件包路径异常：$name")
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { fos ->
                            val buf = ByteArray(8192)
                            while (true) {
                                val n = zip.read(buf)
                                if (n <= 0) break
                                fos.write(buf, 0, n)
                            }
                        }
                    }
                    index++
                    if (count > 0) {
                        val p = 0.02f + (index.toFloat() / count) * 0.58f
                        _state.value = _state.value.copy(progress = p, message = "正在解压内置运行时…")
                    }
                    zip.closeEntry()
                }
            }
        }
    }

    private fun installScript(stagingPath: String): String {
        val q = "'$stagingPath'"
        return """
            ROOT=$ROOT
            V=$ROOT/opt/python/versions/$VERSION
            mkdir -p "$ROOT/opt/python/versions" "$ROOT/bin" "$ROOT/home/.local/bin" "$ROOT/tmp" || exit 1
            rm -rf "${'$'}V.new"
            mkdir -p "${'$'}V.new" || exit 2
            cp -R $q/. "${'$'}V.new/" || { echo "COPY_FAIL"; exit 3; }
            chmod -R 755 "${'$'}V.new" || { echo "CHMOD_FAIL"; exit 4; }
            if ! PYTHONHOME="${'$'}V.new" LD_LIBRARY_PATH="${'$'}V.new/lib" "${'$'}V.new/bin/python3.14" -c "print('$SELF_TEST_MARK')" 2>&1; then
              echo "SELFTEST_FAIL"; rm -rf "${'$'}V.new"; exit 5
            fi
            if [ -e "${'$'}V" ]; then mv "${'$'}V" "${'$'}V.old"; fi
            mv "${'$'}V.new" "${'$'}V" || { echo "MOVE_FAIL"; exit 6; }
            ln -sfn versions/$VERSION "$ROOT/opt/python/current"
            ln -sfn ../opt/python/current/bin/python3 "$ROOT/bin/python3"
            ln -sfn ../opt/python/current/bin/python "$ROOT/bin/python"
            ln -sfn ../opt/python/current/bin/pip3 "$ROOT/bin/pip3"
            ln -sfn ../opt/python/current/bin/pip "$ROOT/bin/pip"
            if ! "$ROOT/bin/python3" -c "print('$SELF_TEST_MARK')" 2>&1; then
              rm -rf "${'$'}V"
              if [ -e "${'$'}V.old" ]; then mv "${'$'}V.old" "${'$'}V"; fi
              echo "WRAPPER_FAIL"; exit 7
            fi
            rm -rf "${'$'}V.old"
            echo "PHONEAGENT_INSTALL_OK"
            exit 0
        """.trimIndent()
    }

    private suspend fun runShell(command: String): ShellResult = withContext(Dispatchers.IO) {
        val raw = if (RootShell.available == true) {
            RootShell.exec(command, 300)
        } else {
            val svc = shizukuManager.shellService
                ?: return@withContext ShellResult(false, "执行通道未就绪")
            runCatching { svc.exec(command) }
                .getOrElse { "EXCEPTION: ${it.message}" }
        }
        val ok = !raw.startsWith("ERROR(") && !raw.startsWith("EXCEPTION:")
        ShellResult(ok, raw)
    }

    private suspend fun installedBytes(): Long = withContext(Dispatchers.IO) {
        val r = runShell("du -sk $ROOT/opt/python 2>/dev/null")
        r.output.lineSequence().firstOrNull()?.trim()?.substringBefore('\t')?.toLongOrNull()
            ?.let { it * 1024L } ?: 0L
    }

    private fun stagingDir(): File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(downloads, "PhoneAgent/components/python-runtime")
    }

    private fun freeBytes(): Long = runCatching {
        val target = File("/data/local/tmp")
        val path = if (target.exists()) target.absolutePath else context.filesDir.absolutePath
        StatFs(path).availableBytes
    }.getOrDefault(Long.MAX_VALUE)

    private data class ShellResult(val ok: Boolean, val output: String)

    companion object {
        private const val ROOT = "/data/local/tmp/phoneagent"
        const val VERSION = "3.14.10"
        private const val ASSET = "test_components/python-3.14.10-arm64.zip"
        private const val SELF_TEST_MARK = "PHONEAGENT_PY_OK"
        private const val MIN_FREE_BYTES = 160L * 1024 * 1024

        private val PYTHON_REF = Regex("""(?i)(^|[\s;&|])(python3?(\.\d+)?|pip3?)(\s|$)""")
    }
}
