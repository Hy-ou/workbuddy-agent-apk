package com.workbuddy2api.panel

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.workbuddy2api.panel.data.AppViewModel
import com.workbuddy2api.panel.ui.connect.ConnectScreen
import com.workbuddy2api.panel.ui.shell.AppShell
import com.workbuddy2api.panel.ui.theme.WbTheme

class MainActivity : ComponentActivity() {

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()
        val startTab = intent?.getIntExtra("tab", 0) ?: 0
        val startMode = intent?.getStringExtra("mode") ?: ""
        setContent { WbRoot(startTab, startMode) }
        debugToolchainInstall()
    }

    /**
     * 仅调试包：`adb shell am start -n … --es wbTool git` 直接跑一次工具链安装，
     * 进度与结果写入 logcat（tag wbTool），无需在界面上点按。
     */
    private fun debugToolchainInstall() {
        if (!BuildConfig.DEBUG) return
        val id = intent?.getStringExtra("wbTool")?.takeIf { it.isNotBlank() } ?: return
        Thread({
            runCatching {
                val manager = com.workbuddy2api.panel.dsh.DshManager.get(applicationContext)
                val r = kotlinx.coroutines.runBlocking {
                    manager.installToolchain(id) { line ->
                        android.util.Log.i("wbTool", line)
                    }
                }
                android.util.Log.i("wbTool", "RESULT ok=" + r.ok + " msg=" + r.message)
            }.onFailure { android.util.Log.e("wbTool", "EXCEPTION", it) }
        }, "wb-toolchain-debug").start()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) runCatching { notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }
}

@Composable
private fun WbRoot(startTab: Int, startMode: String) {
    val vm: AppViewModel = viewModel()
    val session by vm.session.collectAsStateWithLifecycle()
    val repo by vm.repo.collectAsStateWithLifecycle()
    val dark = when (session?.themeMode ?: "dark") {
        "light" -> false
        "system" -> isSystemInDarkTheme()
        else -> true
    }
    // 跟随 App 主题（而不是系统主题）设置状态栏/导航栏图标明暗，避免浅色背景下白字看不见。
    val view = LocalView.current
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }
    WbTheme(darkTheme = dark) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            val s = session
            val r = repo
            if (s != null && r != null && s.configured) {
                AppShell(vm = vm, session = s, repo = r, initialTab = startTab, initialMode = startMode)
            } else {
                ConnectScreen(vm)
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
