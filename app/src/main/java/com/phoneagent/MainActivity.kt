package com.phoneagent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.phoneagent.ui.FileAccessGate
import com.phoneagent.ui.MainScreen
import com.phoneagent.ui.theme.PhoneAgentTheme

class MainActivity : ComponentActivity() {

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 结果不影响主流程 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 纯 View 崩溃回执：先于 Compose / 数据库初始化，确保崩了也能看到堆栈
        val crash = CrashStore.last(this)
        if (crash != null) {
            showCrashReport(crash)
            return
        }
        requestNotificationPermission()
        setContent {
            val appearance by (application as PhoneAgentApp).container.appearance.state.collectAsState()
            PhoneAgentTheme(appearance = appearance) {
                MainScreen()
                // 启动即检测文件访问权限，缺就弹窗（已授权则什么都不做）
                FileAccessGate()
            }
        }
    }

    /** 不用 Compose 画崩溃页：TextView + 继续按钮，任何情况下都能显示。 */
    private fun showCrashReport(text: String) {
        val scroll = android.widget.ScrollView(this)
        val column = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 70, 40, 70)
            setBackgroundColor(android.graphics.Color.WHITE)
        }
        column.addView(android.widget.TextView(this).apply {
            this.text = "上次启动崩溃"
            textSize = 19f
            setTextColor(android.graphics.Color.BLACK)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        column.addView(android.widget.TextView(this).apply {
            this.text = "请把这页截图发给开发者。点下方「继续使用」可清除记录再进一次。"
            textSize = 13f
            setTextColor(android.graphics.Color.DKGRAY)
            setPadding(0, 16, 0, 16)
        })
        column.addView(android.widget.TextView(this).apply {
            this.text = text
            textSize = 10.5f
            setTextColor(android.graphics.Color.BLACK)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        })
        column.addView(android.widget.Button(this).apply {
            this.text = "继续使用"
            setOnClickListener {
                CrashStore.clear(this@MainActivity)
                recreate()
            }
        })
        scroll.addView(column)
        setContentView(scroll)
    }

    /** Android 13+ 需要运行时请求通知权限（用于 ask_user 等待回复提示）。 */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
