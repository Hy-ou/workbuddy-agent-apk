package com.workbuddy2api.panel.dsh

import android.content.Context
import android.net.Uri
import com.workbuddy2api.panel.BuildConfig
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * DSH 数据备份 / 还原（zip，经 SAF 由用户选择位置）。
 *
 * 包含：`dshhome`（会话/插件/凭证）、原生助手会话、网关配置、我的补丁。
 * 注意：备份内含凭证与 API Key，请妥善保存。
 */
object DshBackup {

    private const val META = "wb-backup.json"
    private val SKIP_DIRS = setOf("dshhome/logs")

    data class ExportResult(val files: Int, val bytes: Long)

    fun export(context: Context, homeDir: File, uri: Uri): Result<ExportResult> = runCatching {
        var files = 0
        var bytes = 0L
        context.contentResolver.openOutputStream(uri)?.use { os ->
            ZipOutputStream(os.buffered()).use { zos ->
                fun put(name: String, src: File) {
                    if (!src.isFile) return
                    zos.putNextEntry(ZipEntry(name))
                    src.inputStream().buffered().use { it.copyTo(zos) }
                    zos.closeEntry()
                    files++
                    bytes += src.length()
                }

                // meta
                val meta = JSONObject()
                    .put("app", "WorkBuddy")
                    .put("versionName", BuildConfig.VERSION_NAME)
                    .put("exportedAt", System.currentTimeMillis())
                    .toString(2)
                zos.putNextEntry(ZipEntry(META))
                zos.write(meta.toByteArray())
                zos.closeEntry()

                // dshhome（跳过日志）
                val dshHome = File(homeDir, "dshhome")
                if (dshHome.isDirectory) {
                    dshHome.walkTopDown()
                        .filter { it.isFile }
                        .filter { f -> SKIP_DIRS.none { f.relativeTo(homeDir).path.replace('\\', '/').startsWith("$it/") } }
                        .forEach { f ->
                            put("dshhome/" + f.relativeTo(dshHome).path.replace('\\', '/'), f)
                        }
                }
                File(homeDir, "user-patch").walkTopDown().filter { it.isFile }.forEach { f ->
                    put("user-patch/" + f.relativeTo(File(homeDir, "user-patch")).path.replace('\\', '/'), f)
                }
                // 原生助手会话 + 网关配置（存在才带）
                put("app/agent-sessions.json", File(context.filesDir, "agent/sessions.json"))
                put("app/gateway-config.json", File(context.filesDir, "gateway/config.json"))
            }
        } ?: error("无法写入所选位置")
        ExportResult(files, bytes)
    }

    data class ImportResult(val files: Int, val backupDir: String)

    fun import(context: Context, homeDir: File, uri: Uri): Result<ImportResult> = runCatching {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val staging = File(homeDir, "restore-staging")
        staging.deleteRecursively()
        staging.mkdirs()
        var files = 0
        context.contentResolver.openInputStream(uri)?.use { ins ->
            ZipInputStream(ins.buffered()).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    if (e.isDirectory) { zis.closeEntry(); continue }
                    val name = e.name
                    if (name.contains("..")) { zis.closeEntry(); continue }
                    val dst = File(staging, name)
                    if (!dst.canonicalPath.startsWith(staging.canonicalPath)) { zis.closeEntry(); continue }
                    dst.parentFile?.mkdirs()
                    dst.outputStream().buffered().use { zis.copyTo(it) }
                    files++
                    zis.closeEntry()
                }
            }
        } ?: error("无法读取所选文件")
        if (!File(staging, META).isFile) error("不是 WorkBuddy 备份（缺少 $META）")

        // 备份现有 dshhome 后替换
        val dshHome = File(homeDir, "dshhome")
        val backup = File(homeDir, "dshhome.bak-$stamp")
        if (dshHome.isDirectory) {
            runCatching { dshHome.copyRecursively(backup, overwrite = true) }
        }
        val stagedHome = File(staging, "dshhome")
        if (stagedHome.isDirectory) {
            dshHome.deleteRecursively()
            stagedHome.copyRecursively(dshHome, overwrite = true)
        }
        runCatching {
            File(staging, "user-patch").takeIf { it.isDirectory }?.let { src ->
                val dst = File(homeDir, "user-patch")
                dst.deleteRecursively()
                src.copyRecursively(dst, overwrite = true)
            }
            File(staging, "app/agent-sessions.json").takeIf { it.isFile }?.let { src ->
                val dst = File(context.filesDir, "agent/sessions.json")
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
            }
            File(staging, "app/gateway-config.json").takeIf { it.isFile }?.let { src ->
                val dst = File(context.filesDir, "gateway/config.json")
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
            }
        }
        staging.deleteRecursively()
        ImportResult(files, backup.absolutePath)
    }
}
