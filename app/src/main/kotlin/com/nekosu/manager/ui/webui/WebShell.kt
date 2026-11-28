// SPDX-License-Identifier: GPL-3.0-or-later
package com.nekosu.manager.ui.webui

import android.util.Log
import com.nekosu.manager.neko.NekoNative
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 同步 root 命令，供 [WebViewBridge] 与 [ModulePathHandler] 在 WebView 的
 * JavaBridge / 资源拦截线程上直接调用。
 *
 * 上游 NekoSU 的 `RootShell` 本身就是同步 API，管理器其余部分是协程版；
 * 这里只保留 WebUI 用到的几个方法，语义与上游一致（阻塞直到命令结束），
 * 底层复用现有能力：读文件优先走 JNI（子进程 setresuid 提权，一次 fork），
 * 失败再退回 `su -c cat`。
 */
internal object WebShell {
    private const val TAG = "NekoSU/WebUI"

    /** 单次输出上限，防止误用大文件命令把内存打满。 */
    private const val MAX_BYTES = 64 * 1024 * 1024

    /** NekoSU 内核把 `/system/bin/su` 重定向到 ncore；没有内核的设备退回 PATH 里的 su。 */
    private val SU_CANDIDATES = listOf("/system/bin/su", "su")

    data class Result(
        val code: Int,
        val output: String,
    ) {
        val ok: Boolean get() = code == 0
    }

    data class Output(
        val code: Int,
        val stdout: String,
        val stderr: String,
    )

    /** 合并 stderr 执行，返回退出码与全部输出（页面 `ksu.exec(cmd)` 的同步形式）。 */
    fun exec(cmd: String): Result {
        val (code, bytes) = run(listOf("-c", withPath(cmd)), mergeStderr = true, timeoutSeconds = 0)
        return Result(code, String(bytes, Charsets.UTF_8))
    }

    /** stdout / stderr 分开收集，供 `ksu.exec(cmd, cb)` 回调三个参数。 */
    fun execSplit(cmd: String): Output {
        val process = start(listOf("-c", withPath(cmd)), mergeStderr = false)
            ?: return Output(-1, "", "root shell unavailable")
        return runCatching {
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val outThread = drain(process.inputStream) { stdout.append(it).append('\n') }
            val errThread = drain(process.errorStream) { stderr.append(it).append('\n') }
            outThread.start()
            errThread.start()
            val code = process.waitFor()
            outThread.join(2000)
            errThread.join(2000)
            Output(code, stdout.toString(), stderr.toString())
        }.getOrElse {
            Output(-1, "", it.message.orEmpty())
        }
    }

    /** 流式执行，逐行回调；返回退出码。 */
    fun execStreamingSplit(
        cmd: String,
        onStdout: (String) -> Unit,
        onStderr: (String) -> Unit,
    ): Int {
        val process = start(listOf("-c", withPath(cmd)), mergeStderr = false)
            ?: run {
                onStderr("root shell unavailable")
                return -1
            }
        return runCatching {
            val outThread = drain(process.inputStream, onStdout)
            val errThread = drain(process.errorStream, onStderr)
            outThread.start()
            errThread.start()
            val code = process.waitFor()
            outThread.join(2000)
            errThread.join(2000)
            code
        }.getOrElse {
            onStderr(it.message.orEmpty())
            -1
        }
    }

    /**
     * 以 root 读取一个文件。
     *
     * 走 JNI：内核在线时在子进程里 setresuid(0) 提权后 open/read，只有一次 fork，
     * 比 `su -c cat` 少一次 su 二进制 exec + shell 解析；WebView 每个子资源都要读一次，
     * 这个差距会被放大几十倍。内核不在线 / 不是 manager 时直接返回 null（调用方给 404）。
     */
    fun readFileBytes(path: String): ByteArray? = NekoNative.readRootFile(path)

    /** 启一个 `su` 进程，返回 [Process]；两种候选都起不来时返回 null。 */
    private fun start(
        args: List<String>,
        mergeStderr: Boolean,
    ): Process? {
        for (su in SU_CANDIDATES) {
            val process =
                try {
                    ProcessBuilder(listOf(su) + args).redirectErrorStream(mergeStderr).start()
                } catch (e: Exception) {
                    continue
                }
            // su 存在但命令跑不起来（没授权等）：已经退出且非 0，换下一个候选
            val exited = runCatching { process.exitValue() }.getOrNull()
            if (exited != null && exited != 0) {
                runCatching { process.destroy() }
                continue
            }
            return process
        }
        Log.w(TAG, "没有可用的 su：${args.lastOrNull()?.take(80)}")
        return null
    }

    /** 跑完一条命令，返回退出码与全部 stdout（[mergeStderr] 时含 stderr）。 */
    private fun run(
        args: List<String>,
        mergeStderr: Boolean,
        timeoutSeconds: Long,
    ): Pair<Int, ByteArray> {
        val process = start(args, mergeStderr) ?: return -1 to ByteArray(0)
        return runCatching {
            val sink = ByteArrayOutputStream()
            val reader =
                Thread({
                    runCatching {
                        process.inputStream.use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (sink.size() < MAX_BYTES) sink.write(buffer, 0, count)
                            }
                        }
                    }
                }, "nekosu-webui-read").apply { isDaemon = true }
            reader.start()

            val finished =
                if (timeoutSeconds > 0) {
                    process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                } else {
                    process.waitFor()
                    true
                }
            if (!finished) {
                process.destroyForcibly()
                reader.join(2000)
                return@runCatching -1 to sink.toByteArray()
            }
            reader.join(2000)
            process.exitValue() to sink.toByteArray()
        }.getOrElse { -1 to ByteArray(0) }
    }

    private fun drain(
        input: java.io.InputStream,
        onLine: (String) -> Unit,
    ): Thread =
        Thread({
            runCatching {
                input.bufferedReader().useLines { lines -> lines.forEach(onLine) }
            }
        }, "nekosu-webui-drain").apply { isDaemon = true }

    private fun withPath(cmd: String): String = "PATH=/sbin:/system/sbin:/system/bin:/system/xbin; export PATH; $cmd"
}
