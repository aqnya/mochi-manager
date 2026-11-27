package com.nekosu.manager.data

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 日志收集失败（不是没 root，而是命令本身跑挂了）。 */
class BugreportException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * 一次收集的产物。
 *
 * [rooted] 为 false 表示拿不到 root，报告是以应用自身身份收集的：
 * 设备信息、SELinux、自身进程与日志都在，dmesg / `/data/adb` / tombstones 这些需要特权的部分是空的。
 * [missing] 列出这次没收到的文件（0 字节的已删除），也写进了报告里的 `missing.txt`。
 */
data class BugreportResult(
    val file: File,
    val rooted: Boolean,
    val missing: List<String>,
)

/**
 * 生成一份可提交的问题报告（tar.gz），内容对齐 KernelSU 的 bugreport：
 * 设备信息、SELinux、进程表、dmesg、logcat、tombstones/dropbox/pstore、
 * 厂商 diag 日志、mountinfo、/data/adb 清单、KernelSU 白名单与 ksud 版本。
 *
 * 所有命令写进一个 sh 脚本一次执行，逐条 `2>/dev/null`：某个路径在特定 ROM 上不存在只丢那一份，
 * 不影响其余文件。有 root 时脚本走 `su`，没有 root 就以应用身份跑同一份脚本——
 * 能读到的照旧收集，读不到的记进 `missing.txt`，不再整份失败。
 */
object Bugreport {
    private const val TAG = "NekoSU/Bugreport"
    private const val COLLECT_TIMEOUT_SECONDS = 150L
    // 无 root 时很多命令会失败/重试（logcat 只返回本应用、tar 访问特权目录被拒），
    // 比 root 路径慢得多；60s 在 HyperOS 上不够，会误判成「收集失败」。
    private const val LOCAL_TIMEOUT_SECONDS = 150L
    private const val PACK_TIMEOUT_SECONDS = 120L

