package com.nekosu.manager.data

import android.util.Log
import com.nekosu.manager.neko.NekoKernel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val TAG = "NekoSU/Shell"

/** 一次 shell 执行结果。 */
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
            // 输出必须由独立线程一直读完：原来的 ready()+readLine() 在最后一行没有换行时会阻塞，
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
                        Log.w(TAG, "读取输出失败", e)
                    }
                }, "nekosu-shell-reader").apply { isDaemon = true }
            readerThread.start()

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                readerThread.join(500)
                val cmd = argv.joinToString(" ")
                Log.w(TAG, "命令超时 ${timeoutSeconds}s: cmd=$cmd")
                throw ShellTimeoutException("命令超时（${timeoutSeconds}s）：${cmd.take(60)}")
            }
            readerThread.join(1_000)
            val result = RootShellResult(process.exitValue(), output.toString().trim())
            Log.d(TAG, "命令结束 exit=${result.exitCode} 输出=${result.output.length} 字: ${argv.joinToString(" ").take(80)}")
            result
        }
}
