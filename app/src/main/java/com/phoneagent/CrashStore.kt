package com.phoneagent

import android.content.Context

/**
 * 崩溃回执的最小存储：只用 SharedPreferences，不依赖 Room / Compose，
 * 以便下一次启动时能在任何状态下读出来。
 */
object CrashStore {
    private const val PREF = "phone_agent_crash"
    private const val KEY = "last"

    fun save(context: Context, text: String) {
        runCatching {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY, text).commit()
        }
    }

    fun last(context: Context): String? = runCatching {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null)
    }.getOrNull()

    fun clear(context: Context) {
        runCatching {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().remove(KEY).commit()
        }
    }
}
