package com.workbuddy2api.panel.util

import android.content.Context
import android.util.Log
import java.io.File

/**
 * WebView 渲染进程崩溃转储的「抢救性」拷贝。
 *
 * crashpad 把转储写在 App 私有 cache 下（`cache/WebView/Crashpad/{new,pending,completed}`），
 * 保留多久由 **WebView 提供方 + 系统的缓存清理策略**决定，App 侧没有任何 API 能设成
 * 「保留 7 天」。能做的只有一件事：在崩溃发生的瞬间（`onRenderProcessGone`）抢一份到
 * `filesDir`，别让现场在几十秒内消失。
 *
 * 目标目录：`files/<pkg>/webview-crash/`，文件名带源文件的 mtime，重复调用只会覆盖同一份，
 * 不会无限堆积；超过 [KEEP_DAYS] 天或超过 [MAX_FILES] 个文件的自动清掉。
 */
object WebViewDumps {
    private const val TAG = "WbWebDump"
    private const val KEEP_DAYS = 7L
    private const val MAX_FILES = 40

    /** 扫一遍 cache 里的 crashpad 目录，把非空文件复制到私有目录。失败静默（只记 WARN）。 */
    fun salvage(context: Context) {
        val src = File(context.cacheDir, "WebView/Crashpad")
        if (!src.isDirectory) return
        val dst = File(context.filesDir, "webview-crash")
        runCatching {
            if (!dst.isDirectory && !dst.mkdirs()) return
            prune(dst)
            var copied = 0
            src.walkTopDown().forEach { f ->
                if (copied >= MAX_FILES) return@forEach
                if (!f.isFile || f.length() <= 0L) return@forEach
                // 用源文件的 mtime + 原相对位置命名：重复 salvage 会命中同一个目标名，
                // 靠 overwrite 去重，而不是每次都复制一份新的。
                val name = "${f.lastModified()}-${f.parentFile?.name ?: "root"}-${f.name}"
                val ok = runCatching { f.copyTo(File(dst, name), overwrite = true) }.isSuccess
                if (ok) copied++
            }
            if (copied > 0) Log.i(TAG, "salvaged $copied webview dump file(s) -> ${dst.absolutePath}")
        }.onFailure { Log.w(TAG, "salvage failed", it) }
    }

    /** 留下的转储路径（设置页/诊断卡展示用）。 */
    fun dumpCount(context: Context): Int =
        File(context.filesDir, "webview-crash").listFiles()?.count { it.isFile } ?: 0

    private fun prune(dst: File) {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24 * 3600_000L
        val files = dst.listFiles()?.filter { it.isFile }.orEmpty()
        files.filter { it.lastModified() < cutoff }.forEach { runCatching { it.delete() } }
        val left = dst.listFiles()?.filter { it.isFile }.orEmpty().sortedBy { it.lastModified() }
        if (left.size > MAX_FILES) {
            left.take(left.size - MAX_FILES).forEach { runCatching { it.delete() } }
        }
    }
}
