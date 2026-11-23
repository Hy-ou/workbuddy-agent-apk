package com.workbuddy2api.panel.ui.shell

import android.graphics.Rect
import android.os.Build
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView

/** 判定键盘占位的阈值：占可视高度 15% 以上才算「键盘弹出来了」，排除导航栏/手势条抖动。 */
private const val IME_HEIGHT_RATIO = 0.15f

/**
 * 键盘是否可见（跨 API 版本）。
 *
 * - **API 30+ / edge-to-edge**：窗口不再被 IME 压缩，IME 以 `WindowInsets.ime` 下发，
 *   直接读 Compose 的 [isImeVisible]（权威、无抖动）。
 * - **API < 30**：`adjustResize` 压的是窗口，`ime` insets 恒为 0、`isImeVisible` 恒为 false。
 *   此时用 `getWindowVisibleDisplayFrame()` 的历史最大可见底部与当前值之差判断——
 *   键盘弹出会让可视帧底部缩掉一个键盘高度。用「历史最大」而不是「屏幕高」，
 *   这样分屏 / 小窗下窗口本身就矮的场景不会误判成键盘。
 *
 * 注意：这里**不注册** `View.setOnApplyWindowInsetsListener`——那个监听器在
 * AndroidComposeView 上已经被 Compose 自己占用，抢过来会让全 App 的 insets 失效。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun rememberImeVisible(): Boolean {
    val byInsets = WindowInsets.isImeVisible
    val view = LocalView.current
    var byFrame by remember { mutableStateOf(false) }
    DisposableEffect(view) {
        val rect = Rect()
        val vto = view.viewTreeObserver
        var stableBottom = 0
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            runCatching {
                view.getWindowVisibleDisplayFrame(rect)
                val bottom = rect.bottom
                if (bottom > stableBottom) stableBottom = bottom
                val covered = stableBottom - bottom
                byFrame = stableBottom > 0 && covered > stableBottom * IME_HEIGHT_RATIO
            }
        }
        vto.addOnGlobalLayoutListener(listener)
        onDispose {
            runCatching { vto.removeOnGlobalLayoutListener(listener) }
        }
    }
    // API 30+ 以系统 insets 为准；更低的 API 只能靠帧差兜底。
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) byInsets || byFrame else byFrame
}
