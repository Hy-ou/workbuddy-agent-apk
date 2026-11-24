package com.workbuddy2api.panel.ui.shell

import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView

private const val TAG = "WbIme"

/** 判定键盘占位的阈值：占可视高度 15% 以上才算「键盘弹出来了」，排除导航栏/手势条抖动。 */
private const val IME_HEIGHT_RATIO = 0.15f

/**
 * 键盘状态（跨 API 版本）。
 *
 * @property visible 键盘是否弹起——只用来决定「底部 6 Tab 要不要让位」。
 * @property bottomShiftPx 内容还需要自己往上让多少像素：
 *   - 窗口已经被 `adjustResize` 压缩时是 0（内容已经在键盘上方，不能再补，否则留白）；
 *   - API 30+ 也是 0（交给 `imePadding()` 消费 ime insets，避免重复）；
 *   - 只有「窗口没压缩 + insets 拿不到」的组合才非 0 —— 即 Android 10 及以下的沉浸全屏。
 */
data class ImeState(val visible: Boolean, val bottomShiftPx: Int)

/**
 * 观察键盘状态（跨 API 版本）。
 *
 * 三条不同的通道，必须分开处理：
 * 1. **API 30+ / edge-to-edge**：窗口不再被 IME 压缩，IME 以 `WindowInsets.ime` 下发，
 *    直接读 Compose 的 [isImeVisible]，位移交给调用方的 `imePadding()`。
 * 2. **API < 30 标准布局**：`adjustResize` 压的是窗口，`ime` insets 恒为 0。窗口自己变矮，
 *    内容已经让开了位置，**不需要**再补任何 padding。
 * 3. **API < 30 沉浸全屏**：`SYSTEM_UI_FLAG_IMMERSIVE_STICKY | LAYOUT_HIDE_NAVIGATION` 下
 *    `adjustResize` 不再压缩窗口，insets 又拿不到 —— 两条兜底同时失效，键盘直接盖住输入区
 *    （issue #5）。这里用「根视图高度 - 可视帧高度」把被盖住的高度算出来交给调用方补上。
 *
 * 注意：这里**不注册** `View.setOnApplyWindowInsetsListener`——那个监听器在
 * AndroidComposeView 上已经被 Compose 自己占用，抢过来会让全 App 的 insets 失效。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun rememberImeState(): ImeState {
    val byInsets = WindowInsets.isImeVisible
    val view = LocalView.current
    var byFrame by remember { mutableStateOf(false) }
    var shiftPx by remember { mutableIntStateOf(0) }
    DisposableEffect(view) {
        val rect = Rect()
        val vto = view.viewTreeObserver
        var stableBottom = 0
        var lastShift = -1
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            runCatching {
                view.getWindowVisibleDisplayFrame(rect)
                val bottom = rect.bottom
                if (bottom > stableBottom) stableBottom = bottom
                val covered = (stableBottom - bottom).coerceAtLeast(0)
                val threshold = (stableBottom * IME_HEIGHT_RATIO).toInt()
                byFrame = stableBottom > 0 && covered > threshold

                // 窗口被压缩时：根视图高度 == 可视帧高度 → hidden ≈ 0，不补。
                // 沉浸全屏没压缩时：根视图还是整屏，可视帧矮了一个键盘 → hidden = 键盘高度。
                val hidden = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    0
                } else {
                    (view.rootView.height - rect.height()).let { if (it > threshold) it else 0 }
                }
                shiftPx = hidden
                if (hidden != lastShift) {
                    lastShift = hidden
                    // 只有变化时才打，方便排查「到底是没量到键盘，还是量到了没生效」：
                    //   adb logcat -s WbIme
                    Log.i(
                        TAG,
                        "shift=$hidden rootH=${view.rootView.height} frameH=${rect.height()} " +
                            "visible=${byFrame || byInsets}",
                    )
                }
            }
        }
        vto.addOnGlobalLayoutListener(listener)
        onDispose {
            runCatching { vto.removeOnGlobalLayoutListener(listener) }
        }
    }
    // API 30+ 以系统 insets 为准；更低的 API 只能靠帧差兜底。
    val visible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) byInsets || byFrame else byFrame
    return ImeState(visible, shiftPx)
}
