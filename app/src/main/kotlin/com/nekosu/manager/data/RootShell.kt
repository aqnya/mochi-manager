package com.nekosu.manager.data

import android.util.Log
import com.nekosu.manager.neko.NekoKernel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

private const val TAG = "NekoSU/Shell"

data class RootShellResult(
    val exitCode: Int,
    val output: String,
)

/** 设备上拿不到 root shell（没装 su，或用户拒绝授权）。 */
class RootUnavailableException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** 命令超过时限仍未结束。 */
class ShellTimeoutException(
    message: String,
) : Exception(message)

/**
 * 执行 shell 命令，输出合并 stderr。
 *
 * 工程里没有引入 libsu（只为一个 `su` 没必要），所以自己管超时：
 * 边读输出边看截止时间，超时就 `destroy()` 并抛 [ShellTimeoutException]，
 * 避免某些 ROM 上 `dmesg`、`ksud` 挂住把界面一起拖死。
 */
object RootShell {
    /** 单次输出上限，防止 logcat 之类的命令把结果全塞进内存。 */
    private const val MAX_OUTPUT_CHARS = 4 * 1024 * 1024

    /**
     * su 的候选路径。
     *
     * NekoSU 内核把 `/system/bin/su` 的 execve 重定向到已签名的 ncore（argv[0] 保持 "su"），
     * 所以优先走它；没有内核模块的设备再退回 PATH 里的 `su`。
     */
    private val SU_CANDIDATES = listOf("/system/bin/su", "su")

    /** 走 `su -c`；拿不到 root 时抛 [RootUnavailableException]。 */
    suspend fun exec(
        command: String,
        timeoutSeconds: Long = 60L,
    ): RootShellResult {
        var last: Throwable? = null
        for (su in SU_CANDIDATES) {
            val result =
                try {
                    run(listOf(su, "-c", command), timeoutSeconds)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    last = e
                    continue
                }
            // 127 是 shell 报的「找不到命令」，说明这个 su 根本不存在，换下一个
            if (result.exitCode == 127 && result.output.contains("not found")) {
                last = IOException("$su 不存在")
                continue
            }
            return result
        }
        throw RootUnavailableException("无法执行 su：${last?.message}", last)
    }

    /**
     * 边执行边把输出交给 [onLine]，用于安装这类要现场看日志、还可能等用户操作的脚本。
     *
     * 和 [exec] 的区别只在「什么时候给结果」：这里输出一产生就回调，脚本 print 的
     * 「按音量键继续」能立刻出现在界面上；[onStart] 给出句柄，可以把一行文本写进
     * 脚本的 stdin（安装脚本用 `read` 提问时）。协程取消会销毁进程，不留后台残留。
     */
    suspend fun execStreaming(
        command: String,
        timeoutSeconds: Long = 120L,
        onStart: (StreamingShell) -> Unit = {},
        onLine: (String) -> Unit,
    ): RootShellResult {
        var last: Throwable? = null
        for (su in SU_CANDIDATES) {
            val result =
                try {
                    runStreaming(listOf(su, "-c", command), timeoutSeconds, onStart, onLine)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    last = e
                    continue
                }
            if (result.exitCode == 127 && result.output.contains("not found")) {
                last = IOException("$su 不存在")
                continue
            }
            return result
        }
        throw RootUnavailableException("无法执行 su：${last?.message}", last)
    }

    /**
     * 以应用自身身份执行，不经过 `su`。
     *
     * 无 root 时也能拿到 `getprop`、`/proc/1/mountinfo`、自身 logcat 这类世界可读的内容，
     * 只是 dmesg、`/data/adb` 会被拒绝——这比整份报告都拿不到有用。
     */
    suspend fun execAsUser(
        command: String,
        timeoutSeconds: Long = 60L,
    ): RootShellResult = run(listOf("sh", "-c", command), timeoutSeconds)

    /**
     * 以 root 打开一条命令的原始字节流（供模块 WebUI 的文件服务用）。
     *
     * 和 [exec] 的区别：不合并 stderr、不做超时收口，返回的就是进程 stdout——
     * 二进制文件（图片、字体）必须字节干净；流由调用方消费，读完 `cat` 自然退出。
     * stderr 由一个守护线程持续排空，防止写满管道把进程挂住。
     */
    fun openSuReadStream(command: String): InputStream? {
        for (su in SU_CANDIDATES) {
            val process =
                try {
                    ProcessBuilder(listOf(su, "-c", command)).start()
                } catch (e: IOException) {
                    continue
                }
            val exited = runCatching { process.exitValue() }.getOrNull()
            if (exited != null && exited != 0) {
                process.destroy()
                continue
            }
            Thread({
                try {
                    process.errorStream.use { it.readBytes() }
                } catch (e: Throwable) {
                    Log.w(TAG, "drain stderr failed", e)
                }
            }, "nekosu-su-stderr").apply { isDaemon = true }.start()
            return process.inputStream
        }
        return null
    }

