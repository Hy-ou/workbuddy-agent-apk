package com.workbuddy2api.panel.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 异步数据的三种状态：加载中 / 成功 / 失败。
 *
 * 用 `sealed interface` 而不是 `Result`，让 UI 的 `when` 分支完整且可读；
 * 协程异常统一收敛为 [Failed]，屏幕上永远不需要再写 try/catch。
 */
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data class Ready<T>(val data: T) : Loadable<T>
    data class Failed(val message: String) : Loadable<Nothing>
}

/**
 * 单个页面的数据加载器：持有 [state] 与 [refreshing]，并保证同一时刻只有一个请求在飞。
 *
 * - [refresh] 非静默：没有数据时先切到 [Loadable.Loading]；已有数据时保留旧内容。
 * - [refresh] 静默（`quiet = true`）：不切 Loading、失败时保留旧数据，用于下拉刷新与轮询。
 * - [set] 用于把本地操作结果直接写回（例如动作成功后局部更新）。
 */
@Stable
class Loader<T>(
    private val scope: CoroutineScope,
    private val load: suspend () -> T,
) {
    /** 当前数据状态，初始为 [Loadable.Loading]。 */
    var state: Loadable<T> by mutableStateOf<Loadable<T>>(Loadable.Loading)
        private set

    /** 是否有请求在进行中（用于下拉刷新指示器与按钮 loading 态）。 */
    var refreshing: Boolean by mutableStateOf(false)
        private set

    private var job: Job? = null

    /** 触发一次加载；已有请求在飞时直接忽略，天然防重复点击 / 重复轮询。 */
    fun refresh(quiet: Boolean = false) {
        if (job?.isActive == true) return
        if (!quiet && state !is Loadable.Ready) state = Loadable.Loading
        // 静默轮询不碰 refreshing：否则下拉刷新指示器每 5 秒闪一次，
        // 既误导用户（看起来像有人在手动刷新），也白白多两次整屏重组。
        if (!quiet) refreshing = true
        job = scope.launch {
            try {
                val next = Loadable.Ready(load())
                // 轮询结果没变化就不写状态：数据类 equals 相等时跳过，
                // 避免定时刷新把整张列表/图表重组一遍（掉帧的主要来源之一）。
                if (next != state) state = next
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 静默刷新失败时保留旧数据，避免列表瞬间闪成错误页。
                if (!quiet || state !is Loadable.Ready) {
                    state = Loadable.Failed(e.message?.takeIf { it.isNotBlank() } ?: "加载失败")
                }
            } finally {
                if (!quiet) refreshing = false
            }
        }
    }

    /** 直接写入一份已知数据（本地操作后的乐观更新）。 */
    fun set(data: T) {
        state = Loadable.Ready(data)
    }
}

/** 静默刷新：不切 Loading、失败保留旧数据。下拉刷新与轮询的统一入口。 */
fun Loader<*>.refreshQuiet() = refresh(quiet = true)

/**
 * 记住一个 [Loader]：首次组合自动加载，[keys] 变化时重建并重新加载。
 *
 * ```kotlin
 * val loader = rememberLoader(repo) { repo.overview() }
 * ```
 */
@Composable
fun <T> rememberLoader(vararg keys: Any?, load: suspend () -> T): Loader<T> {
    val scope = rememberCoroutineScope()
    // load 闭包可能捕获了变化的状态，始终调用最新版本。
    val latestLoad by rememberUpdatedState(load)
    val loader = remember(*keys) { Loader(scope) { latestLoad() } }
    LaunchedEffect(loader) { loader.refresh() }
    return loader
}

/**
 * 记住一个按 [intervalMs] 轮询的 [Loader]：首次立即加载，之后每 [intervalMs] 静默刷新一次。
 *
 * 生命周期跟随组合：`LaunchedEffect` 在离开屏幕（dispose）时自动取消，
 * 不会在后台继续打接口；[enabled] 为 false 或间隔非正数时不轮询（只做首次加载）。
 *
 * ```kotlin
 * val loader = rememberPollingLoader(5_000L, true, repo) { repo.overview() }
 * ```
 */
@Composable
fun <T> rememberPollingLoader(
    intervalMs: Long,
    enabled: Boolean = true,
    vararg keys: Any?,
    load: suspend () -> T,
): Loader<T> {
    val loader = rememberLoader(*keys) { load() }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(loader, enabled, intervalMs, lifecycleOwner) {
        if (!enabled || intervalMs <= 0L) return@LaunchedEffect
        // 只在界面可见（STARTED 及以上）时轮询：后台继续每 5 秒打接口既费电，
        // 也会让系统把进程排到低优先级，回前台后更容易掉帧。
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            loader.refresh(quiet = true)
            while (isActive) {
                delay(intervalMs)
                loader.refresh(quiet = true)
            }
        }
    }
    return loader
}
