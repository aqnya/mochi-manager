package com.nekosu.manager.data

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.nekosu.manager.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "NekoSU/LogExport"

/** 日志的两个去向。 */
enum class LogAction { Save, Share }

/**
 * 一次导出的完整状态机。
 *
 * 放在 [LogExportCoordinator] 这个应用级单例里，而不是记住在 Composable 的 remember 状态中：
 * 用户点了「保存日志」之后侧滑返回 / 切到文件管理 / 锁屏都会让 Activity 销毁重建，
 * 如果协程绑在 `rememberCoroutineScope()` 上，收集到一半就会被取消，文件根本写不到下载目录。
 * 有 root 时脚本跑得快、恰好在 Activity 还活着时完成，无 root 时脚本慢就必然踩中。
 */
sealed interface LogExportState {
    data object Idle : LogExportState
    data object Collecting : LogExportState

    /** 无 root：收集完成，等用户确认是否仍要导出。 */
    data class ConfirmPartial(
        val file: File,
        val action: LogAction,
        val missing: Int,
    ) : LogExportState

    /** 完成或失败，直接给已格式化的文案。 */
    data class Notice(
        val title: String,
        val message: String,
    ) : LogExportState
}

object LogExportCoordinator {
    /**
     * Main.immediate 保证状态更新在主线程，同时 SupervisorJob 让一次失败不会毒化整个 scope。
     * 刻意不绑定 Activity/ViewModel 生命周期：导出一旦开始就要跑到结束。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var state by mutableStateOf<LogExportState>(LogExportState.Idle)
        private set

    /** intent 里传进来的 context 可能是 Activity，一律只留 applicationContext，避免持有已销毁的 Activity。 */
    fun start(
        context: Context,
        action: LogAction,
    ) {
        if (state is LogExportState.Collecting) {
            Log.d(TAG, "start ignored: already collecting")
            return
        }
        val app = context.applicationContext
        Log.d(TAG, "start action=$action")
        state = LogExportState.Collecting
        scope.launch {
            try {
                val result = Bugreport.build(app)
                Log.d(
                    TAG,
                    "build done rooted=${result.rooted} missing=${result.missing.size} bytes=${result.file.length()}",
                )
                if (result.rooted) {
                    deliver(app, action, result.file, noRoot = false)
                } else {
                    state = LogExportState.ConfirmPartial(result.file, action, result.missing.size)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "收集失败", e)
                state = LogExportState.Notice(app.getString(R.string.log_failed), errorText(app, e))
            }
        }
    }

    /** 无 root 提示框里点「继续导出」。 */
    fun confirm(context: Context) {
        val pending = state as? LogExportState.ConfirmPartial ?: return
        Log.d(TAG, "confirm action=${pending.action}")
        deliver(context.applicationContext, pending.action, pending.file, noRoot = true)
    }

    /** 无 root 提示框里点「取消」，或用户主动关掉结果提示。 */
    fun dismiss() {
        if (state is LogExportState.ConfirmPartial) {
            (state as LogExportState.ConfirmPartial).file.delete()
        }
        state = LogExportState.Idle
    }

    private fun deliver(
        app: Context,
        action: LogAction,
        file: File,
        noRoot: Boolean,
    ) {
        when (action) {
            LogAction.Save -> {
                scope.launch {
                    val path =
                        try {
                            LogExport.save(app, file, file.name)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            // 失败时保留 cacheDir 里的文件，用户还能走「发送日志」把它发出去
                            Log.e(TAG, "写入下载目录失败", e)
                            state =
                                LogExportState.Notice(
                                    app.getString(R.string.log_failed),
                                    app.getString(R.string.log_export_failed, e.message ?: e.javaClass.simpleName),
                                )
                            return@launch
                        }
                    Log.d(TAG, "saved to $path (${file.length()} bytes)")
                    file.delete()
                    state =
                        LogExportState.Notice(
                            app.getString(R.string.log_saved),
                            app.getString(
                                if (noRoot) R.string.log_saved_no_root else R.string.log_saved_message,
                                path,
                            ),
                        )
                }
            }

            LogAction.Share -> {
                try {
                    val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
                    val send =
                        Intent(Intent.ACTION_SEND).apply {
                            type = "application/gzip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    app.startActivity(Intent.createChooser(send, app.getString(R.string.send_log)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    // 分享出去之后界面回到 Idle；文件留给系统分享流程持有，不在这里删
                    state = LogExportState.Idle
                } catch (e: Throwable) {
                    Log.e(TAG, "分享日志失败", e)
                    state = LogExportState.Notice(app.getString(R.string.log_failed), errorText(app, e))
                }
            }
        }
    }

    private fun errorText(
        app: Context,
        e: Throwable,
    ): String =
        when (e) {
            is ShellTimeoutException -> e.message ?: app.getString(R.string.log_failed)
            is BugreportException -> e.message ?: app.getString(R.string.log_failed)
            else -> e.message ?: e.javaClass.simpleName
        }
}
