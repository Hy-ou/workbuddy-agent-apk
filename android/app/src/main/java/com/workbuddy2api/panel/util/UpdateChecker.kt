package com.workbuddy2api.panel.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 检查 GitHub Releases 上的新版本。
 *
 * 只做「读 latest release + 版本比较」，不下载、不静默安装；有新版本时给出链接由用户决定。
 */
object UpdateChecker {

    const val REPO = "Hy-ou/workbuddy-agent-apk"

    data class Release(
        val tag: String,
        val name: String,
        val url: String,
        val body: String,
    )

    private val client by lazy { OkHttpClient() }

    suspend fun fetchLatest(repo: String = REPO): Result<Release> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://api.github.com/repos/$repo/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "WorkBuddy-Android")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val json = JSONObject(resp.body?.string().orEmpty())
                Release(
                    tag = json.optString("tag_name").trim(),
                    name = json.optString("name").ifBlank { json.optString("tag_name") },
                    url = json.optString("html_url"),
                    body = json.optString("body"),
                )
            }
        }
    }

    /** latest 是否比 current 新（按数字段比较，容忍 v 前缀与后缀）。 */
    fun isNewer(latest: String, current: String): Boolean =
        compare(normalize(latest), normalize(current)) > 0

    private fun normalize(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V")
            .split('.', '-', '+', ' ')
            .mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }

    private fun compare(a: List<Int>, b: List<Int>): Int {
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}
