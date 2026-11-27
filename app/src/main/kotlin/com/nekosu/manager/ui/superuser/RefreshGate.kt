package com.nekosu.manager.ui.superuser

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 下拉刷新时指示器的最短展示时长，避免列表读得快时动画一闪而过。 */
internal const val RefreshMinDurationMs = 350L

/** 刷新上限：某些 ROM 的 PackageManager 长时间不返回，不能让它把列表一直按在刷新态。 */
internal const val RefreshTimeoutMs = 15_000L

/**
 * 执行一次下拉刷新，保证在 [timeoutMs] 内返回。
 *
 * `MochiLazyLargeTitleScaffold` 的下拉刷新是「onRefresh 挂起期间显示指示器、
 * 返回后才复位」的模型（`MochiRefreshState.isRefreshing`），所以 **onRefresh 必须返回**：
 * 一旦它永久挂起（等待一个再也不会到来的状态、或加载抛异常），列表就会被
 * `mochiRefreshHold` 顶到下方，菊花一直转，只能杀进程。
 *
 * 这里把三件事一次做完：加载失败吞掉（保留旧列表）、最短展示 [minDurationMs]、
 * 超过 [timeoutMs] 强制结束。取消（用户离开页面）照常向上传播。
 */
internal suspend fun runRefresh(
    minDurationMs: Long = RefreshMinDurationMs,
    timeoutMs: Long = RefreshTimeoutMs,
    load: suspend () -> Unit,
) {
    try {
        withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                launch { load() }
                launch { delay(minDurationMs) }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        // 读列表失败只影响数据，不能让刷新态卡住；界面继续显示上一次的列表
    }
}
