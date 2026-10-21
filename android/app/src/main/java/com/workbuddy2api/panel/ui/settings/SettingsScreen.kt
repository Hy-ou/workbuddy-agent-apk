package com.workbuddy2api.panel.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.workbuddy2api.panel.data.Session
import com.workbuddy2api.panel.data.WbRepository
import com.workbuddy2api.panel.ui.components.AllFilesAccessCard
import com.workbuddy2api.panel.ui.components.ConfirmDialog
import com.workbuddy2api.panel.ui.components.FilterChips
import com.workbuddy2api.panel.ui.components.InfoRow
import com.workbuddy2api.panel.ui.components.SectionHeader
import com.workbuddy2api.panel.ui.components.Tone
import com.workbuddy2api.panel.ui.components.WbButton
import com.workbuddy2api.panel.ui.components.WbCard
import com.workbuddy2api.panel.ui.components.WbTopBar
import com.workbuddy2api.panel.ui.state.Loadable
import com.workbuddy2api.panel.ui.state.rememberLoader
import com.workbuddy2api.panel.ui.theme.LocalWbColors
import com.workbuddy2api.panel.ui.theme.WbDimens
import com.workbuddy2api.panel.ui.theme.WbMono
import com.workbuddy2api.panel.util.maskKey

/** 外观选项，下标即 [FilterChips] 的选中下标；值直接写回 Session.themeMode。 */
private val THEME_LABELS = listOf("深色", "浅色", "跟随系统")
private val THEME_MODES = listOf("dark", "light", "system")

/** 自动刷新间隔选项（秒）。 */
private val REFRESH_LABELS = listOf("3s", "5s", "10s", "30s")
private val REFRESH_SECONDS = listOf(3, 5, 10, 30)

/** 应用自身版本号（与 build.gradle.kts 的 versionName 保持一致）。 */
private const val APP_VERSION = "3.9.0"

/**
 * 设置页：连接信息 / 外观 / 刷新间隔 / 二级功能入口 / 关于。
 *
 * 本屏只负责展示与回调，所有状态由 shell（AppViewModel + Session）持有；
 * 二级页面通过 [onOpenConfig] 等回调交给 shell 切换路由。
 */
