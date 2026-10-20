package com.workbuddy2api.panel.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.workbuddy2api.panel.ui.theme.LocalWbColors
import com.workbuddy2api.panel.ui.theme.WbDimens
import com.workbuddy2api.panel.ui.theme.WbMono

// ---------------------------------------------------------------------------
// 基础语义
// ---------------------------------------------------------------------------

/** 状态语义色：健康 / 冷却 / 错误 / 强调，UI 只允许用这五种语义描述状态。 */
enum class Tone { Neutral, Ok, Warn, Bad, Accent }

/** 全局 Snackbar 宿主；由 shell 通过 [CompositionLocalProvider] 注入，屏幕里直接取用。 */
val LocalSnackbar: ProvidableCompositionLocal<SnackbarHostState> =
    staticCompositionLocalOf { SnackbarHostState() }

/** 统计小卡片的数据载体，配合 [StatGrid] 使用。 */
data class StatTileData(
    val label: String,
    val value: String,
    val unit: String? = null,
    val tone: Tone = Tone.Neutral,
)

/** 语义色对应的文字颜色；Neutral 用正文色而不是灰，避免数值不可读。 */
@Composable
private fun toneColor(tone: Tone): Color {
    val c = LocalWbColors.current
    return when (tone) {
        Tone.Neutral -> c.ink
        Tone.Ok -> c.ok
        Tone.Warn -> c.warn
        Tone.Bad -> c.bad
        Tone.Accent -> c.accent
    }
}

// ---------------------------------------------------------------------------
// 容器与标题
// ---------------------------------------------------------------------------

/**
 * 面板统一卡片：surface 底 + 细描边 + 16dp 圆角 + 14dp 内边距。
 *
 * 传入 [onClick] 时整张卡片可点击（触控目标天然大于 44dp），否则是纯展示容器。
 */
@Composable
fun WbCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalWbColors.current
    val shape = RoundedCornerShape(WbDimens.cardRadius)
    val base = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(c.surface)
        .border(1.dp, c.lineSoft, shape)
    val interactive = if (onClick != null) base.clickable(onClick = onClick) else base
    Column(modifier = interactive.padding(WbDimens.cardPadding), content = content)
}

/** 分区标题：左侧标题 + 可选副标题，右侧可放「全部 / 刷新」等操作。 */
@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val c = LocalWbColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = c.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) trailing()
    }
}

// ---------------------------------------------------------------------------
// 数值与状态
// ---------------------------------------------------------------------------

/**
 * 2 列统计小卡片：小号灰色标签 + 22sp 等宽数值（按 [tone] 上色）+ 可选单位。
 *
 * 传入 [onClick] 时显示描边并整块可点，用于「点进去看明细」。
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    unit: String? = null,
    tone: Tone = Tone.Neutral,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val c = LocalWbColors.current
    val shape = RoundedCornerShape(12.dp)
    val base = modifier
        .heightIn(min = 72.dp)
        .clip(shape)
        .background(c.surface2)
    val interactive = if (onClick != null) {
        base.border(1.dp, c.line, shape).clickable(onClick = onClick)
    } else {
        base
    }
    Column(
        modifier = interactive.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.5.sp),
            color = c.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = WbMono,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 22.sp,
                ),
                color = toneColor(tone),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (unit != null) {
                Spacer(Modifier.width(3.dp))
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.5.sp),
                    color = c.ink3,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 状态胶囊：10% 同色底 + 同色文字，11.5sp；用于「健康 / 冷却 / 禁用」等。 */
@Composable
fun StatusChip(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val color = toneColor(tone)
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.5.sp),
            color = color,
            maxLines = 1,
        )
    }
}

