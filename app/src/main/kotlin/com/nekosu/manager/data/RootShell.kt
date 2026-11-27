package com.nekosu.manager.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** `su -c` 的一次执行结果。 */
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
 * 走 `su -c` 执行一条命令，输出合并 stderr。
 *
 * 工程里没有引入 libsu（只为一个 `su` 没必要），所以自己管超时：
 * 边读输出边看截止时间，超时就 `destroy()` 并抛 [ShellTimeoutException]，
 * 避免某些 ROM 上 `dmesg`、`ksud` 挂住把界面一起拖死。
 */
object RootShell {
    /** 单次输出上限，防止 logcat 之类的命令把结果全塞进内存。 */
    private const val MAX_OUTPUT_CHARS = 4 * 1024 * 1024

    suspend fun exec(
        command: String,
        timeoutSeconds: Long = 60L,
    ): RootShellResult =
        withContext(Dispatchers.IO) {
            val process =
                try {
                    ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
                } catch (e: IOException) {
                    throw RootUnavailableException("无法执行 su：${e.message}", e)
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
                throw ShellTimeoutException("命令超时（${timeoutSeconds}s）：${command.take(60)}")
            }
            RootShellResult(process.waitFor(), output.toString().trim())
        }
}
