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
        runCatching { com.workbuddy2api.panel.dsh.DshManager.get(applicationContext).refreshBinShims() }
        debugToolchainInstall()
        debugPluginInstall()
        debugExec()
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

    /** 仅调试包：`--es wbPlugin <包名>` 走一次社区插件安装（与界面按钮同一条路径）。 */
    private fun debugPluginInstall() {
        if (!BuildConfig.DEBUG) return
        val pkg = intent?.getStringExtra("wbPlugin")?.takeIf { it.isNotBlank() } ?: return
        Thread({
            runCatching {
                val manager = com.workbuddy2api.panel.dsh.DshManager.get(applicationContext)
                val r = kotlinx.coroutines.runBlocking { manager.installPlugin(pkg) }
                android.util.Log.i("wbPlugin", "RESULT ok=" + r.ok + " msg=" + r.output)
            }.onFailure { android.util.Log.e("wbPlugin", "EXCEPTION", it) }
        }, "wb-plugin-debug").start()
    }

    /** 仅调试包：`--es wbExec "<命令>"`，用内核等价环境（应用进程）执行并打印输出。 */
    private fun debugExec() {
        if (!BuildConfig.DEBUG) return
        val cmd = intent?.getStringExtra("wbExec")?.takeIf { it.isNotBlank() } ?: return
        Thread({
            runCatching {
                val home = java.io.File(filesDir, "dsh-home").absolutePath
                val nlib = applicationInfo.nativeLibraryDir
                val tusr = home + "/toolchain/usr"
                val pb = ProcessBuilder("sh", "-c", cmd)
                pb.redirectErrorStream(true)
                val e = pb.environment()
                e["PATH"] = home + "/bin:" + tusr + "/bin:" + nlib + ":/system/bin"
                e["HOME"] = filesDir.absolutePath
                e["LD_LIBRARY_PATH"] = nlib
                e["PREFIX"] = tusr
                e["WB_DSH_HOME"] = home
                e["WB_NODE"] = nlib + "/libdshnode.so"
                e["WB_TOOLCHAIN_BIN"] = tusr + "/bin"
                e["WB_TOOLS_BIN"] = home + "/tools/node_modules/.bin"
                e["GIT_EXEC_PATH"] = tusr + "/libexec/git-core"
                e["GIT_SSL_CAINFO"] = tusr + "/etc/tls/cert.pem"
                val p = pb.start()
                val out = p.inputStream.bufferedReader().readText()
                val code = p.waitFor()
                android.util.Log.i("wbExec", "exit=" + code)
                out.lineSequence().take(12).forEach { android.util.Log.i("wbExec", it) }
            }.onFailure { android.util.Log.e("wbExec", "EXCEPTION", it) }
        }, "wb-exec-debug").start()
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
