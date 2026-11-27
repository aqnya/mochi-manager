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
    /** 输出里出现过「按音量键 / 请输入」之类的提示，界面据此把输入框露出来。 */
    val awaitingInput: Boolean = false,
) {
    val current: StagedModule? get() = items.getOrNull(index)
    val finished: Boolean get() = started && !running
}

/**
 * 一次模块安装的会话。
 *
 * 为什么不放在页面的 `LaunchedEffect` 里：安装是长任务，旋转屏幕或切到后台再回来
 * 会重建 Activity，`rememberCoroutineScope` / `LaunchedEffect` 跟着被取消，脚本就断在
 * 半路（日志导出那次踩过同样的坑）。这里用应用级作用域持有任务与输出，页面只是订阅者。
 *
 * 输出是流式的：ncore 的每行 `ui_print` 一到就进 [state]，交互提示能立刻显示出来，
 * 用户按音量键或通过 [sendInput] 输入后脚本才继续。
 */
object ModuleInstallSession {
    /** 终端区保留的行数：再多渲染没意义，还会拖慢重组。 */
    private const val MAX_LINES = 1500

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

    /** 开始安装一批包；上一次没结束时不重复启动。 */
    fun start(items: List<StagedModule>) {
        if (items.isEmpty()) return
        if (job?.isActive == true) return
        shell = null
        _state.value = InstallState(items = items, running = true, started = true)
        job =
            scope.launch {
                var success = 0
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
                                append("[完成] ${item.displayName}")
                            }

                            is InstallOutcome.Failure -> {
                                failures += "${item.displayName}：${outcome.message}"
                                append("[失败] ${item.displayName}：${outcome.message}")
                            }
                        }
                        _state.update { it.copy(success = success, failures = failures.toList()) }
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

    /** 把一行输入写进脚本的 stdin（脚本用 `read` 提问时用）。 */
    fun sendInput(text: String) {
        val target = shell ?: return
        if (!target.send(text)) {
            append("! 写入失败，脚本可能已经退出")
            return
        }
        append("> $text")
        _state.update { it.copy(awaitingInput = false) }
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

    private fun append(line: String) {
        _state.update { state ->
            val next =
                if (state.lines.size >= MAX_LINES) {
                    state.lines.subList(state.lines.size - MAX_LINES + 1, state.lines.size) + line
                } else {
                    state.lines + line
                }
            state.copy(
                lines = next,
                // 提示一旦出现就保持，直到用户输入或安装结束——脚本可能只打一次
                awaitingInput = state.awaitingInput || looksLikePrompt(line),
            )
        }
    }

    /** 安装脚本常见的「等你操作」措辞；命中后把输入区展开。 */
    internal fun looksLikePrompt(line: String): Boolean = PROMPT.containsMatchIn(line)

    private val PROMPT =
        Regex(
            "(?i)(音量|volume\\s*(up|down|key)|press\\s+(any\\s+)?(volume|key)|按.{0,6}键|" +
                "等待.{0,6}(输入|操作)|input|\\[y/n\\]|\\(y/n\\)|yes/no|请输入)",
        )
}
