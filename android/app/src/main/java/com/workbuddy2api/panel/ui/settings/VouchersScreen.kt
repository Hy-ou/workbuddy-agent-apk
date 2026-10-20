package com.workbuddy2api.panel.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.workbuddy2api.panel.data.SchoolVoucher
import com.workbuddy2api.panel.data.SchoolVoucherAccount
import com.workbuddy2api.panel.data.SchoolVouchersPage
import com.workbuddy2api.panel.data.WbRepository
import com.workbuddy2api.panel.ui.components.EmptyState
import com.workbuddy2api.panel.ui.components.ErrorBox
import com.workbuddy2api.panel.ui.components.LoadingBox
import com.workbuddy2api.panel.ui.components.RealmBadge
import com.workbuddy2api.panel.ui.components.StatusChip
import com.workbuddy2api.panel.ui.components.Tone
import com.workbuddy2api.panel.ui.components.WbCard
import com.workbuddy2api.panel.ui.components.WbPullRefresh
import com.workbuddy2api.panel.ui.components.WbTopBar
import com.workbuddy2api.panel.ui.state.Loadable
import com.workbuddy2api.panel.ui.state.rememberLoader
import com.workbuddy2api.panel.ui.theme.LocalWbColors
import com.workbuddy2api.panel.ui.theme.WbDimens
import com.workbuddy2api.panel.ui.theme.WbMono
import com.workbuddy2api.panel.util.parseIso
import com.workbuddy2api.panel.util.shortUid
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val VOUCHER_DATE: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())

/** 券码日期：解析失败（含 Go 零值时间）显示 `—`。 */
private fun voucherDateText(s: String): String {
    val instant = parseIso(s) ?: return "—"
    return VOUCHER_DATE.format(instant)
}

/** 是否已过期：没有有效期的券按「不过期」处理，避免误标红。 */
private fun isVoucherExpired(validTo: String): Boolean {
    val instant = parseIso(validTo) ?: return false
    return instant.isBefore(Instant.now())
}

/**
 * 校园券码页：按账号展示抽到的券码、有效期与领取时间。
 *
 * 券码可直接长按复制（[SelectionContainer]）；单个账号失败只影响自己的卡片。
 */
@Composable
fun VouchersScreen(repo: WbRepository, onBack: () -> Unit) {
    val colors = LocalWbColors.current
    val loader = rememberLoader(repo) { repo.schoolVouchers() }
    val state = loader.state
    val page = (state as? Loadable.Ready<SchoolVouchersPage>)?.data
    val total = page?.accounts?.sumOf { it.vouchers.orEmpty().size } ?: 0
    val subtitle = if (page != null) repo.serverLabel + " · 共 " + total + " 张" else repo.serverLabel

    Column(modifier = Modifier.fillMaxSize()) {
        WbTopBar(
            title = "校园券码",
            subtitle = subtitle,
            onBack = onBack,
            actions = {
                IconButton(
                    onClick = { if (!loader.refreshing) loader.refresh() },
                    enabled = !loader.refreshing,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "刷新券码",
                        tint = colors.ink2,
                    )
                }
            },
        )

        WbPullRefresh(
            refreshing = loader.refreshing,
            onRefresh = { loader.refresh(quiet = true) },
            modifier = Modifier.weight(1f),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = WbDimens.screenPadding,
                    end = WbDimens.screenPadding,
                    top = 4.dp,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(WbDimens.gap),
            ) {
                when {
                    state is Loadable.Failed -> item {
                        ErrorBox(message = state.message, onRetry = { loader.refresh() })
                    }
                    page == null -> item { LoadingBox() }
                    else -> {
                        val accounts = page.accounts
                        if (accounts.isEmpty()) {
                            item {
                                EmptyState(
                                    title = "暂无券码",
                                    subtitle = "校园活动抽奖得到的券码会显示在这里",
                                )
                            }
                        } else {
                            items(accounts) { account -> VoucherAccountCard(account) }
                        }
                    }
                }
            }
        }
    }
}

/** 单账号券码卡片：昵称 + 区服 + 张数 + 券码列表。 */
@Composable
private fun VoucherAccountCard(account: SchoolVoucherAccount) {
    val colors = LocalWbColors.current
    val vouchers = account.vouchers.orEmpty()

    WbCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = account.nickname.ifBlank { shortUid(account.uid) },
                style = MaterialTheme.typography.titleSmall,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            RealmBadge(account.realm)
            Spacer(Modifier.width(6.dp))
            StatusChip(text = vouchers.size.toString() + " 张", tone = Tone.Accent)
        }

        if (account.error.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = account.error,
                style = MaterialTheme.typography.bodySmall,
                color = colors.warn,
            )
        }

        if (vouchers.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (account.error.isBlank()) "该账号暂无券码" else "券码获取失败",
                style = MaterialTheme.typography.bodySmall,
                color = colors.ink3,
            )
        } else {
            vouchers.forEachIndexed { index, voucher ->
                if (index > 0) {
                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(colors.lineSoft),
                    )
                }
                Spacer(Modifier.height(10.dp))
                VoucherRow(voucher)
            }
        }
    }
}

/** 单张券码：奖品名 + 有效期状态 + 可复制券码 + 领取时间。 */
@Composable
private fun VoucherRow(voucher: SchoolVoucher) {
    val colors = LocalWbColors.current
    val expired = isVoucherExpired(voucher.validTo)
    val hasValidTo = voucher.validTo.isNotBlank() && parseIso(voucher.validTo) != null

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = voucher.prizeName.ifBlank { voucher.skuCode.ifBlank { "券码" } },
                style = MaterialTheme.typography.titleSmall,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            when {
                expired -> StatusChip(text = "已过期", tone = Tone.Bad)
                hasValidTo -> StatusChip(
                    text = "有效至 " + voucherDateText(voucher.validTo),
                    tone = Tone.Ok,
                )
                else -> StatusChip(text = "长期有效", tone = Tone.Neutral)
            }
        }

        Spacer(Modifier.height(6.dp))
        SelectionContainer {
            Text(
                text = voucher.code.ifBlank { "—" },
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = WbMono,
                    letterSpacing = 0.5.sp,
                ),
                color = if (expired) colors.ink3 else colors.ink,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "领取 " + voucherDateText(voucher.grantedAt),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = WbMono),
                color = colors.ink3,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            if (voucher.validFrom.isNotBlank() && parseIso(voucher.validFrom) != null) {
                Text(
                    text = "生效 " + voucherDateText(voucher.validFrom),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = WbMono),
                    color = colors.ink3,
                    maxLines = 1,
                )
            }
        }
    }
}