@Composable
fun SettingsScreen(
    repo: WbRepository,
    session: Session,
    onOpenConfig: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenPackages: () -> Unit,
    onOpenProbes: () -> Unit,
    onOpenVouchers: () -> Unit,
    onDisconnect: () -> Unit,
    onThemeChange: (String) -> Unit,
    onRefreshChange: (Int) -> Unit,
    onAssistantModeChange: (String) -> Unit,
) {
    val colors = LocalWbColors.current
    var confirmDisconnect by remember { mutableStateOf(false) }

    // 服务端版本是「锦上添花」的信息：失败一律吞掉，不让设置页变成错误页。
    val versionLoader = rememberLoader("settings-server-version", repo) {
        runCatching { repo.overview().version.trim() }.getOrDefault("")
    }
    val versionState = versionLoader.state
    val serverVersion = (versionState as? Loadable.Ready<String>)?.data.orEmpty()

    val themeIndex = THEME_MODES.indexOf(session.themeMode).takeIf { it >= 0 } ?: 0
    val refreshIndex = REFRESH_SECONDS.indexOf(session.refreshSec).takeIf { it >= 0 } ?: 1

    Column(modifier = Modifier.fillMaxSize()) {
        WbTopBar(title = "设置", subtitle = repo.serverLabel.ifBlank { "未连接" })

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = WbDimens.screenPadding),
        ) {
            Spacer(Modifier.height(4.dp))

            // ── 连接 ────────────────────────────────────────────────
            SectionHeader(title = "连接", subtitle = "当前会话使用的服务器")
            WbCard {
                FieldLabel("服务器")
                Spacer(Modifier.height(2.dp))
                Text(
                    text = repo.serverLabel.ifBlank { session.serverUrl.ifBlank { "未连接" } },
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = WbMono),
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                FieldLabel("API Key")
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (session.apiKey.isBlank()) "未设置（服务端免鉴权）" else maskKey(session.apiKey),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = WbMono),
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (session.demo) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "演示模式：当前展示的是本地示例数据，不会请求任何服务器。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.warn,
                    )
                }
                Spacer(Modifier.height(14.dp))
                WbButton(
                    text = "断开连接",
                    onClick = { confirmDisconnect = true },
                    tone = Tone.Bad,
                )
            }

            Spacer(Modifier.height(WbDimens.gap))

            // ── 外观 ────────────────────────────────────────────────
            SectionHeader(title = "外观", subtitle = "深色为默认主题")
            WbCard {
                FilterChips(
                    labels = THEME_LABELS,
                    selectedIndex = themeIndex,
                    onSelect = { onThemeChange(THEME_MODES[it]) },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "选择「跟随系统」后，App 会随系统深色模式自动切换。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.ink3,
                )
            }

            Spacer(Modifier.height(WbDimens.gap))

            // ── 刷新 ────────────────────────────────────────────────
            SectionHeader(title = "刷新", subtitle = "总览与账号列表的自动刷新间隔")
            WbCard {
                FilterChips(
                    labels = REFRESH_LABELS,
                    selectedIndex = refreshIndex,
                    onSelect = { onRefreshChange(REFRESH_SECONDS[it]) },
                )
            }

            Spacer(Modifier.height(WbDimens.gap))

            // ── 文件访问 ────────────────────────────────────────────
            SectionHeader(title = "文件访问", subtitle = "DSH 内核检索、操作手机文件所需")
            AllFilesAccessCard()

            Spacer(Modifier.height(WbDimens.gap))
            // ── 运行体检 ────────────────────────────────────────────
            SectionHeader(title = "运行环境", subtitle = "电池白名单、WebView、架构与内核位置")
            DiagnosticsCard()

            Spacer(Modifier.height(WbDimens.gap))
            // ── 扩展能力 ────────────────────────────────────────────
            SectionHeader(title = "扩展能力", subtitle = "AI 通知 / Shizuku 特权 / 工具链 / 社区插件")
            ExtensionsCard()

            Spacer(Modifier.height(WbDimens.gap))
            // ── DSH 工具 ────────────────────────────────────────────
            SectionHeader(title = "DSH 工具", subtitle = "插件开关 / 安全模式 / 备份 / 我的补丁")
            DshToolsCard()

            Spacer(Modifier.height(WbDimens.gap))
            // ── 助手引擎 ────────────────────────────────────────────
            SectionHeader(title = "助手引擎", subtitle = "「助手」页使用的对话内核")
            WbCard {
                FilterChips(
                    labels = listOf("原生助手", "DSH 内核"),
                    selectedIndex = if (session.assistantMode == "dsh") 1 else 0,
                    onSelect = { onAssistantModeChange(if (it == 1) "dsh" else "native") },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "原生助手：直连本机网关，轻量省电。DSH 内核：以独立 Node 进程运行完整的 " +
                        "DeepSeek Harness 控制台（会话、工具、终端），首次启用会解压内核，占用更多存储与内存。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.ink3,
                )
            }

            Spacer(Modifier.height(WbDimens.gap))
            // ── 服务器功能入口 ──────────────────────────────────────
            SectionHeader(title = "服务器", subtitle = "打开服务端的配置与统计页面")
            val navItems = listOf(
                Triple("服务器配置", "编辑服务端配置（含密钥，界面已打码）", onOpenConfig),
                Triple("用量统计", "按时间范围查看请求、Token 与延迟", onOpenUsage),
                Triple("积分套餐", "各账号的积分余额与套餐明细", onOpenPackages),
                Triple("模型探测", "各模型声称与实际输出上限", onOpenProbes),
                Triple("校园券码", "校园活动抽到的券码与有效期", onOpenVouchers),
            )
            navItems.forEachIndexed { index, item ->
                if (index > 0) Spacer(Modifier.height(WbDimens.gap))
                NavCard(title = item.first, subtitle = item.second, onClick = item.third)
            }

            Spacer(Modifier.height(WbDimens.gap))

            // ── 关于 ────────────────────────────────────────────────
            SectionHeader(title = "关于")
            WbCard {
                InfoRow(label = "应用版本", value = APP_VERSION, mono = true)
                InfoRow(
                    label = "服务端版本",
                    value = when {
                        serverVersion.isNotBlank() -> serverVersion
                        versionState is Loadable.Failed -> "未知"
                        repo.isDemo -> "演示数据"
                        else -> "读取中…"
                    },
                    mono = true,
                )
                InfoRow(label = "数据来源", value = if (repo.isDemo) "本地演示数据" else "自建服务器", mono = false)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "WorkBuddy 面板的 Android 原生客户端：手机优先的单列卡片布局，只为替代网页版面板而做。" +
                        "所有数据都通过本机或自建服务器的 /panel/api 接口获取，不经过任何第三方。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.ink3,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDisconnect) {
        ConfirmDialog(
            title = "断开连接",
            text = "断开后需要重新填写服务器地址与 API Key 才能继续使用；外观与刷新偏好会保留。",
            confirmText = "断开",
            destructive = true,
            onConfirm = {
                confirmDisconnect = false
                onDisconnect()
            },
            onDismiss = { confirmDisconnect = false },
        )
    }
}

/** 卡片内的小号字段标题（灰色），比 [InfoRow] 更适合承载长 URL / 密钥。 */
@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = LocalWbColors.current.ink3,
    )
}

/** 可点击的功能入口卡片：标题 + 副标题 + 右侧箭头。 */
@Composable
private fun NavCard(title: String, subtitle: String, onClick: () -> Unit) {
    val colors = LocalWbColors.current
    WbCard(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.ink3,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
