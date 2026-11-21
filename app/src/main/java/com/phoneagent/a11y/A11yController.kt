package com.phoneagent.a11y

import android.accessibilityservice.AccessibilityService

/** UI 动作结果。 */
data class UiResult(val ok: Boolean, val message: String)

/**
 * 无障碍操控门面：所有 UI 级动作的统一入口，带状态校验与结果描述。
 * 未开启无障碍时统一返回失败信息，避免静默失败。
 */
object A11yController {

    fun isEnabled(): Boolean = AgentAccessibilityService.isEnabled()

    private fun service(): AgentAccessibilityService? =
        AgentAccessibilityService.instance?.takeIf { AgentAccessibilityService.connected }

    private fun screenSize(): Pair<Int, Int> {
        val svc = service() ?: return 1080 to 2400
        val dm = svc.resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    private fun notEnabled() =
        UiResult(false, "无障碍服务未开启：请到 设置 → Phone Use → 无障碍服务 中开启")

    /** 点击坐标。 */
    fun tap(x: Int, y: Int): UiResult {
        val svc = service() ?: return notEnabled()
        return if (svc.tap(x, y)) UiResult(true, "已点击坐标 ($x, $y)")
        else UiResult(false, "点击失败：手势被系统拒绝")
    }

    /** 点击包含指定文本的控件。 */
    fun clickText(text: String): UiResult {
        val svc = service() ?: return notEnabled()
        return if (svc.clickByText(text)) UiResult(true, "已点击「$text」")
        else UiResult(false, "未找到可点击的「$text」控件")
    }

    /** 滑动：direction = up/down/left/right。 */
    fun swipe(direction: String, ratio: Float = 0.6f): UiResult {
        val svc = service() ?: return notEnabled()
        val (w, h) = screenSize()
        val cx = w / 2
        val cy = h / 2
        val dx = (w * ratio).toInt()
        val dy = (h * ratio).toInt()
        val coords = when (direction.lowercase()) {
            "up" -> listOf(cx, cy + dy / 2, cx, cy - dy / 2)
            "down" -> listOf(cx, cy - dy / 2, cx, cy + dy / 2)
            "left" -> listOf(cx + dx / 2, cy, cx - dx / 2, cy)
            "right" -> listOf(cx - dx / 2, cy, cx + dx / 2, cy)
            else -> return UiResult(false, "不支持的滑动方向：$direction（可用 up/down/left/right）")
        }
        val ok = svc.swipe(coords[0], coords[1], coords[2], coords[3], 300)
        return if (ok) UiResult(true, "已向 $direction 滑动") else UiResult(false, "滑动失败")
    }

    /** 在当前焦点输入框输入文字。 */
    fun inputText(text: String): UiResult {
        val svc = service() ?: return notEnabled()
        return if (svc.inputText(text)) UiResult(true, "已输入文字")
        else UiResult(false, "未找到可编辑的输入框")
    }

    /** 全局动作：back / home / recents / notifications。 */
    fun global(action: String): UiResult {
        val svc = service() ?: return notEnabled()
        val code = when (action.lowercase()) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            else -> return UiResult(false, "不支持的系统动作：$action（可用 back/home/recents/notifications）")
        }
        return if (svc.performGlobalAction(code)) UiResult(true, "已执行 $action")
        else UiResult(false, "执行 $action 失败")
    }

    /** 读取当前屏幕可见文字。 */
    fun dumpScreen(): UiResult {
        val svc = service() ?: return notEnabled()
        val text = svc.dumpScreen()
        return if (text.isBlank()) UiResult(true, "当前屏幕未读取到文字")
        else UiResult(true, "屏幕文字：\n$text")
    }
}
