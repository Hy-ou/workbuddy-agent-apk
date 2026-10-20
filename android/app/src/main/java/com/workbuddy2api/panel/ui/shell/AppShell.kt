package com.workbuddy2api.panel.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.workbuddy2api.panel.data.AppViewModel
import com.workbuddy2api.panel.data.Session
import com.workbuddy2api.panel.data.WbRepository
import com.workbuddy2api.panel.ui.accounts.AccountsScreen
import com.workbuddy2api.panel.dsh.DshManager
import com.workbuddy2api.panel.ui.components.FilterChips
import com.workbuddy2api.panel.ui.dsh.DshScreen
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import com.workbuddy2api.panel.ui.assistant.AssistantScreen
import com.workbuddy2api.panel.ui.components.LocalSnackbar
import com.workbuddy2api.panel.ui.components.WbButton
import com.workbuddy2api.panel.ui.logs.LogsScreen
import com.workbuddy2api.panel.ui.overview.OverviewScreen
import com.workbuddy2api.panel.ui.settings.ConfigScreen
import com.workbuddy2api.panel.ui.settings.PackagesScreen
import com.workbuddy2api.panel.ui.settings.ProbesScreen
import com.workbuddy2api.panel.ui.settings.SettingsScreen
import com.workbuddy2api.panel.ui.settings.UsageScreen
import com.workbuddy2api.panel.ui.settings.VouchersScreen
import com.workbuddy2api.panel.ui.tasks.TasksScreen
import com.workbuddy2api.panel.ui.theme.LocalWbColors

private data class TabItem(val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem("总览", Icons.Outlined.Dashboard),
    TabItem("账号", Icons.Outlined.Dns),
    TabItem("助手", Icons.Outlined.AutoAwesome),
    TabItem("任务", Icons.Outlined.TaskAlt),
    TabItem("日志", Icons.Outlined.Article),
    TabItem("设置", Icons.Outlined.Settings),
)

/** 二级页面路由（设置里的功能页）。 */
private enum class Route { Main, Config, Usage, Packages, Probes, Vouchers }

/**
 * App 主壳：底部 6 Tab（总览 / 账号 / 助手 / 任务 / 日志 / 设置）+ 二级页面。
 *
 * 屏幕自身不建 Scaffold，只出内容与顶部栏；这里统一提供 Snackbar 宿主。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(vm: AppViewModel, session: Session, repo: WbRepository, initialTab: Int = 0, initialMode: String = "") {
    val wb = LocalWbColors.current
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, tabs.size - 1)) }
    // 调试直达：带 tab/mode extra 启动时（如 adb am start --ei tab 2）强制切到目标页，
    // 否则 rememberSaveable 会恢复上一次的 Tab。
    LaunchedEffect(Unit) {
        if (initialTab != 0 || initialMode.isNotBlank()) tab = initialTab.coerceIn(0, tabs.size - 1)
    }
    var route by rememberSaveable { mutableStateOf(Route.Main) }
    var showGatewayLogs by remember { mutableStateOf(false) }

    val agentClient by vm.agentClient.collectAsStateWithLifecycle()
    val gatewayStatus by vm.gatewayStatus.collectAsStateWithLifecycle()

    BackHandler(enabled = route != Route.Main) { route = Route.Main }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = wb.bg,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (route == Route.Main) {
                NavigationBar(containerColor = wb.surface, tonalElevation = 0.dp) {
                    tabs.forEachIndexed { index, item ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = wb.accent,
                                selectedTextColor = wb.accent,
                                indicatorColor = wb.accentSoft,
                                unselectedIconColor = wb.ink3,
                                unselectedTextColor = wb.ink3,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        CompositionLocalProvider(LocalSnackbar provides snackbar) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(bottom = padding.calculateBottomPadding()),
            ) {
                when (route) {
                    Route.Main -> when (tab) {
                        0 -> OverviewScreen(
                            repo = repo,
                            onGoAccounts = { tab = 1 },
                            onGoLogs = { tab = 4 },
                        )
                        1 -> AccountsScreen(repo)
                        2 -> {
                            // 引擎切换放在「设置 → 助手引擎」；intent extra mode 仅用于调试直达。
                            val engine = initialMode.ifBlank { session.assistantMode }
                            if (engine == "dsh") {
                                val dsh = remember { DshManager.get(vm.getApplication()) }
                                DshScreen(manager = dsh, gatewayApiKey = session.apiKey)
                            } else {
                                AssistantScreen(
                                    client = agentClient,
                                    store = vm.agentStore,
                                    gatewayStatus = gatewayStatus,
                                    onStartGateway = { vm.startLocalGateway() },
                                    onRestartGateway = { vm.restartLocalGateway() },
                                    onOpenAccounts = { tab = 1 },
                                    onOpenLogs = { tab = 4 },
                                    onOpenGatewayLogs = { showGatewayLogs = true },
                                )
                            }
                        }
                        3 -> TasksScreen(repo)
                        4 -> LogsScreen(repo)
                        else -> SettingsScreen(
                            repo = repo,
                            session = session,
                            onOpenConfig = { route = Route.Config },
                            onOpenUsage = { route = Route.Usage },
                            onOpenPackages = { route = Route.Packages },
                            onOpenProbes = { route = Route.Probes },
                            onOpenVouchers = { route = Route.Vouchers },
                            onDisconnect = { vm.disconnect() },
                            onThemeChange = { vm.setTheme(it) },
                            onRefreshChange = { vm.setRefresh(it) },
                            onAssistantModeChange = { vm.setAssistantMode(it) },
                        )
                    }
                    Route.Config -> ConfigScreen(repo) { route = Route.Main }
                    Route.Usage -> UsageScreen(repo) { route = Route.Main }
                    Route.Packages -> PackagesScreen(repo) { route = Route.Main }
                    Route.Probes -> ProbesScreen(repo) { route = Route.Main }
                    Route.Vouchers -> VouchersScreen(repo) { route = Route.Main }
                }
            }
        }
    }

    if (showGatewayLogs) {
        ModalBottomSheet(
            onDismissRequest = { showGatewayLogs = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = wb.surface,
        ) {
            GatewayLogSheet(vm, onClose = { showGatewayLogs = false })
        }
    }
}

@Composable
private fun GatewayLogSheet(vm: AppViewModel, onClose: () -> Unit) {
    val wb = LocalWbColors.current
    var text by remember { mutableStateOf(vm.gatewayLogTail()) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Text("网关日志", style = MaterialTheme.typography.titleMedium, color = wb.ink)
        Text(
            "内置网关的标准输出与错误（最近 120 行）",
            style = MaterialTheme.typography.bodySmall,
            color = wb.ink3,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 10.dp),
        ) {
            Text(
                text.ifBlank { "暂无日志" },
                style = MaterialTheme.typography.bodySmall,
                color = wb.ink2,
                fontFamily = FontFamily.Monospace,
            )
        }
        RowActions(
            onRefresh = { text = vm.gatewayLogTail() },
            onRestart = { vm.restartLocalGateway(); onClose() },
            onClose = onClose,
        )
    }
}

@Composable
private fun RowActions(onRefresh: () -> Unit, onRestart: () -> Unit, onClose: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WbButton("刷新", onRefresh, Modifier.weight(1f))
        WbButton("重启网关", onRestart, Modifier.weight(1f), primary = true)
        WbButton("关闭", onClose, Modifier.weight(1f))
    }
}