/** 区服徽标：`cn -> 国内`（灰）、`global -> 国际`（蓝），其他值原样展示。 */
@Composable
fun RealmBadge(realm: String) {
    val c = LocalWbColors.current
    val key = realm.trim().lowercase()
    val text = when (key) {
        "cn" -> "国内"
        "global" -> "国际"
        "" -> "未知"
        else -> realm.trim()
    }
    val color = when (key) {
        "global" -> c.accent
        "cn" -> c.ink3
        else -> c.ink3
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// 空 / 加载 / 错误
// ---------------------------------------------------------------------------

/** 空状态：居中图标 + 标题 + 说明 + 可选操作按钮。 */
@Composable
fun EmptyState(
    title: String,
    subtitle: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = LocalWbColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.Inbox,
            contentDescription = null,
            tint = c.ink3,
            modifier = Modifier.size(38.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = c.ink2,
            textAlign = TextAlign.Center,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = c.ink3,
                textAlign = TextAlign.Center,
            )
        }
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            WbButton(text = actionText, onClick = onAction)
        }
    }
}

/** 加载中：居中转圈 + 「加载中…」。 */
@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    val c = LocalWbColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(26.dp),
            color = c.accent,
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "加载中…",
            style = MaterialTheme.typography.bodySmall,
            color = c.ink3,
        )
    }
}

/** 错误态：红色图标 + 中文错误信息 + 「重试」按钮。 */
@Composable
fun ErrorBox(message: String, onRetry: () -> Unit) {
    val c = LocalWbColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = c.bad,
            modifier = Modifier.size(34.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = message.ifBlank { "加载失败" },
            style = MaterialTheme.typography.bodyMedium,
            color = c.ink2,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        WbButton(text = "重试", onClick = onRetry, primary = true)
    }
}

// ---------------------------------------------------------------------------
// 顶部栏与弹层
// ---------------------------------------------------------------------------

/**
 * 页面顶部栏：52–56dp 高，17sp 半粗标题 + 可选 11.5sp 等宽副标题。
 *
 * 内部自带 [statusBarsPadding]（含状态栏高度），因此屏幕内容根不需要再处理状态栏；
 * 返回按钮为 44dp 触控目标，右侧 [actions] 放刷新 / 更多等图标按钮。
 */
@Composable
fun WbTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = LocalWbColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bg)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .padding(start = if (onBack != null) 4.dp else 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = c.ink2,
                        modifier = Modifier.size(21.dp),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = WbMono,
                            fontSize = 11.5.sp,
                        ),
                        color = c.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    }
}

/** 二次确认弹窗；[destructive] 为 true 时确认按钮用红色（删除 / 移除 / 断开）。 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmText: String = "确定",
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalWbColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        titleContentColor = c.ink,
        textContentColor = c.ink2,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmText,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (destructive) c.bad else c.accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "取消",
                    style = MaterialTheme.typography.labelLarge,
                    color = c.ink3,
                )
            }
        },
    )
}

// ---------------------------------------------------------------------------
// 文本与进度
// ---------------------------------------------------------------------------

/** 「标签 + 值」两行式信息行（手机端替代表格）；[mono] 为 true 时值用等宽字体。 */
@Composable
fun InfoRow(
    label: String,
    value: String,
    mono: Boolean = false,
    valueColor: Color? = null,
) {
    val c = LocalWbColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = c.ink3,
            modifier = Modifier.widthIn(min = 84.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = if (mono) WbMono else FontFamily.Default,
            ),
            color = valueColor ?: c.ink,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 等宽文本：UID、model id、密钥片段、耗时等技术值统一用它。 */
@Composable
fun Mono(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 13.sp,
    color: Color = LocalWbColors.current.ink2,
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = WbMono,
            fontSize = size,
        ),
        color = color,
    )
}

/** 细进度条，[value] 取值 0..1，超出范围自动裁剪。 */
@Composable
fun WbProgress(
    value: Float,
    tone: Tone = Tone.Accent,
    modifier: Modifier = Modifier,
) {
    val c = LocalWbColors.current
    val target = value.coerceIn(0f, 1f)
    val animated by animateFloatAsState(targetValue = target, label = "wbProgress")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(50))
            .background(c.surface2),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(animated)
                .clip(RoundedCornerShape(50))
                .background(toneColor(tone)),
        )
    }
}

// ---------------------------------------------------------------------------
// 交互控件
// ---------------------------------------------------------------------------

