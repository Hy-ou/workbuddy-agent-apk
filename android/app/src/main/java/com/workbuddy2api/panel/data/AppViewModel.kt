package com.workbuddy2api.panel.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.workbuddy2api.panel.data.agent.AgentStore
import com.workbuddy2api.panel.data.agent.OpenAiClient
import com.workbuddy2api.panel.gateway.GatewayStatus
import com.workbuddy2api.panel.gateway.LocalGatewayManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * 应用级状态：连接会话 + 当前仓库 + 内置网关 + Agent 客户端。
 *
 * 两种连接模式：
 * - 本地网关（[Session.local]）：App 自己拉起内置 Go 服务，监听 127.0.0.1，无需外部服务器；
 * - 远程服务器：连接已有的 workbuddy2api 实例。
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)
    private val gateway = LocalGatewayManager.get(app)

    /** Agent 会话存储（进程内单例，随 App 生命周期）。 */
    val agentStore: AgentStore = AgentStore(app)

    val gatewayStatus: StateFlow<GatewayStatus> = gateway.status

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    private val _repo = MutableStateFlow<WbRepository?>(null)
    val repo: StateFlow<WbRepository?> = _repo.asStateFlow()

    private val _agentClient = MutableStateFlow<OpenAiClient?>(null)
    val agentClient: StateFlow<OpenAiClient?> = _agentClient.asStateFlow()

    private val _connecting = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = _connecting.asStateFlow()

    private val _connectError = MutableStateFlow<String?>(null)
    val connectError: StateFlow<String?> = _connectError.asStateFlow()

    init {
        viewModelScope.launch {
            val s = runCatching { settings.load() }.getOrElse { Session() }
            _session.value = s
            if (!s.configured) return@launch
            if (s.demo) {
                _repo.value = DemoWbRepository()
                return@launch
            }
            if (s.local) {
                // 本地模式：启动内置网关（端口可能因上次占用而变化，以实际状态为准）
                val st = gateway.start()
                if (st is GatewayStatus.Running) {
                    attachSession(localUrl(st.port), st.apiKey, local = true, s)
                } else {
                    _connectError.value = (st as? GatewayStatus.Failed)?.message
                }
            } else {
                attachSession(s.serverUrl, s.apiKey, local = false, s)
            }
        }
    }

    /** 连接远程服务器（校验方式：请求一次 overview）。 */
    fun connect(url: String, key: String) {
        if (_connecting.value) return
        viewModelScope.launch {
            _connecting.value = true
            _connectError.value = null
            val normalized = normalizeServerUrl(url)
            if (normalized.isBlank()) {
                _connectError.value = "请填写服务器地址"
                _connecting.value = false
                return@launch
            }
            val candidate = RemoteWbRepository(normalized, key.trim())
            try {
                withTimeout(25_000) { candidate.overview() }
                val old = _session.value ?: Session()
                val next = old.copy(
                    serverUrl = normalized,
                    apiKey = key.trim(),
                    demo = false,
                    local = false,
                )
                settings.save(next)
                closeRepo()
                _session.value = next
                _repo.value = candidate
                _agentClient.value = OpenAiClient(normalized, key.trim())
            } catch (e: Exception) {
                candidate.close()
                _connectError.value = e.message?.takeIf { it.isNotBlank() } ?: "连接失败，请检查地址与 API Key"
            } finally {
                _connecting.value = false
            }
        }
    }

    /** 启动内置网关并直接连上它（一键本地模式）。 */
    fun startLocalGateway() {
        if (_connecting.value) return
        viewModelScope.launch {
            _connecting.value = true
            _connectError.value = null
            try {
                val st = gateway.start()
                when (st) {
                    is GatewayStatus.Running -> {
                        val old = _session.value ?: Session()
                        attachSession(localUrl(st.port), st.apiKey, local = true, old)
                        settings.save(_session.value ?: old)
                    }
                    is GatewayStatus.Failed -> _connectError.value = st.message
                    else -> Unit
                }
            } finally {
                _connecting.value = false
            }
        }
    }

    /** 重启内置网关（切换端口/改配置后使用）。 */
    fun restartLocalGateway() {
        if (_connecting.value) return
        viewModelScope.launch {
            _connecting.value = true
            _connectError.value = null
            try {
                val st = gateway.restart()
                if (st is GatewayStatus.Running) {
                    val old = _session.value ?: Session()
                    attachSession(localUrl(st.port), st.apiKey, local = true, old)
                    settings.save(_session.value ?: old)
                } else {
                    _connectError.value = (st as? GatewayStatus.Failed)?.message ?: "网关重启失败"
                }
            } finally {
                _connecting.value = false
            }
        }
    }

    /** 网关日志（排障用）。 */
    fun gatewayLogTail(): String = gateway.logTail()

    fun localGatewayRunning(): Boolean = gateway.isRunning()

    /** 进入演示模式（无需服务器，展示完整手机布局）。 */
    fun enterDemo() {
        viewModelScope.launch {
            val old = _session.value ?: Session()
            val next = old.copy(demo = true, local = false)
            settings.save(next)
            closeRepo()
            _agentClient.value = null
            _repo.value = DemoWbRepository()
            _session.value = next
            _connectError.value = null
        }
    }

    /** 断开连接（本地模式会一并停掉内置网关；保留外观与刷新偏好）。 */
    fun disconnect() {
        viewModelScope.launch {
            val old = _session.value ?: Session()
            val next = old.copy(serverUrl = "", apiKey = "", demo = false, local = false)
            settings.save(next)
            if (old.local) gateway.stop()
            closeRepo()
            _agentClient.value = null
            _repo.value = null
            _session.value = next
            _connectError.value = null
        }
    }

    fun setTheme(mode: String) {
        val next = (_session.value ?: Session()).copy(themeMode = mode)
        _session.value = next
        viewModelScope.launch { settings.save(next) }
    }

    /** 切换助手引擎：native 原生对话 / dsh DSH 内核。 */
    fun setAssistantMode(mode: String) {
        val next = (_session.value ?: Session()).copy(assistantMode = mode)
        _session.value = next
        viewModelScope.launch { settings.save(next) }
    }
    fun setRefresh(sec: Int) {
        val next = (_session.value ?: Session()).copy(refreshSec = sec)
        _session.value = next
        viewModelScope.launch { settings.save(next) }
    }

    private fun attachSession(url: String, key: String, local: Boolean, old: Session) {
        val next = old.copy(serverUrl = url, apiKey = key, demo = false, local = local)
        closeRepo()
        _session.value = next
        _repo.value = RemoteWbRepository(url, key)
        _agentClient.value = OpenAiClient(url, key)
    }

    private fun localUrl(port: Int): String = "http://127.0.0.1:$port"

    private fun closeRepo() {
        val current = _repo.value
        if (current != null && !current.isDemo) {
            runCatching { current.close() }
        }
        _repo.value = null
        runCatching { _agentClient.value?.close() }
        _agentClient.value = null
    }
}
