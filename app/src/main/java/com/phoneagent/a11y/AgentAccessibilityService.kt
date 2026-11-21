package com.phoneagent.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

/**
 * 无障碍服务：为 Agent 提供界面级操控能力（点击/滑动/输入/返回主页/读屏）。
 *
 * 安全设计：
 * - 服务本身不做任何自动化，只被动等待 Agent 工具调用；
 * - 所有操作由工具层触发并写入操作日志；
 * - 用户可在设置页一键关闭（关闭服务即彻底失效）。
 */
class AgentAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        connected = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不监听事件内容，避免读取用户隐私；仅在读取屏幕时才主动抓取节点树
    }

    override fun onInterrupt() {
        // 无需处理
    }

    override fun onDestroy() {
        connected = false
        instance = null
        super.onDestroy()
    }

    companion object {
        /** 服务实例（连接后赋值）。 */
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        /** 服务是否已连接。 */
        @Volatile
        var connected: Boolean = false
            private set

        fun isEnabled(): Boolean = connected && instance != null
    }

    // ===== 供 A11yController 调用的底层动作 =====

    /** 点击坐标。 */
    fun tap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** 滑动。 */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** 查找包含指定文本的可点击节点并点击。 */
    fun clickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNode(root, text)
        var target: AccessibilityNodeInfo? = node
        // 向上找可点击祖先
        while (target != null && !target.isClickable) target = target.parent
        val ok = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        return ok
    }

    private fun findNode(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (root.text?.toString()?.contains(text, ignoreCase = true) == true) return root
        if (root.contentDescription?.toString()?.contains(text, ignoreCase = true) == true) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            findNode(child, text)?.let { return it }
        }
        return null
    }

    /** 向当前焦点输入框写入文本。 */
    fun inputText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val editable = findEditable(root) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findEditable(child)?.let { return it }
        }
        return null
    }

    /** 读取当前屏幕可见文本（节点树）。 */
    fun dumpScreen(): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        collectText(root, sb, 0)
        return sb.toString().trim()
    }

    private fun collectText(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > 12) return
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val label = text?.takeIf { it.isNotBlank() } ?: desc?.takeIf { it.isNotBlank() }
        if (label != null) {
            sb.append(label).append('\n')
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectText(it, sb, depth + 1) }
        }
    }
}