/** 横向滚动的筛选胶囊组；选中态为 accentSoft 底 + accent 文字。 */
@Composable
fun FilterChips(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    val c = LocalWbColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            // 外层撑到 44dp 保证触控目标，内层胶囊保持紧凑视觉。
            Box(
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clip(CircleShape)
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (selected) c.accentSoft else c.surface2)
                        .border(1.dp, if (selected) Color.Transparent else c.line, CircleShape)
                        .padding(horizontal = 13.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.5.sp),
                        color = if (selected) c.accent else c.ink2,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 下拉刷新容器：material3 [PullToRefreshBox] 的薄封装，内容自行填满。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WbPullRefresh(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
        content = content,
    )
}

/**
 * 面板按钮：primary 为 accent 实心，其余为 surface2 + 细描边；
 * [tone] 只影响文字颜色（`Tone.Bad` 用于删除类操作），禁用时整体降透明度。
 */
@Composable
fun WbButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    tone: Tone = Tone.Neutral,
) {
    val c = LocalWbColors.current
    val shape = RoundedCornerShape(12.dp)
    val destructivePrimary = primary && tone == Tone.Bad
    val background = when {
        primary && !enabled -> c.accent.copy(alpha = 0.35f)
        destructivePrimary -> c.bad
        primary -> c.accent
        else -> c.surface2
    }
    val textColor = when {
        !enabled -> c.ink3
        destructivePrimary -> MaterialTheme.colorScheme.onError
        primary -> MaterialTheme.colorScheme.onPrimary
        tone != Tone.Neutral -> toneColor(tone)
        else -> c.ink
    }
    val base = modifier
        .heightIn(min = 44.dp)
        .clip(shape)
        .background(background)
    val outlined = if (primary) base else base.border(1.dp, c.line, shape)
    Box(
        modifier = outlined
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 面板输入框：surface2 底 + 细描边，聚焦时描边变 accent；
 * [password] 为 true 时默认打码，右侧眼睛图标可切换明文。
 */
@Composable
fun WbTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    password: Boolean = false,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val c = LocalWbColors.current
    var visible by remember { mutableStateOf(false) }
    val trailing: (@Composable () -> Unit)? = if (password) {
        {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "隐藏密钥" else "显示密钥",
                    tint = c.ink3,
                )
            }
        }
    } else {
        null
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        shape = RoundedCornerShape(12.dp),
        label = {
            Text(text = label, style = MaterialTheme.typography.bodySmall)
        },
        placeholder = {
            if (placeholder != null) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.ink3,
                )
            }
        },
        trailingIcon = trailing,
        visualTransformation = if (password && !visible) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else keyboardType,
        ),
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = if (password) WbMono else FontFamily.Default,
            color = c.ink,
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = c.ink,
            unfocusedTextColor = c.ink,
            focusedContainerColor = c.surface2,
            unfocusedContainerColor = c.surface2,
            disabledContainerColor = c.surface2,
            focusedBorderColor = c.accent,
            unfocusedBorderColor = c.line,
            disabledBorderColor = c.lineSoft,
            focusedLabelColor = c.accent,
            unfocusedLabelColor = c.ink3,
            disabledLabelColor = c.ink3,
            cursorColor = c.accent,
            focusedPlaceholderColor = c.ink3,
            unfocusedPlaceholderColor = c.ink3,
            focusedLeadingIconColor = c.ink3,
            unfocusedLeadingIconColor = c.ink3,
            focusedTrailingIconColor = c.ink3,
            unfocusedTrailingIconColor = c.ink3,
        ),
    )
}

/** 统计卡片网格：默认 2 列，按 [columns] 均分宽度并保持 8dp 间距。 */
@Composable
fun StatGrid(items: List<StatTileData>, columns: Int = 2) {
    val cols = columns.coerceAtLeast(1)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.chunked(cols).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowItems.forEach { item ->
                    StatTile(
                        label = item.label,
                        value = item.value,
                        unit = item.unit,
                        tone = item.tone,
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(cols - rowItems.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
