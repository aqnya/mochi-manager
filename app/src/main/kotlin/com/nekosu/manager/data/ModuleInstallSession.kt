package com.nekosu.manager.data

import com.nekosu.manager.data.registry.InstallOutcome
import com.nekosu.manager.data.registry.ModuleInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** 安装输出页的一份快照。 */
data class InstallState(
    val items: List<StagedModule> = emptyList(),
    val index: Int = 0,
    val running: Boolean = false,
    val started: Boolean = false,
    val cancelled: Boolean = false,
    val lines: List<String> = emptyList(),
    val success: Int = 0,
    val failures: List<String> = emptyList(),
    /** 装完是否要重启才生效；热安装为 false，界面上就只留「关闭」。 */
    val needsReboot: Boolean = false,
) {
    val current: StagedModule? get() = items.getOrNull(index)

    val finished: Boolean get() = started && !running

    /** 有任何一个包失败，标题就按失败处理；日志里仍会逐个列出原因。 */
    val failed: Boolean get() = cancelled || failures.isNotEmpty() || (finished && success == 0)

    val text: String get() = lines.joinToString("\n")
}

/**
 * 一次模块安装的会话。
 *
 * 为什么不放在页面的 `LaunchedEffect` 里：安装是长任务，旋转屏幕或切到后台再回来
 * 会重建 Activity，`rememberCoroutineScope` / `LaunchedEffect` 跟着被取消，脚本就断在
 * 半路（日志导出那次踩过同样的坑）。这里用应用级作用域持有任务与输出，页面只是订阅者。
 *
 * 输出是流式的：ncore 的每行 `ui_print` 一到就进 [state]，和 KernelSU 的刷写页一样
 * 直接铺在终端区；脚本等音量键时提示本身就在日志里，不再额外做输入框。
 */
object ModuleInstallSession {
    /** 终端区保留的行数：再多渲染没意义，还会拖慢重组。 */
    private const val MAX_LINES = 1500

    /** 安装脚本清屏用的 ANSI 序列，KernelSU 的刷写页也做了同样的处理。 */
    private const val CLEAR = "\u001b[H\u001b[J"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(InstallState())
    val state: StateFlow<InstallState> = _state.asStateFlow()

    private var job: Job? = null
    private var shell: StreamingShell? = null

    /**
     * 调用实际的安装器。单测里替换成假的，避免真的去碰 `su`。
     */
    var installer: suspend (File, (StreamingShell) -> Unit, (String) -> Unit) -> InstallOutcome =
        { file, onStart, onLine -> ModuleInstaller.installStreaming(file, onStart, onLine) }

    /** 判断刚装完的包是否需要重启；单测里替换掉，避免真去探测 /data/adb。 */
    var rebootRequired: suspend (File) -> Boolean = { ModuleInstaller.needsReboot(it) }

    /** 开始安装一批包；上一次没结束时不重复启动。 */
    fun start(items: List<StagedModule>) {
        if (items.isEmpty()) return
        if (job?.isActive == true) return
        shell = null
        _state.value = InstallState(items = items, running = true, started = true)
        job =
            scope.launch {
                var success = 0
                var reboot = false
                val failures = mutableListOf<String>()
                try {
                    items.forEachIndexed { index, item ->
                        _state.update { it.copy(index = index) }
                        append(">>> 安装 ${item.displayName}（${index + 1}/${items.size}）")
                        val outcome =
                            try {
                                installer(item.file, { shell = it }, ::append)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                InstallOutcome.Failure(e.message ?: "安装失败")
                            }
                        when (outcome) {
                            is InstallOutcome.Success -> {
                                success++
                                if (!reboot) {
                                    reboot =
                                        runCatching { rebootRequired(item.file) }.getOrElse {
                                            // 探测不出来就按需要重启处理，宁可多按一次重启
                                            true
                                        }
                                }
                                append("[完成] ${item.displayName}")
                            }

                            is InstallOutcome.Failure -> {
                                failures += "${item.displayName}：${outcome.message}"
                                append("[失败] ${item.displayName}：${outcome.message}")
                            }
                        }
                        _state.update { it.copy(success = success, failures = failures.toList(), needsReboot = reboot) }
                    }
                } catch (e: CancellationException) {
                    append("[已取消] 安装被中断")
                    _state.update { it.copy(cancelled = true) }
                    throw e
                } finally {
                    shell = null
                    _state.update { it.copy(running = false) }
                }
            }
    }

    /** 重启设备；界面上的「重启」按钮走这里。 */
    fun reboot() {
        scope.launch {
            runCatching { RootShell.exec("svc power reboot || reboot") }
            Unit
        }
    }

    /** 用户取消：销毁进程，协程的 finally 会把 running 复位。 */
    fun cancel() {
        job?.cancel()
        shell = null
    }

    /** 离开安装页后清掉结果，下次进来从干净状态开始。 */
    fun dismiss() {
        if (job?.isActive == true) return
        _state.value = InstallState()
    }

    private fun append(raw: String) {
        var line = raw
        if (line.startsWith(CLEAR)) {
            _state.update { it.copy(lines = emptyList()) }
            line = line.removePrefix(CLEAR)
            if (line.isEmpty()) return
        }
        _state.update { state ->
            val next =
                if (state.lines.size >= MAX_LINES) {
                    state.lines.subList(state.lines.size - MAX_LINES + 1, state.lines.size) + line
                } else {
                    state.lines + line
                }
            state.copy(lines = next)
        }
    }
}
