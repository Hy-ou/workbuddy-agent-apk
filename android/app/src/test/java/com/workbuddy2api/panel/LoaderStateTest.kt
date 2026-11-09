package com.workbuddy2api.panel

import com.workbuddy2api.panel.ui.state.Loadable
import com.workbuddy2api.panel.ui.state.Loader
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Loader` 的轮询语义：静默刷新不拨动 `refreshing`、结果相等时不写状态。
 *
 * 这两条是 3.12.0 掉帧优化的一部分——总览/账号页每 5 秒轮询一次，
 * 旧实现每次都会把整张列表重组一遍并把下拉刷新指示器点亮一次。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoaderStateTest {

    @Test fun quietRefreshDoesNotTouchRefreshing() = runTest {
        val loader = Loader(this) { 1 }
        loader.refresh(quiet = true)
        assertFalse(loader.refreshing)
        advanceUntilIdle()
        assertFalse(loader.refreshing)
        assertEquals(Loadable.Ready(1), loader.state)
    }

    @Test fun manualRefreshTogglesRefreshing() = runTest {
        val loader = Loader(this) { 1 }
        loader.refresh()
        assertTrue(loader.refreshing)
        advanceUntilIdle()
        assertFalse(loader.refreshing)
    }

    @Test fun equalPollResultKeepsSameStateInstance() = runTest {
        val loader = Loader(this) { listOf(1, 2, 3) }
        loader.refresh()
        advanceUntilIdle()
        val first = loader.state
        loader.refresh(quiet = true)
        advanceUntilIdle()
        assertSame(first, loader.state)
        loader.refresh(quiet = true)
        advanceUntilIdle()
        assertSame(first, loader.state)
    }

    @Test fun changedPollResultReplacesState() = runTest {
        var n = 0
        val loader = Loader(this) { ++n }
        loader.refresh()
        advanceUntilIdle()
        loader.refresh(quiet = true)
        advanceUntilIdle()
        assertEquals(Loadable.Ready(2), loader.state)
    }

    @Test fun failureKeepsLastGoodDataOnQuietRefresh() = runTest {
        var fail = false
        val loader = Loader(this) {
            if (fail) error("boom") else 7
        }
        loader.refresh()
        advanceUntilIdle()
        fail = true
        loader.refresh(quiet = true)
        advanceUntilIdle()
        assertEquals(Loadable.Ready(7), loader.state)
    }
}
