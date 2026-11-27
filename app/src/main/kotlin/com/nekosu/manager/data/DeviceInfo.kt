package com.nekosu.manager.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.system.Os
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 首页「设备信息」的真实取值。
 *
 * 字段与 KernelSU 的 `SystemInfo` 一一对应（deviceModel / managerVersion /
 * selinuxStatus / seccompStatus / kernelVersion / fingerprint），
 * 取值方式也照 PR #4005 依赖的那套实现，详见 [DeviceInfoRepository]。
 */
data class DeviceInfo(
    /** 商业名，如「Xiaomi 15 Pro」；取不到时回退到厂商 + 品牌 + 型号。 */
    val model: String,
    /** 如「HyperOS 2.0.5.0 · Android 15」。 */
    val system: String,
    /** 管理器自身版本，如「1.0 (1)」。 */
    val managerVersion: String,
    /** 原始值：Enforcing / Permissive / Disabled / Unknown，由 UI 翻译。 */
    val selinuxStatus: String,
    /** `PR_GET_SECCOMP` 的返回值：-1 不支持、0 关闭、1 严格、2 过滤。 */
    val seccompStatus: Int,
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
                selinuxStatus = "Unknown",
                seccompStatus = -1,
                kernel = "—",
                fingerprint = "—",
            )
    }
}

/**
 * 设备信息采集，对齐 KernelSU 的实现。
 *
 * 之前那版的毛病：
 * 1. 只认 `ro.product.marketname`，非小米/一加的机型拿不到商业名；
 * 2. 只靠反射 `SystemProperties`，在隐藏 API 被拦的 ROM 上整块退化成型号代号；
 * 3. SELinux 只读 `/sys/fs/selinux/enforce`，该文件对普通 app 常常不可读；
 * 4. Seccomp 靠解析 `/proc/self/status` 的文字，格式一变就「未知」。
 *
 * 现在按 PR 的做法：
 * - 机型：按 `Build.MANUFACTURER` 分派属性键（小米 / OPPO 系 / vivo / 荣耀华为 /
 *   中兴努比亚 / 三星），取不到才回退 `MANUFACTURER BRAND MODEL`；
 * - 属性：反射优先，失败再用 `getprop` 子进程兜底（不需要 root）；
 * - SELinux：执行 `getenforce`，被 SELinux 拒绝（stderr 以 Permission denied 结尾）
 *   说明当前域是 enforcing，直接判 Enforcing；拿不到才读 selinuxfs；
 * - Seccomp：`Os.prctl(PR_GET_SECCOMP)` 取内核返回值，异常记 -1（不支持）；
 * - 所有读取独立容错，某一项失败只影响那一格。
 */
object DeviceInfoRepository {
    private const val PR_GET_SECCOMP = 21
    private const val SHELL_TIMEOUT_MS = 400L

    private val SELINUX_STATES = setOf("Enforcing", "Permissive", "Disabled")

    /** 三星把商品名放在 floating_feature 里，反射与文件两条路。 */
    private const val SAMSUNG_FLOATING_FEATURE_CLASS = "com.samsung.android.feature.SemFloatingFeature"
    private const val SAMSUNG_KEY = "SEC_FLOATING_FEATURE_SETTINGS_CONFIG_BRAND_NAME"
    private val SAMSUNG_FILES =
        listOf("/vendor/etc/floating_feature.xml", "/system/etc/floating_feature.xml")

    suspend fun load(context: Context): DeviceInfo =
        withContext(Dispatchers.IO) {
            DeviceInfo(
                model = resolveDeviceName(),
                system = systemVersion(),
                managerVersion = managerVersion(context),
                selinuxStatus = readSELinuxStatus(),
                seccompStatus = readSeccompStatus(),
                kernel = kernelVersion(),
                fingerprint = Build.FINGERPRINT?.takeIf { it.isNotBlank() } ?: "unknown",
            )
        }

    // ---------------------------------------------------------------- 机型

