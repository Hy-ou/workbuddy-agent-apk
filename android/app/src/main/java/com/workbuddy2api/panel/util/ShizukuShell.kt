package com.workbuddy2api.panel.util

import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File

/**
 * 免 root 的系统特权通道（基于 Shizuku）。
 *
 * 能力：以 shell（uid 2000）身份执行系统命令 —— 装/卸载应用（pm install）、模拟输入（input tap/text）、
 * 截屏（screencap）、改系统设置（settings put）、查看进程等。
 *
 * 前置：用户自行安装 https://shizuku.rikka.app/ 并在 App 内授权。
 * 未安装 / 未授权时 [available] 为 false，调用方应降级到无障碍通道，不要报错打扰用户。
 */
object ShizukuShell {

    fun available(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun granted(): Boolean {
        if (!available()) return false
        return runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    /** 发起授权请求（需要在前台 Activity 里调用）。 */
    fun requestPermission(requestCode: Int = 1001) {
        if (!available()) return
        runCatching { Shizuku.requestPermission(requestCode) }
    }

    data class ShellResult(val code: Int, val output: String)

    /**
     * 以 shell 身份执行 `sh -c "..."`，返回退出码与合并输出。
     * 未安装/未授权时抛异常，由调用方转成工具失败结果。
     */
    suspend fun exec(command: String, timeoutMs: Long = 25_000): ShellResult = withContext(Dispatchers.IO) {
        check(available()) { "Shizuku 未安装或未启动：请先安装 Shizuku 并启动服务" }
        check(granted()) { "Shizuku 未授权：请在「设置 → 扩展能力」里点「申请 Shizuku 授权」" }
        val process = newShellProcess(command)
        val out = StringBuilder()
        val tOut = Thread {
            runCatching { process.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
        }.apply { isDaemon = true; start() }
        val tErr = Thread {
            runCatching { process.errorStream.bufferedReader().forEachLine { out.appendLine(it) } }
        }.apply { isDaemon = true; start() }
        val deadline = System.currentTimeMillis() + timeoutMs
        var code = -1
        while (System.currentTimeMillis() < deadline) {
            code = runCatching { process.exitValue() }.getOrDefault(-1)
            if (code >= 0) break
            Thread.sleep(120)
        }
        if (code < 0) runCatching { process.destroy() }
        tOut.join(500)
        tErr.join(500)
        ShellResult(code, out.toString().trimEnd())
    }

    /** Shizuku 13 把 newProcess 收紧为内部方法，这里用反射调用（Java 层私有，不涉及隐藏 API 名单）。 */
    private fun newShellProcess(command: String): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        ).apply { isAccessible = true }
        return method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
    }

    /** 便捷：截屏到应用缓存并返回文件路径。 */
    suspend fun screenshot(cacheDir: File, name: String = "shot-${System.currentTimeMillis()}.png"): String? {
        val target = File(cacheDir, name)
        val tmp = "/data/local/tmp/wb-$name"
        val r = exec("screencap -p '$tmp' && cp '$tmp' '${target.absolutePath}' && rm -f '$tmp'")
        return if (r.code == 0 && target.isFile && target.length() > 0) target.absolutePath else null
    }
}
