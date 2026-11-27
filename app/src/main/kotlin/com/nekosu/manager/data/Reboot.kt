package com.nekosu.manager.data

import com.nekosu.manager.R

/**
 * 重启目标。reason 与内核 `reboot(2)` 的参数、上游 KernelSU 的菜单一一对应。
 *
 * 上游还有一项 `soft_reboot` 走 `ksud soft-reboot`，ncore 没有对等的守护进程命令，
 * 这里不提供；设置里的「软重启」走的是内核通道的开关，和这个菜单无关。
 */
enum class RebootTarget(
    val reason: String,
    val labelRes: Int,
) {
    SYSTEM("", R.string.reboot_system),
    USERSPACE("userspace", R.string.reboot_userspace),
    RECOVERY("recovery", R.string.reboot_recovery),
    BOOTLOADER("bootloader", R.string.reboot_bootloader),
    EDL("edl", R.string.reboot_edl),
}

object Reboot {
    /**
     * 按设备能力筛出菜单项。「重启到用户空间」依赖 `PowerManager
     * .isRebootingUserspaceSupported`——API 37 起平台废弃了 userspace reboot，
     * 该方法在新系统上恒返回 false；不支持的机器上发这个 reason 只会得到
     * 一个开不了机的黑屏，所以没有这项时直接从菜单里去掉。
     */
    fun targets(userspaceSupported: Boolean): List<RebootTarget> =
        buildList {
            add(RebootTarget.SYSTEM)
            if (userspaceSupported) add(RebootTarget.USERSPACE)
            add(RebootTarget.RECOVERY)
            add(RebootTarget.BOOTLOADER)
            add(RebootTarget.EDL)
        }

    /**
     * 与上游同款的双保险：`svc power reboot` 失败再退回裸 `reboot`。
     * recovery 前先按一次 KEYCODE_POWER（26），消掉厂商 recovery 里
     * 误导人的「清除数据」确认文案——这也是上游的做法。
     */
    fun shellCommand(target: RebootTarget): String {
        val args = if (target.reason.isEmpty()) "" else " ${target.reason}"
        val reboot = "/system/bin/svc power reboot$args || /system/bin/reboot$args"
        return when (target) {
            RebootTarget.RECOVERY -> "/system/bin/input keyevent 26; $reboot"
            else -> reboot
        }
    }

    /** 执行重启。root 拿不到时抛 [RootUnavailableException]，由调用方提示。 */
    suspend fun perform(target: RebootTarget) {
        RootShell.exec(shellCommand(target))
    }
}
