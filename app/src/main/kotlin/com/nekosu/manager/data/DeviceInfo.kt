package com.nekosu.manager.data

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.system.Os
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 首页「设备信息」的真实取值。
 *
 * 全部来自本机：`Build` 字段、`/proc`、`/sys/fs/selinux` 与 PackageManager，
 * 不依赖 root，也不发任何网络请求。
 */
data class DeviceInfo(
    /** 市场名优先（HyperOS 的 `ro.product.marketname`），否则厂商 + 型号。 */
    val model: String,
    /** 如「HyperOS 2.0.5.0 · Android 15」。 */
    val system: String,
    /** 管理器自身版本，如「1.0 (1)」。 */
    val managerVersion: String,
    val selinux: String,
    val seccomp: String,
    val kernel: String,
    val fingerprint: String,
) {
    companion object {
        /** 加载中的占位，避免先闪一帧假数据。 */
        val Empty =
            DeviceInfo(
                model = "—",
                system = "—",
                managerVersion = "—",
                selinux = "—",
                seccomp = "—",
                kernel = "—",
                fingerprint = "—",
            )
    }
}

/**
 * 设备信息采集。
 *
 * 几个刻意的选择：
 * 1. **市场名**：HyperOS / MIUI 的 `Build.MODEL` 是 `2405CPX3DC` 这类代号，
 *    真正的商品名在 `ro.product.marketname`（不同分区前缀都试一遍）；
 * 2. **内核**：优先 `Os.uname().release`（就是 `uname -r`），拿不到再解析 `/proc/version`；
 * 3. **SELinux**：读 `/sys/fs/selinux/enforce`。selinuxfs 在部分 ROM 上对 app 不可读，
 *    此时退回反射 `android.os.SELinux.isSELinuxEnforced()`，再不行才显示未知；
 * 4. **Seccomp**：解析 `/proc/self/status` 的 `Seccomp:` 行，2 = filter，1 = strict；
 * 5. 所有读取都包了 runCatching —— 采集失败只影响一格文字，不能让首页崩。
 */
object DeviceInfoRepository {
    private val MARKET_NAME_PROPS =
        listOf(
            "ro.product.marketname",
            "ro.product.odm.marketname",
            "ro.product.vendor.marketname",
            "ro.product.system.marketname",
        )

    suspend fun load(context: Context): DeviceInfo =
        withContext(Dispatchers.IO) {
            val props = SystemPropertiesReader()
            DeviceInfo(
                model = deviceModel(props),
                system = systemVersion(props),
                managerVersion = managerVersion(context),
                selinux = selinuxStatus(),
                seccomp = seccompStatus(),
                kernel = kernelVersion(),
                fingerprint = Build.FINGERPRINT?.takeIf { it.isNotBlank() } ?: "unknown",
            )
        }

    private fun deviceModel(props: SystemPropertiesReader): String {
        val market = MARKET_NAME_PROPS.firstNotNullOfOrNull { props.get(it) }
        val model = Build.MODEL.orEmpty().trim()
        val maker = Build.MANUFACTURER.orEmpty().trim()
        return when {
            !market.isNullOrBlank() && market != model ->
                if (model.isBlank()) market else "$market ($model)"
            model.isBlank() -> maker.ifBlank { "unknown" }
            maker.isBlank() || model.startsWith(maker, ignoreCase = true) -> model
            else -> "$maker $model"
        }
    }

    /** HyperOS 的 `ro.mi.os.version.name` 形如 `OS2.0.5.0.VNCCNXM`，只保留前四段。 */
    private fun systemVersion(props: SystemPropertiesReader): String {
        val android = "Android ${Build.VERSION.RELEASE}"
        val rom =
            props.get("ro.mi.os.version.name")?.let { raw ->
                "HyperOS " + raw.removePrefix("OS").split('.').take(4).joinToString(".")
            } ?: props.get("ro.miui.ui.version.name")?.let { raw ->
                "MIUI " + raw.split('.').take(4).joinToString(".")
            }
        return if (rom.isNullOrBlank()) android else "$rom · $android"
    }

    private fun managerVersion(context: Context): String {
        val pm = context.packageManager
        val info =
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(context.packageName, 0)
                }
            }.getOrNull()
        val name = info?.versionName ?: "unknown"
        val code = info?.let(::versionCodeOf) ?: 0L
        return "$name ($code)"
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    private fun selinuxStatus(): String =
        when (readFile("/sys/fs/selinux/enforce")?.trim()) {
            "1" -> "Enforcing"
            "0" -> "Permissive"
            else ->
                when (selinuxEnforcedByReflection()) {
                    true -> "Enforcing"
                    false -> "Permissive"
                    // selinuxfs 不存在说明内核根本没开 SELinux，而不是「读不到」
                    null -> if (File("/sys/fs/selinux").exists()) "未知" else "Disabled"
                }
        }

    private fun selinuxEnforcedByReflection(): Boolean? =
        runCatching {
            val clazz = Class.forName("android.os.SELinux")
            clazz.getMethod("isSELinuxEnforced").invoke(null) as Boolean
        }.getOrNull()

    private fun seccompStatus(): String {
        val line = readFile("/proc/self/status")
            ?.lineSequence()
            ?.firstOrNull { it.startsWith("Seccomp:") }
            ?.substringAfter(':')
            ?.trim()
        return when (line) {
            "2" -> "Filter"
            "1" -> "Strict"
            "0" -> "Disabled"
            else -> "未知"
        }
    }

    private fun kernelVersion(): String =
        runCatching { Os.uname().release }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: readFile("/proc/version")
                ?.let { Regex("""Linux version (\S+)""").find(it)?.groupValues?.getOrNull(1) }
            ?: System.getProperty("os.version")?.takeIf { it.isNotBlank() }
            ?: "unknown"

    private fun readFile(path: String): String? =
        runCatching {
            val file = File(path)
            if (!file.canRead()) return null
            file.readText()
        }.getOrNull()
}

/** `android.os.SystemProperties` 是隐藏类，反射拿不到（或抛异常）时静默返回 null。 */
private class SystemPropertiesReader {
    private val getter =
        runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        }.getOrNull()

    fun get(key: String): String? =
        runCatching { getter?.invoke(null, key) as? String }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}
