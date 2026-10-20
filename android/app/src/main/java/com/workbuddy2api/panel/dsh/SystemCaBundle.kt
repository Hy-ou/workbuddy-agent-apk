package com.workbuddy2api.panel.dsh

import android.content.Context
import java.io.File

/**
 * 用系统证书库合成 PEM bundle，供 DSH 内的 curl / git / TLS 客户端使用。
 *
 * 背景：Android 的系统 CA 在 /system/etc/security/cacerts（每个文件一张 PEM 证书），
 * 而 OpenSSL 系工具只认单个 PEM bundle。内置 assets/cacert.pem 只覆盖 Mozilla 根证书，
 * 企业代理 / 自签 CA / 用户手动安装的 CA 都不在其中，因此这里把系统证书合并成 bundle。
 *
 * 带 stamp 缓存（证书数量 + 最新修改时间），系统证书更新后自动重建。
 */
object SystemCaBundle {

    private const val PREFS = "wb_ca_bundle"
    private const val KEY_STAMP = "stamp"

    /** 返回合成后的 bundle 路径；失败返回 null（调用方回退内置 cacert.pem）。 */
    fun ensure(context: Context, targetDir: File): File? {
        val src = File("/system/etc/security/cacerts")
        val certs = src.listFiles()?.filter { it.isFile && it.length() > 0 } ?: return null
        if (certs.isEmpty()) return null

        val newest = certs.maxOf { it.lastModified() }
        val stamp = "${certs.size}:$newest"
        val out = File(targetDir, "cacert-system.pem")
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (out.isFile && out.length() > 1024 && prefs.getString(KEY_STAMP, null) == stamp) {
            return out
        }

        return runCatching {
            val parent = out.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            out.outputStream().buffered().use { os ->
                for (cert in certs) {
                    cert.inputStream().buffered().use { it.copyTo(os) }
                    os.write('\n'.code)
                }
            }
            if (out.length() < 1024) return null
            prefs.edit().putString(KEY_STAMP, stamp).apply()
            out
        }.getOrNull()
    }
}
