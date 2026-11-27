package com.nekosu.manager.neko

import android.content.Context
import android.util.Log
import com.nekosu.manager.data.RootShell
import com.nekosu.manager.data.RootShellResult
import java.io.File

/**
 * neko 包的用户态运行时（`ncore`）。
 *
 * 二进制以 `libncore.so` 的名字打进 APK：内核从 PackageManager 解包的
 * `/data/app/<pkg>/lib/arm64-v8a/libncore.so` 引导它到 `/data/adb/ncore`，
 * 名字和位置都不能改。首次使用（或版本变化）时由这里复制并跑一次 `ncore install`，
 * 由它自己铺开 `/data/adb/nksu/bin` 里的辅助程序。
 *
 * 模块安装、启用/禁用、卸载、执行 action 全部转给 ncore 的 CLI——管理器不再自己
 * 解包 ZIP，也不再依赖内核这边的 ksud。
 */
object Ncore {
    private const val TAG = "NekoSU/Ncore"
    private const val PREFS = "ncore_runtime"
    private const val KEY_VERSION = "installed_version"

    /** 内核引导 ncore 时使用的固定路径。 */
    const val BOOT_PATH = "/data/adb/ncore"


    private fun libFile(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libncore.so")

    /**
     * 是否已按当前版本部署过。
     *
     * 不能用 `File("/data/adb/ncore").canExecute()` 判断：应用进程没有 /data/adb 的读权限，
     * SELinux 会把 stat 直接拒掉，永远得到 false。这里以「上次部署时的 versionCode」为准，
     * 升级安装后会重新部署一次。
     */
    fun isDeployed(context: Context): Boolean {
        val app = context.applicationContext
        val version = versionCode(app)
        val installed = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_VERSION, -1L)
        return version != 0L && installed == version
    }

    /**
     * 确保 ncore 就位。没有 root、或者包体里没有 arm64 的 ncore 时返回 false，
     * 调用方回退到原来的 ksud 路径。
     */
    suspend fun ensureInstalled(context: Context): Boolean {
        val app = context.applicationContext
        val src = libFile(app)
        if (!src.isFile) {
            Log.w(TAG, "APK 内没有 libncore.so（非 arm64 设备？）")
            return false
        }

        val version = versionCode(app)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (isDeployed(app)) return true

        val quotedSrc = quote(src.absolutePath)
        val quotedDst = quote(BOOT_PATH)
        val script =
            "mkdir -p /data/adb && cp -f $quotedSrc $quotedDst && chmod 755 $quotedDst && " +
                "chcon u:object_r:ksu_file:s0 $quotedDst 2>/dev/null; $quotedDst install"

        val result =
            runCatching { RootShell.exec(script, timeoutSeconds = 90L) }
                .getOrElse { e ->
                    Log.w(TAG, "部署 ncore 失败：${e.message}")
                    return false
                }
        if (result.exitCode != 0) {
            Log.w(TAG, "ncore install 退出码 ${result.exitCode}：${result.output.take(300)}")
            return false
        }
        prefs.edit().putLong(KEY_VERSION, version).apply()
        Log.i(TAG, "ncore 已就位：$BOOT_PATH")
        return true
    }

    /** 以 root 执行 ncore 子命令。 */
    suspend fun exec(
        args: String,
        timeoutSeconds: Long = 120L,
    ): RootShellResult = RootShell.exec("$BOOT_PATH $args", timeoutSeconds)

    suspend fun moduleInstall(zip: File): RootShellResult =
        exec("module install ${quote(zip.absolutePath)}", timeoutSeconds = 180L)

    suspend fun moduleUninstall(id: String): RootShellResult = exec("module uninstall ${quote(id)}", 90L)

    suspend fun moduleUndoUninstall(id: String): RootShellResult =
        exec("module undo-uninstall ${quote(id)}", 90L)

    suspend fun moduleEnable(id: String): RootShellResult = exec("module enable ${quote(id)}", 90L)

    suspend fun moduleDisable(id: String): RootShellResult = exec("module disable ${quote(id)}", 90L)

    suspend fun moduleAction(id: String): RootShellResult = exec("module action ${quote(id)}", 90L)

    /** `module list --json`，直接交给上层解析。 */
    suspend fun moduleListJson(): RootShellResult = exec("module list --json", 60L)

    private fun versionCode(context: Context): Long =
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.longVersionCode
        }.getOrDefault(0L)

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
