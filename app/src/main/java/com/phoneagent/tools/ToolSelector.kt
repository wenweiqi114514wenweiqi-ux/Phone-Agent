package com.phoneagent.tools

/**
 * 按本轮请求挑工具，减少每次请求里重复发送的 66 份工具定义。
 *
 * 原则：
 * - 文件、Office、网络、OCR、提交交付物这些高频能力始终保留；
 * - 应用管理 / 屏幕输入 / 系统设置 / 系统信息 / 额外网络诊断按关键词按需加入；
 * - 用户自定义工具永远保留；
 * - 用户正文里直接点名某个工具时，无论如何都保留它。
 *
 * 工具页仍然展示全部工具；这里只影响发给模型的 function calling 清单。
 */
object ToolSelector {

    private val CORE = setOf(
        // 文件（读写、查找、打包是日常最高频）
        "file_list", "file_mkdir", "file_touch", "file_copy", "file_move",
        "file_cat", "file_find", "file_chmod", "file_remove",
        "file_write", "file_append", "file_grep", "file_tail",
        "file_stat", "file_du", "file_wc", "file_base64", "file_zip",
        "file_unzip", "file_zip_list",
        // 文档
        "doc_write", "doc_extract_text", "sheet_write", "sheet_read", "ppt_write",
        // 网络与下载
        "http_get", "http_post", "web_fetch", "file_download",
        // 图片
        "ocr_image", "image_describe",
        // 收尾与交互
        "submit_file", "ask_user", "shell_exec", "memory_save", "set_keepalive",
    )

    private data class Group(val names: Set<String>, val keywords: List<String>)

    private val GROUPS = listOf(
        Group(
            names = setOf(
                "pm_list_packages", "pm_uninstall", "pm_install", "pm_clear",
                "pm_disable", "am_start", "am_force_stop", "am_broadcast",
            ),
            keywords = listOf(
                "应用", "安装", "卸载", "清除数据", "禁用", "包名", "apk",
                "启动", "打开", "停止", "强制停止", "广播",
                "装一下", "安一下", "装个",
            ),
        ),
        Group(
            names = setOf(
                "screencap", "input_keyevent", "input_text", "input_tap",
                "input_swipe", "input_longpress",
            ),
            keywords = listOf(
                "截图", "屏幕", "点击", "滑动", "按键", "输入", "长按",
                "坐标", "操作手机", "自动化",
                "截屏", "点一下", "点下", "划一下", "按一下", "手势", "触控",
            ),
        ),
        Group(
            names = setOf(
                "settings_get", "settings_put", "settings_put_global",
                "set_system_setting",
            ),
            keywords = listOf(
                "系统设置", "设置", "亮度", "旋转", "超时", "屏幕常亮",
                "系统版本", "版本号",
            ),
        ),
        Group(
            names = setOf(
                "dumpsys", "logcat", "sys_df", "sys_free", "sys_ps",
                "sys_getprop", "sys_ip", "sys_battery", "reboot",
            ),
            keywords = listOf(
                "系统信息", "内存", "磁盘", "存储空间", "进程", "电池",
                "日志", "属性", "ip", "网络接口", "重启",
                "存储", "空间", "后台", "运行状态", "系统版本", "版本号",
            ),
        ),
        Group(
            names = setOf("ping", "netstat", "dns_lookup"),
            keywords = listOf(
                "ping", "端口", "路由", "dns", "域名解析", "网络诊断",
                "网络", "连接", "域名",
            ),
        ),
    )

    fun select(all: List<ToolDefinition>, query: String): List<ToolDefinition> {
        val q = query.lowercase()
        val selected = CORE.toMutableSet()

        GROUPS.forEach { group ->
            if (group.keywords.any { q.contains(it) }) selected += group.names
        }

        // 用户直接点名某个工具（例如“用 pm_install 装它”）时，不能因为分组被漏掉。
        all.forEach { tool ->
            if (q.contains(tool.name.lowercase())) selected += tool.name
        }

        return all.filter { tool ->
            // 自定义工具（非内置）始终保留，避免用户扩展被过滤。
            tool.name in selected || !tool.builtin
        }
    }
}