    /** 对应 KernelSU `ui/util/DeviceNameHelper.kt` 的 `resolveDeviceName()`。 */
    fun resolveDeviceName(): String {
        val marketName =
            when (Build.MANUFACTURER.orEmpty().lowercase(Locale.ROOT)) {
                "samsung", "samsung electronics" -> samsungProductName()
                "xiaomi", "redmi", "poco" ->
                    // 不同机型把市场名放在不同分区，逐个试
                    listOf(
                        "ro.product.marketname",
                        "ro.product.odm.marketname",
                        "ro.product.vendor.marketname",
                        "ro.product.system.marketname",
                    ).firstNotNullOfOrNull { prop(it).validName() }
                "oppo", "oneplus", "realme", "oplus" -> prop("ro.vendor.oplus.market.name").validName()
                "vivo", "iqoo" -> prop("ro.vivo.market.name").validName()
                "honor", "huawei" -> prop("ro.config.marketing_name").validName()
                "zte", "nubia" -> prop("ro.vendor.product.ztename").validName()
                else -> null
            }
        if (marketName != null) return marketName

        // 回退：厂商 + 品牌（与厂商不同才加）+ 型号，重复词只留一个，
        // 否则 Robolectric 这类三字段相同的环境会得到「robolectric robolectric robolectric」。
        val parts =
            listOf(Build.MANUFACTURER, Build.BRAND, Build.MODEL)
                .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        val unique = ArrayList<String>(parts.size)
        for (part in parts) {
            if (unique.none { it.equals(part, ignoreCase = true) }) unique += part
        }
        return unique.joinToString(" ").ifBlank { "unknown" }
    }

    @SuppressLint("PrivateApi")
    private fun samsungProductName(): String? {
        val byReflection =
            runCatching {
                val clazz = Class.forName(SAMSUNG_FLOATING_FEATURE_CLASS)
                val instance = clazz.getMethod("getInstance").invoke(null)
                clazz.getMethod("getString", String::class.java).invoke(instance, SAMSUNG_KEY) as? String
            }.getOrNull()
        val name = byReflection.validName() ?: samsungProductNameFromFile()
        return name?.let { if (it.startsWith("Samsung", ignoreCase = true)) it else "Samsung $it" }
    }

    private fun samsungProductNameFromFile(): String? {
        val pattern = Regex("<$SAMSUNG_KEY>\\s*(.*?)\\s*</$SAMSUNG_KEY>", RegexOption.DOT_MATCHES_ALL)
        return SAMSUNG_FILES.firstNotNullOfOrNull { path ->
            runCatching { pattern.find(File(path).readText())?.groupValues?.getOrNull(1) }.getOrNull().validName()
        }
    }

    private fun String?.validName(): String? =
        this?.trim()?.takeIf {
            it.isNotEmpty() && !it.equals(Build.UNKNOWN, ignoreCase = true) && !it.equals("null", ignoreCase = true)
        }

    // ------------------------------------------------------------ 系统版本

    /** 只识别 HyperOS / MIUI，其余显示 Android 版本；不猜不编。 */
    private fun systemVersion(): String {
        val android = "Android ${Build.VERSION.RELEASE}"
        val rom =
            hyperOsVersion()?.let { "HyperOS $it" }
                ?: miuiVersion()?.let { "MIUI $it" }
        return if (rom == null) android else "$rom · $android"
    }

    private fun hyperOsVersion(): String? {
        // 真机是 ro.mi.os.version.name = OS2.0.5.0.VNCCNXM，个别版本只有 incremental
        val raw = prop("ro.mi.os.version.name").ifBlank { prop("ro.mi.os.version.incremental") }
        return versionHead(raw)
    }

    private fun miuiVersion(): String? = versionHead(prop("ro.miui.ui.version.name"))

