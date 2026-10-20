package com.workbuddy2api.panel.dsh

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 第三方（社区）插件登记表：记录通过一键安装装入内核的 npm 插件，
 * 并让 `cordis.patch.yml` 自动 insert 它们。
 */
object InstalledPlugins {

    private const val PREFS = "wb_dsh_plugins"
    private const val KEY = "installed"

    data class Entry(val id: String, val packageName: String)

    fun list(context: Context): List<Entry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(Entry(o.optString("id"), o.optString("pkg")))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun add(context: Context, id: String, packageName: String) {
        val next = list(context).filterNot { it.packageName == packageName } + Entry(id, packageName)
        save(context, next)
    }

    fun remove(context: Context, packageName: String) {
        save(context, list(context).filterNot { it.packageName == packageName })
    }

    private fun save(context: Context, items: List<Entry>) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("id", it.id).put("pkg", it.packageName)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
