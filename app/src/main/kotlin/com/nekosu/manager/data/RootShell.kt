package com.nekosu.manager.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

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

    /** 走 `su -c`；拿不到 root 时抛 [RootUnavailableException]。 */
    suspend fun exec(
        command: String,
        timeoutSeconds: Long = 60L,
    ): RootShellResult = run(listOf("su", "-c", command), timeoutSeconds)

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
     * 探测能不能拿到 root：`su -c id -u` 输出 `0` 才算。
     *
     * 超时、被拒绝、没有 su 二进制都算没有 root，交给调用方降级；
     * 探测本身触发 KernelSU 的授权弹窗，所以给了比普通命令更长的等待时间。
     */
    suspend fun isRootAvailable(timeoutSeconds: Long = 30L): Boolean =
        try {
            exec("id -u", timeoutSeconds).output.trim() == "0"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            false
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
            val output = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
            process.inputStream.bufferedReader().use { reader ->
                while (System.currentTimeMillis() < deadline) {
                    if (reader.ready()) {
                        val line = reader.readLine() ?: break
                        if (output.length < MAX_OUTPUT_CHARS) output.append(line).append('\n')
                    } else if (process.isAlive) {
                        Thread.sleep(20)
                    } else {
                        break
                    }
                }
            }
            if (process.isAlive) {
                process.destroy()
                throw ShellTimeoutException("命令超时（${timeoutSeconds}s）：${argv.joinToString(" ").take(60)}")
            }
            RootShellResult(process.waitFor(), output.toString().trim())
        }
}