    /** `OS2.0.5.0.VNCCNXM` → `2.0.5.0`；`V816` → `816`。 */
    private fun versionHead(raw: String): String? {
        if (raw.isBlank()) return null
        val text = raw.trim().removePrefix("OS").removePrefix("V")
        val numbers = text.split('.', '-', '_').takeWhile { it.isNotEmpty() && it.all(Char::isDigit) }
        return numbers.take(4).joinToString(".").ifBlank { text }
    }

    // -------------------------------------------------------------- 版本号

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

    // ------------------------------------------------------------- SELinux

    /** 对应 KernelSU `ui/util/SELinuxChecker.kt` 的 `getSELinuxStatusRaw()`。 */
    private fun readSELinuxStatus(): String {
        val shell = exec("getenforce")
        if (shell != null) {
            val stdout = shell.stdout.trim()
            if (shell.code == 0 && stdout in SELINUX_STATES) return stdout
            // 取不到不代表没开：被 SELinux 拒绝恰恰说明当前域是 enforcing
            if (shell.code != 0 && shell.stderr.trim().endsWith("Permission denied")) return "Enforcing"
        }
        when (readFile("/sys/fs/selinux/enforce")?.trim()) {
            "1" -> return "Enforcing"
            "0" -> return "Permissive"
        }
        return if (File("/sys/fs/selinux").exists()) "Unknown" else "Disabled"
    }

    // -------------------------------------------------------------- Seccomp

    /** 0 = 关闭，1 = strict，2 = filter；-1 = 内核不支持或调用失败。 */
    private fun readSeccompStatus(): Int =
        runCatching { Os.prctl(PR_GET_SECCOMP, 0, 0, 0, 0) }.getOrDefault(-1)

    // --------------------------------------------------------------- 内核

    private fun kernelVersion(): String =
        runCatching { Os.uname().release }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: readFile("/proc/version")
                ?.let { Regex("""Linux version (\S+)""").find(it)?.groupValues?.getOrNull(1) }
            ?: System.getProperty("os.version")?.takeIf { it.isNotBlank() }
            ?: "unknown"

    // ------------------------------------------------------- 属性 / 进程

    /**
     * `android.os.SystemProperties` 是隐藏类，反射在部分 ROM 上会被隐藏 API 策略拦截，
     * 所以再补一条 `getprop` 子进程兜底 —— 普通应用也有权限执行。
     */
    private object Props {
        private val cache = ConcurrentHashMap<String, String>()

        fun get(key: String): String {
            cache[key]?.let { return it }
            val value = byReflection(key) ?: byGetprop(key)
            cache[key] = value.orEmpty()
            return value.orEmpty()
        }

        @SuppressLint("PrivateApi")
        private fun byReflection(key: String): String? =
            runCatching {
                Class.forName("android.os.SystemProperties")
                    .getMethod("get", String::class.java)
                    .invoke(null, key) as? String
            }.getOrNull()?.trim()?.takeIf(String::isNotEmpty)

        private fun byGetprop(key: String): String? =
            runCatching {
                val process = ProcessBuilder("getprop", key).redirectErrorStream(true).start()
                val finished =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        process.waitFor(SHELL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    } else {
                        process.waitFor()
                        true
                    }
                if (!finished) {
                    process.destroyForcibly()
                    return null
                }
                process.inputStream.bufferedReader().use { it.readText() }.trim().takeIf(String::isNotEmpty)
            }.getOrNull()
    }

    private fun prop(key: String): String = Props.get(key)

    private class ShellResult(val code: Int, val stdout: String, val stderr: String)

    private fun exec(command: String): ShellResult? =
        runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(false).start()
            val finished =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    process.waitFor(SHELL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                } else {
                    process.waitFor()
                    true
                }
            if (!finished) {
                process.destroyForcibly()
                return null
            }
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            ShellResult(process.exitValue(), stdout, stderr)
        }.getOrNull()

    private fun readFile(path: String): String? =
        runCatching {
            val file = File(path)
            if (!file.canRead()) return null
            file.readText()
        }.getOrNull()
}