    suspend fun build(context: Context): BugreportResult =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "bugreport")
            dir.deleteRecursively()
            if (!dir.mkdirs()) throw BugreportException("无法创建日志目录")

            val script = File(dir, "collect.sh").apply { writeText(collectScript(dir)) }
            // 探测本身会弹 KernelSU 授权框；被拒绝/超时/没装 su 都按「无 root」处理，继续往下收集
            val rooted = RootShell.isRootAvailable()
            Log.d(TAG, "开始收集 rooted=$rooted dir=${dir.absolutePath}")
            val collected =
                if (rooted) {
                    RootShell.exec("sh ${quote(script.absolutePath)}", COLLECT_TIMEOUT_SECONDS)
                } else {
                    RootShell.execAsUser("sh ${quote(script.absolutePath)}", LOCAL_TIMEOUT_SECONDS)
                }
            Log.d(TAG, "收集结束 exit=${collected.exitCode} 输出=${collected.output.length} 字")
            if (collected.exitCode != 0) {
                throw BugreportException(
                    collected.output.ifBlank { "收集脚本退出码 ${collected.exitCode}" },
                )
            }

            File(dir, "basic.txt").writeText(basicReport(context, rooted))
            // 脚本跑完才有 getenforce 的结果，这里补进 basic.txt 之外的一份原始记录
            if (!File(dir, "selinux.txt").isFile) {
                File(dir, "selinux.txt").writeText(DeviceInfoRepository.current()?.selinuxStatus.orEmpty())
            }
            val missing = pruneEmpty(dir)

            val target = File(context.cacheDir, bugreportName(Date()))
            val packCommand =
                "tar czf ${quote(target.absolutePath)} -C ${quote(dir.absolutePath)} ." +
                    " && chmod 0644 ${quote(target.absolutePath)}"
            val packed =
                if (rooted) {
                    RootShell.exec(packCommand, PACK_TIMEOUT_SECONDS)
                } else {
                    RootShell.execAsUser(packCommand, PACK_TIMEOUT_SECONDS)
                }
            Log.d(TAG, "打包结束 exit=${packed.exitCode} 文件=${target.isFile} 大小=${if (target.isFile) target.length() else -1}")
            if (packed.exitCode != 0 || !target.isFile || target.length() == 0L) {
                target.delete()
                throw BugreportException(packed.output.ifBlank { "打包失败，退出码 ${packed.exitCode}" })
            }
            dir.deleteRecursively()
            BugreportResult(target, rooted, missing)
        }

    /** 供保存/分享时复用的文件名。 */
    internal fun bugreportName(now: Date): String =
        "NekoSU_bugreport_${SimpleDateFormat("yyyy-MM-dd_HH_mm", Locale.US).format(now)}.tar.gz"

    /**
     * 删掉 0 字节的输出，并把它们的名字写进 `missing.txt`。
     *
     * 空文件看着像「这份日志是空的」，实际是命令被拒绝或路径不存在；单独列出来，
     * 看报告的人不用一个个点开猜。返回缺失清单，供界面提示。
     */
    internal fun pruneEmpty(dir: File): List<String> {
        val missing =
            dir.listFiles()
                .orEmpty()
                .filter { it.isFile && it.length() == 0L && it.name != "collect.sh" }
                .map { it.name }
                .sorted()
        missing.forEach { File(dir, it).delete() }
        if (missing.isNotEmpty()) {
            File(dir, "missing.txt").writeText(
                buildString {
                    appendLine("本次没有采集到的内容（0 字节的输出已删除）：")
                    missing.forEach { appendLine("- $it") }
                    appendLine()
                    appendLine("无 root 时 dmesg、/data/adb、tombstones、dropbox 等需要特权的路径会整体缺失，属正常现象；")
                    appendLine("个别厂商 ROM 也会把 logcat 限制为只返回本应用的记录。")
                },
            )
        }
        return missing
    }

    /** 收集脚本。纯函数，便于单测检查关键命令都在。 */
    internal fun collectScript(dir: File): String =
        """
        #!/system/bin/sh
        DIR='${dir.absolutePath}'
        cd "${'$'}DIR" || exit 1

        # 基础状态：这些大多世界可读，没有 root 也能拿到
        getenforce > selinux.txt 2>/dev/null
        getprop > props.txt 2>/dev/null
        cat /proc/1/mountinfo > mounts.txt 2>/dev/null
        cat /proc/filesystems > filesystems.txt 2>/dev/null
        cat /proc/modules > proc_modules.txt 2>/dev/null
        cat /proc/version > proc_version.txt 2>/dev/null
        cat /proc/self/status > self_status.txt 2>/dev/null
        cat /proc/self/mountinfo > self_mounts.txt 2>/dev/null
        # kptr_restrict 不为 1 时 kallsyms 的地址全是 0，临时打开再还原（无 root 时这两行会静默失败）
        ORIG="${'$'}(cat /proc/sys/kernel/kptr_restrict 2>/dev/null)"
        echo 1 > /proc/sys/kernel/kptr_restrict 2>/dev/null
        cat /proc/kallsyms > kallsyms.txt 2>/dev/null
        [ -n "${'$'}ORIG" ] && echo "${'$'}ORIG" > /proc/sys/kernel/kptr_restrict 2>/dev/null
        cp /proc/bootconfig boot_config.txt 2>/dev/null
        cp /proc/config.gz defconfig.gz 2>/dev/null
        cp /data/system/packages.list packages.txt 2>/dev/null

        # 进程与日志：无 root 时 logcat 只返回本应用的记录，dmesg 通常被拒绝
        (ps -A -o PID,TID,UID,COMM,CMDLINE,STAT,LABEL 2>/dev/null || ps -A 2>/dev/null) > process.txt
        (dmesg -r 2>/dev/null || dmesg 2>/dev/null) > dmesg.txt
        (logcat -b all -v uid -d 2>/dev/null || logcat -b all -d 2>/dev/null || logcat -d 2>/dev/null) > logcat.txt

        # 崩溃与厂商日志，路径不存在就留空
        tar -czf tombstones.tar.gz -C /data/tombstones . 2>/dev/null
        tar -czf dropbox.tar.gz -C /data/system/dropbox . 2>/dev/null
        tar -czf pstore.tar.gz -C /sys/fs/pstore . 2>/dev/null
        tar -czf diag.tar.gz -C /data/vendor/diag . --exclude=./minidump.gz 2>/dev/null
        tar -czf oplus.tar.gz -C /mnt/oplus/op2/media/log/boot_log . 2>/dev/null
        tar -czf ksu_log.tar.gz -C /data/adb/ksu/log . 2>/dev/null

        # KernelSU 自身状态
        (/data/adb/ksud --version 2>/dev/null || ksud --version 2>/dev/null) > ksud_version.txt
        cat /data/adb/ksu/.allowlist > allowlist.bin 2>/dev/null
        du -sh /data/adb/ksu/* > ksu_size.txt 2>/dev/null
        ls -alR /data/adb > adb_tree.txt 2>/dev/null
        ls -al /data/adb/modules > modules.txt 2>/dev/null

        exit 0
        """.trimIndent() + "\n"

    /** 设备与管理器信息，纯文本，方便直接看。 */
    internal fun basicReport(
        context: Context,
        rooted: Boolean = false,
    ): String {
        val info = DeviceInfoRepository.current() ?: runCatching { DeviceInfoRepository.loadNow(context) }.getOrNull()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        return buildString {
            appendLine("NekoSU Manager")
            appendLine("Time: $now")
            appendLine("Manager: ${info?.managerVersion ?: managerVersion(context)}")
            appendLine("Collected as: ${if (rooted) "root" else "app (no root)"}")
            appendLine("Root: ${if (rooted) "yes" else "no"}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}, preview ${Build.VERSION.PREVIEW_SDK_INT})")
            appendLine("BRAND: ${Build.BRAND}")
            appendLine("MODEL: ${Build.MODEL}")
            appendLine("PRODUCT: ${Build.PRODUCT}")
            appendLine("DEVICE: ${Build.DEVICE}")
            appendLine("MANUFACTURER: ${Build.MANUFACTURER}")
            appendLine("FINGERPRINT: ${Build.FINGERPRINT}")
            appendLine("DeviceModel: ${info?.model ?: "Unknown"}")
            appendLine("System: ${info?.system ?: "Unknown"}")
            appendLine("Kernel: ${info?.kernel ?: System.getProperty("os.version").orEmpty()}")
            appendLine("SELinux: ${info?.selinuxStatus ?: "Unknown"}")
            appendLine("Seccomp: ${info?.seccompStatus ?: -1}")
        }
    }

    private fun managerVersion(context: Context): String =
        runCatching {
            val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pkg.versionName} (${if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else @Suppress("DEPRECATION") pkg.versionCode})"
        }.getOrDefault("Unknown")

    /** 单引号包裹，内部单引号转义，避免路径里有空格或引号时命令断掉。 */
    private fun quote(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"
}
