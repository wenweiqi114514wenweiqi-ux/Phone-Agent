package com.phoneagent.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * 本机运行日志：所有「软件自身出了问题」都往这里记一条。
 *
 * 目的只有一个——出问题时用户不必截图堆栈，进「设置 → 运行日志」就能看到
 * 时间、模块、错误信息和完整堆栈；有新问题时底部会浮出小提示。
 *
 * 只存本机（filesDir/app_log.jsonl），最多保留最近 400 条，不联网、不上传。
 */
object AppLog {

    /**
     * ENV  = 环境/权限类（用户没给 Root、Shizuku 未授权、路径不存在…），
     *        用户自己就能解决，不算软件 bug，不计入问题数、不弹提示。
     * WARN / ERROR = 软件自身的问题。
     */
    enum class Level { INFO, ENV, WARN, ERROR }

    data class Entry(
        val time: Long,
        val level: Level,
        val tag: String,
        val message: String,
        val stack: String?,
    )

    private const val FILE_NAME = "app_log.jsonl"
    private const val MAX_MEMORY = 400
    private const val MAX_FILE_LINES = 600
    private const val PREF = "phone_agent_log"

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())

    /** 最近日志，最新的在前。 */
    val entries = _entries.asStateFlow()

    private val _openIssues = MutableStateFlow(0)

    /** 上次查看日志之后新增的问题条数（WARN / ERROR）。 */
    val openIssues = _openIssues.asStateFlow()

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        runCatching {
            val list = readAll()
            _entries.value = list.asReversed()
            val lastRead = lastReadTime()
            _openIssues.value = list.count {
                (it.level == Level.WARN || it.level == Level.ERROR) && it.time > lastRead
            }
        }.onFailure { Log.w("AppLog", "init failed: ${it.message}") }
    }

    fun info(tag: String, message: String) = record(Level.INFO, tag, message, null)

    /** 环境类：记录但不打扰（不计入“发现问题”）。 */
    fun env(tag: String, message: String, error: Throwable? = null) =
        record(Level.ENV, tag, message, error)

    fun warn(tag: String, message: String, error: Throwable? = null) =
        record(Level.WARN, tag, message, error)

    fun error(tag: String, message: String, error: Throwable? = null) =
        record(Level.ERROR, tag, message, error)

    /** 用户看过日志后调用：清掉「有问题」提示。 */
    fun markRead() {
        val now = System.currentTimeMillis()
        appContext?.let {
            runCatching {
                it.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putLong("last_read", now).apply()
            }
        }
        _openIssues.value = 0
    }

    fun clear() {
        runCatching { file()?.delete() }
        _entries.value = emptyList()
        _openIssues.value = 0
    }

    /** 导出为纯文本，供「复制」用。 */
    fun dump(): String = _entries.value.joinToString("\n\n") { e ->
        buildString {
            append(timeText(e.time)).append(" [").append(e.level.name).append("] ")
            append(e.tag).append(" · ").append(e.message)
            e.stack?.let { append("\n").append(it) }
        }
    }

    private fun record(level: Level, tag: String, message: String, error: Throwable?) {
        val stack = error?.let { Log.getStackTraceString(it) }
        val entry = Entry(System.currentTimeMillis(), level, tag, message, stack)
        _entries.value = (listOf(entry) + _entries.value).take(MAX_MEMORY)
        if (level == Level.WARN || level == Level.ERROR) _openIssues.value += 1
        when (level) {
            Level.ERROR -> Log.e("PhoneAgent/$tag", message, error)
            Level.WARN -> Log.w("PhoneAgent/$tag", message, error)
            Level.ENV -> Log.i("PhoneAgent/$tag", "[env] $message")
            Level.INFO -> Log.i("PhoneAgent/$tag", message)
        }
        runCatching {
            val f = file() ?: return@runCatching
            val line = JSONObject().apply {
                put("t", entry.time)
                put("l", level.name)
                put("g", tag)
                put("m", message)
                stack?.let { put("s", it) }
            }.toString()
            f.appendText(line + "\n")
            // 偶尔压缩，避免无限增长
            if (f.length() > 512 * 1024) {
                val kept = f.readLines().takeLast(MAX_FILE_LINES)
                f.writeText(kept.joinToString("\n") + "\n")
            }
        }
    }

    private fun file(): java.io.File? = appContext?.let { java.io.File(it.filesDir, FILE_NAME) }

    private fun readAll(): List<Entry> {
        val f = file() ?: return emptyList()
        if (!f.exists()) return emptyList()
        return f.readLines()
            .takeLast(MAX_MEMORY)
            .mapNotNull { line -> runCatching { decode(line) }.getOrNull() }
    }

    private fun decode(line: String): Entry? {
        if (line.isBlank()) return null
        val o = JSONObject(line)
        return Entry(
            time = o.optLong("t"),
            level = runCatching { Level.valueOf(o.optString("l", "INFO")) }.getOrDefault(Level.INFO),
            tag = o.optString("g"),
            message = o.optString("m"),
            stack = o.optString("s").takeIf { it.isNotBlank() },
        )
    }

    private fun lastReadTime(): Long = appContext?.let {
        runCatching {
            it.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong("last_read", 0L)
        }.getOrDefault(0L)
    } ?: 0L

    private fun timeText(ts: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = ts }
        return String.format(
            "%02d-%02d %02d:%02d:%02d",
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND),
        )
    }

    fun timeTextOf(ts: Long): String = timeText(ts)
}

/**
 * 轻度事件总线：某个页面想「跳去设置日志」时打一声招呼，
 * 主界面切到设置 Tab，设置页切到日志页。
 */
object AppEvents {
    private val _openLog = MutableStateFlow(0)
    val openLog = _openLog.asStateFlow()

    fun requestOpenLog() {
        _openLog.value += 1
    }
}
