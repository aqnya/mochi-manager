package com.nekosu.manager.ui.superuser

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下拉刷新的收尾保证。
 *
 * 库的模型是「onRefresh 返回后 isRefreshing 才复位」，所以这些用例守住一条底线：
 * 无论加载成功、失败、还是永远不返回，[runRefresh] 都必须结束。
 */
class RefreshGateTest {
    @Test
    fun `fast load still shows the indicator for the minimum duration`() = runTest {
        var loaded = false
        runRefresh(minDurationMs = 300, timeoutMs = 5_000) { loaded = true }
        assertTrue("加载没有执行", loaded)
        assertTrue("指示器只转了 ${testScheduler.currentTime}ms", testScheduler.currentTime >= 300)
    }

    @Test
    fun `load failure is swallowed so the refresh state can be released`() = runTest {
        // 真机上 getInstalledPackages 会因包可见性策略、PM 服务重启抛异常。
        // 失败时不必等最短时长，立刻收起指示器，界面保留上一次的列表。
        runRefresh(minDurationMs = 100, timeoutMs = 5_000) { error("PackageManager 挂了") }
        assertTrue("失败后没有及时返回：${testScheduler.currentTime}ms", testScheduler.currentTime < 100)
    }

    @Test
    fun `hung load ends at the timeout instead of waiting forever`() = runTest {
        var cancelled = false
        runRefresh(minDurationMs = 100, timeoutMs = 2_000) {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        assertEquals(2_000L, testScheduler.currentTime)
        assertTrue("超时后应取消仍在进行的加载", cancelled)
    }

    @Test
    fun `outer cancellation propagates`() = runTest {
        val started = CompletableDeferred<Unit>()
        val job =
            launch {
                runRefresh(minDurationMs = 100, timeoutMs = 60_000) {
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
        started.await()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
    }
}
