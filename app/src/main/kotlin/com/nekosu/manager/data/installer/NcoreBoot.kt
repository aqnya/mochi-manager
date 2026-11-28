package com.nekosu.manager.data.installer

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.nekosu.manager.data.RootShell
import java.io.File

/**
 * ncore 用户态组件的「自升级换代」。
 *
 * 对齐上游 `NcoreBoot`：内核在 boot 阶段用 PackageManager 解压到 nativeLibraryDir
 * 的签名 `libncore.so` 引导 `/data/adb/ncore`，二进制本身不用管理器再暂存。但
 * `ncore install` 还会把内嵌的 busybox/resetprop 等 assets 解压出来、并把 ncore
 * 链进 `/data/adb/nksu/bin`——这一步只靠内核引导做不全。所以每次打开管理器时
 * 比对「上次安装的版本」与「当前运行版本」，版本变了就以 root 跑一次 APK 自带
 * 二进制的 `install`，让 ncore 用户态跟着管理器一起换代。
 *
 * 与上游的差异：工作副本的 [RootShell.exec] 是 suspend API，所以 [install] 也是
 * suspend；调用方（MainActivity）负责切到后台线程，不阻塞首帧。
 */
object NcoreBoot {
    const val NCORE_DIR = "/data/adb/nksu"
    const val BOOT_PATH = "/data/adb/ncore"

    private const val TAG = "NekoSU/NcoreBoot"
    private const val PREFS_NAME = "ncore_boot"
    private const val KEY_INSTALLED_VERSION = "installed_version"
    private const val NCORE_LIB_NAME = "libncore.so"

    /** APK 自带 ncore 的路径：PackageManager 把 jniLibs 解压到这里。 */
    internal fun bundledNcorePath(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, NCORE_LIB_NAME)

    /**
     * 是否需要跑 install。
     *
     * 与上游 `if (version != 0L && cached == version) return true` 等价：运行版本
     * 有效且与缓存一致就跳过；拿不到版本（0）或版本变化都要装一次，保证换代。
     */
    internal fun shouldInstall(
        cachedVersion: Long,
        runningVersion: Long,
    ): Boolean = runningVersion == 0L || cachedVersion != runningVersion

    /** root 下执行的 install 命令；路径做 shell 单引号转义。 */
    internal fun installCommand(ncorePath: String): String = "${quote(ncorePath)} install"

    /**
     * 每次打开时调用。版本变化则以 root 运行 APK 自带 ncore 的 install。
     *
     * 返回 true 表示「已就绪」（版本一致无需安装，或安装成功）；false 表示确实
     * 需要安装但没装成（二进制缺失 / 无 root / 命令失败），此时不写缓存，下次
     * 打开会再试。
     */
    suspend fun install(context: Context): Boolean {
        val app = context.applicationContext
        val ncore = bundledNcorePath(app)
        if (!ncore.exists()) {
            Log.w(TAG, "bundled ncore missing: ${ncore.absolutePath}")
            return false
        }

        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val version = appVersionCode(app)
        val cached = prefs.getLong(KEY_INSTALLED_VERSION, -1L)
        if (!shouldInstall(cached, version)) return true

        val ok =
            try {
                RootShell.exec(installCommand(ncore.absolutePath)).exitCode == 0
            } catch (e: Exception) {
                Log.w(TAG, "ncore install failed", e)
                false
            }
        if (ok && version != 0L) {
            prefs.edit().putLong(KEY_INSTALLED_VERSION, version).apply()
        }
        Log.i(TAG, "ncore install cached=$cached version=$version ok=$ok")
        return ok
    }

    internal fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** 当前运行版本（longVersionCode），取不到返回 0。 */
    private fun appVersionCode(context: Context): Long {
        val pm = context.packageManager
        val info =
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(context.packageName, 0)
                }
            }.getOrNull() ?: return 0L
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }
}