    /**
     * 探测能不能拿到 root。
     *
     * 先看内核控制通道：能被 ncore 认定为 manager，本身就说明内核模块在、且本应用有 root 能力，
     * 这样不必再弹一次授权框。通道不可用时才回退到 `su -c id -u`。
     */
    suspend fun isRootAvailable(timeoutSeconds: Long = 30L): Boolean {
        val viaKernel = withContext(Dispatchers.IO) { NekoKernel.available }
        if (viaKernel) return true
        return try {
            exec("id -u", timeoutSeconds).output.trim() == "0"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            false
        }
    }

    private suspend fun run(
        argv: List<String>,
        timeoutSeconds: Long,
    ): RootShellResult =
        withContext(Dispatchers.IO) {
            val process =
                try {
                    ProcessBuilder(argv).redirectErrorStream(true).start()
                } catch (e: IOException) {
                    throw RootUnavailableException("无法执行 ${argv.firstOrNull()}：${e.message}", e)
                }
            // 输出必须由独立线程一直读完：readLine() 在最后一行没有换行时会阻塞，
            // 超时循环进不去，脚本又不退出，界面就永远停在「正在收集日志」。
            val output = StringBuilder()
            val readerThread =
                Thread({
                    val buffer = ByteArray(8 * 1024)
                    try {
                        process.inputStream.use { input ->
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (output.length < MAX_OUTPUT_CHARS) {
                                    output.append(String(buffer, 0, count, Charsets.UTF_8))
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "read output failed", e)
                    }
                }, "nekosu-shell-reader").apply { isDaemon = true }
            readerThread.start()

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                readerThread.join(500)
                val cmd = argv.joinToString(" ")
                Log.w(TAG, "command timed out timeout=${timeoutSeconds}s cmd=${cmd.take(120)}")
                throw ShellTimeoutException("命令超时（${timeoutSeconds}s）：${cmd.take(60)}")
            }
            readerThread.join(1_000)
            val result = RootShellResult(process.exitValue(), output.toString().trim())
            Log.d(
                TAG,
                "command finished exit=${result.exitCode} outChars=${result.output.length} " +
                    "cmd=${argv.joinToString(" ").take(120)}",
            )
            result
        }

    private suspend fun runStreaming(
        argv: List<String>,
        timeoutSeconds: Long,
        onStart: (StreamingShell) -> Unit,
        onLine: (String) -> Unit,
    ): RootShellResult =
        withContext(Dispatchers.IO) {
            val process =
                try {
                    ProcessBuilder(argv).redirectErrorStream(true).start()
                } catch (e: IOException) {
                    throw RootUnavailableException("无法执行 ${argv.firstOrNull()}：${e.message}", e)
                }

            val output = StringBuilder()
            val readerThread =
                Thread({
                    try {
                        // 按行读：一行是一次 ui_print，界面按行追加比分块拼接更好读
                        process.inputStream.bufferedReader().use { reader ->
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (output.length < MAX_OUTPUT_CHARS) output.append(line).append('\n')
                                try {
                                    onLine(line)
                                } catch (e: Throwable) {
                                    // 回调把界面搞崩了也不能影响读管道，否则进程会被写满阻塞
                                    Log.w(TAG, "line callback failed", e)
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "read output failed", e)
                    }
                }, "nekosu-shell-stream").apply { isDaemon = true }
            readerThread.start()

            try {
                onStart(StreamingShell(process))
            } catch (e: Throwable) {
                Log.w(TAG, "start callback failed", e)
            }

            val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds)
            try {
                while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                    currentCoroutineContext().ensureActive()
                    if (System.currentTimeMillis() > deadline) {
                        process.destroyForcibly()
                        readerThread.join(500)
                        throw ShellTimeoutException("命令超时（${timeoutSeconds}s）：${argv.lastOrNull()?.take(60)}")
                    }
                }
            } catch (e: CancellationException) {
                // 用户点了取消：连同 su 下面的脚本一起收掉
                process.destroyForcibly()
                throw e
            }

            readerThread.join(1_000)
            RootShellResult(process.exitValue(), output.toString().trim())
        }
}

/**
 * 正在运行的流式 shell。
 *
 * 只暴露「写一行」这一件事：安装脚本用 `read` / `select` 提问时，界面把用户输入
 * 通过这里送进去；靠音量键的脚本则不需要它，看到提示按物理键即可。
 */
class StreamingShell internal constructor(
    private val process: Process,
) {
    /** 写一行到脚本的 stdin；进程已退出或管道关闭时返回 false。 */
    fun send(line: String): Boolean =
        try {
            val stream = process.outputStream
            stream.write((line + "\n").toByteArray(Charsets.UTF_8))
            stream.flush()
            true
        } catch (e: IOException) {
            Log.w(TAG, "write stdin failed", e)
            false
        }
}
