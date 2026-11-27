package com.nekosu.manager.neko

import com.nekosu.manager.data.RootShell
import com.nekosu.manager.data.RootShellResult
import java.io.File

/**
 * neko 包的用户态运行时（`ncore`）。
 *
 * 二进制以 `libncore.so` 的名字打进 APK（`/data/app/<pkg>/lib/arm64-v8a/libncore.so`）。
 * 引导与鉴权都在内核态完成：内核的 exec 探针认 `/data/adb/ncore` 这个路径，init 在
 * post-fs-data 执行它时自动挂上控制 fd；ncore 自己在 boot 阶段扫描管理器包并把 uid
 * 交给内核（`IOC_SET_MANAGER_UID`）。
 *
 * 所以管理器这边不复制镜像、不跑 `ncore install`、也不做 `manager scan`——这些都由
 * 内核态负责，重复做只会和内核抢同一份状态。这里只负责以 root 调用已就位的 ncore CLI：
 * 模块安装、启停、卸载、执行 action 全部转给它，管理器不再自己解包 ZIP，也不依赖 ksud。
 */
object Ncore {
    /** 内核引导 ncore 时使用的固定路径。 */
    const val BOOT_PATH = "/data/adb/ncore"

    /** 以 root 执行 ncore 子命令。 */
    suspend fun exec(
        args: String,
        timeoutSeconds: Long = 120L,
    ): RootShellResult = RootShell.exec("$BOOT_PATH $args", timeoutSeconds)

    suspend fun moduleInstall(zip: File): RootShellResult =
        exec("module install ${quote(zip.absolutePath)}", timeoutSeconds = 180L)

    /**
     * 流式安装：解压、`install.sh` 的每行输出都会实时回调。
     *
     * 安装脚本可能停下等用户按音量键，也可能用 `read` 提问，所以这里把输出和
     * stdin 句柄一起交给界面，而不是等进程退出才拿到一整块文本。
     */
    suspend fun moduleInstallStreaming(
        zip: File,
        timeoutSeconds: Long = 300L,
        onStart: (com.nekosu.manager.data.StreamingShell) -> Unit = {},
        onLine: (String) -> Unit,
    ): RootShellResult =
        RootShell.execStreaming(
            command = "$BOOT_PATH module install ${quote(zip.absolutePath)}",
            timeoutSeconds = timeoutSeconds,
            onStart = onStart,
            onLine = onLine,
        )

    suspend fun moduleUninstall(id: String): RootShellResult = exec("module uninstall ${quote(id)}", 90L)

    suspend fun moduleUndoUninstall(id: String): RootShellResult =
        exec("module undo-uninstall ${quote(id)}", 90L)

    suspend fun moduleEnable(id: String): RootShellResult = exec("module enable ${quote(id)}", 90L)

    suspend fun moduleDisable(id: String): RootShellResult = exec("module disable ${quote(id)}", 90L)

    suspend fun moduleAction(id: String): RootShellResult = exec("module action ${quote(id)}", 90L)

    /** `module list --json`，直接交给上层解析。 */
    suspend fun moduleListJson(): RootShellResult = exec("module list --json", 60L)

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
