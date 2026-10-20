package com.workbuddy2api.panel.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.workbuddy2api.panel.data.AppViewModel
import com.workbuddy2api.panel.gateway.GatewayStatus
import com.workbuddy2api.panel.data.normalizeServerUrl
import com.workbuddy2api.panel.ui.components.WbButton
import com.workbuddy2api.panel.ui.components.WbCard
import com.workbuddy2api.panel.ui.components.WbTextField
import com.workbuddy2api.panel.ui.theme.LocalWbColors

/**
 * 连接页：首启 / 断开连接后展示。
 *
 * 手机端一次性输入服务器地址与 API Key，连接成功后全部界面都在本地渲染，
 * 不再依赖浏览器；也提供「演示模式」直接体验完整界面。
 */
@Composable
fun ConnectScreen(vm: AppViewModel) {
    val wb = LocalWbColors.current
    val connecting by vm.connecting.collectAsStateWithLifecycle()
    val error by vm.connectError.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val gw by vm.gatewayStatus.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current

    var url by rememberSaveable { mutableStateOf("") }
    var key by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(session) {
        val s = session
        if (s != null && s.serverUrl.isNotBlank() && url.isBlank()) {
            url = s.serverUrl
            key = s.apiKey
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(wb.bg, wb.bg, wb.surface.copy(alpha = 0.6f)),
                ),
            ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(36.dp))
            Box(
                Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(wb.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "W",
                    color = wb.accent,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 38.sp,
                )
            }
            Spacer(Modifier.height(20.dp))
            Text("WorkBuddy 控制台", style = MaterialTheme.typography.headlineMedium, color = wb.ink)
            Spacer(Modifier.height(6.dp))
            Text(
                "账号池 · 自动任务 · 请求审计",
                style = MaterialTheme.typography.bodyMedium,
                color = wb.ink3,
            )
            Spacer(Modifier.height(32.dp))

            val gwText = when (val g = gw) {
                is GatewayStatus.Starting -> g.message
                is GatewayStatus.Running -> "网关已就绪：127.0.0.1:${g.port}"
                is GatewayStatus.Failed -> g.message
                else -> null
            }
            WbCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Bolt, contentDescription = null, tint = wb.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("本地网关（推荐）", style = MaterialTheme.typography.titleMedium, color = wb.ink)
                        Text("在手机里直接运行网关，无需另开服务器", style = MaterialTheme.typography.bodySmall, color = wb.ink3)
                    }
                }
                Spacer(Modifier.height(14.dp))
                WbButton(
                    text = if (connecting) "正在启动…" else "一键启动本地网关",
                    onClick = { focus.clearFocus(); vm.startLocalGateway() },
                    primary = true,
                    enabled = !connecting,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (gwText != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        gwText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (gw is GatewayStatus.Failed) wb.bad else wb.ink3,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            WbCard(Modifier.fillMaxWidth()) {
                Text("连接远程服务器", style = MaterialTheme.typography.titleMedium, color = wb.ink)
                Spacer(Modifier.height(4.dp))
                Text(
                    "填写面板所在服务器地址；API Key 可在服务端 config.json 的 api_key 找到。",
                    style = MaterialTheme.typography.bodySmall,
                    color = wb.ink3,
                )
                Spacer(Modifier.height(16.dp))
                WbTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = "服务器地址",
                    placeholder = "192.168.1.10:7863",
                    keyboardType = KeyboardType.Uri,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                WbTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = "API Key",
                    placeholder = "sk-…（服务端未启用鉴权可留空）",
                    password = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Info, contentDescription = null, tint = wb.bad, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(error!!, color = wb.bad, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(18.dp))
                WbButton(
                    text = if (connecting) "正在连接…" else "连接服务器",
                    onClick = {
                        focus.clearFocus()
                        vm.connect(normalizeServerUrl(url), key)
                    },
                    primary = true,
                    enabled = !connecting && url.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (connecting) {
                    Spacer(Modifier.height(10.dp))
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = wb.accent)
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f).height(1.dp).background(wb.line))
                Text("或", color = wb.ink3, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp))
                Box(Modifier.weight(1f).height(1.dp).background(wb.line))
            }
            Spacer(Modifier.height(16.dp))
            WbButton(
                text = "先看看演示模式",
                onClick = { focus.clearFocus(); vm.enterDemo() },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Outlined.Dns,
                    contentDescription = null,
                    tint = wb.ink3,
                    modifier = Modifier.size(14.dp).padding(top = 2.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "地址示例：http://10.0.0.5:7863；同一局域网内可直接用内网 IP。\n" +
                        "支持 http / https，App 不会把密钥上传到其他服务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = wb.ink3,
                )
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}
