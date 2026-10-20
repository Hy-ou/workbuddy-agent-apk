package com.workbuddy2api.panel.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 本地连接与偏好设置。 */
data class Session(
    val serverUrl: String = "",
    val apiKey: String = "",
    val demo: Boolean = false,
    /** true 表示连接的是 App 内置网关（127.0.0.1，随 App 启停）。 */
    val local: Boolean = false,
    val themeMode: String = "dark",
    /** 助手引擎："native" 原生对话 / "dsh" DSH 内核（独立 node 进程）。 */
    val assistantMode: String = "native",
    val refreshSec: Int = 5,
) {
    val configured: Boolean get() = demo || serverUrl.isNotBlank()
}

private val Context.wbDataStore by preferencesDataStore(name = "wb_settings")

/** 用 DataStore 持久化 Session（API Key 仅存本机私有目录）。 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val serverUrl = stringPreferencesKey("server_url")
        val apiKey = stringPreferencesKey("api_key")
        val demo = booleanPreferencesKey("demo")
        val local = booleanPreferencesKey("local")
        val themeMode = stringPreferencesKey("theme_mode")
        val refreshSec = intPreferencesKey("refresh_sec")
        val assistantMode = stringPreferencesKey("assistant_mode")
    }

    val flow: Flow<Session> = context.wbDataStore.data.map { p ->
        Session(
            serverUrl = p[Keys.serverUrl].orEmpty(),
            apiKey = p[Keys.apiKey].orEmpty(),
            demo = p[Keys.demo] ?: false,
            local = p[Keys.local] ?: false,
            themeMode = p[Keys.themeMode] ?: "dark",
            refreshSec = p[Keys.refreshSec] ?: 5,
            assistantMode = p[Keys.assistantMode] ?: "native",
        )
    }

    suspend fun load(): Session = flow.first()

    suspend fun save(s: Session) {
        context.wbDataStore.edit { p ->
            p[Keys.serverUrl] = s.serverUrl
            p[Keys.apiKey] = s.apiKey
            p[Keys.demo] = s.demo
            p[Keys.local] = s.local
            p[Keys.themeMode] = s.themeMode
            p[Keys.refreshSec] = s.refreshSec
            p[Keys.assistantMode] = s.assistantMode
        }
    }
}
