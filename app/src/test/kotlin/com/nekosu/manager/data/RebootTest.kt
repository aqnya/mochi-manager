package com.nekosu.manager.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重启菜单的数据层：菜单项按设备能力筛选、shell 命令与上游 KernelSU 的
 * `svc power reboot $reason || /system/bin/reboot $reason` 同款。
 */
class RebootTest {
    @Test
    fun `菜单默认按能力隐藏用户空间重启`() {
        val targets = Reboot.targets(userspaceSupported = false)
        assertFalse(targets.contains(RebootTarget.USERSPACE))
        assertTrue(targets.contains(RebootTarget.SYSTEM))
        assertTrue(targets.contains(RebootTarget.RECOVERY))
        assertTrue(targets.contains(RebootTarget.BOOTLOADER))
        assertTrue(targets.contains(RebootTarget.EDL))
    }

    @Test
    fun `支持的设备菜单含用户空间重启`() {
        assertTrue(Reboot.targets(userspaceSupported = true).contains(RebootTarget.USERSPACE))
    }

    @Test
    fun `普通重启用双保险命令`() {
        assertEquals(
            "/system/bin/svc power reboot || /system/bin/reboot",
            Reboot.shellCommand(RebootTarget.SYSTEM),
        )
    }

    @Test
    fun `带 reason 的目标命令拼装`() {
        assertEquals(
            "/system/bin/svc power reboot bootloader || /system/bin/reboot bootloader",
            Reboot.shellCommand(RebootTarget.BOOTLOADER),
        )
        assertEquals(
            "/system/bin/svc power reboot userspace || /system/bin/reboot userspace",
            Reboot.shellCommand(RebootTarget.USERSPACE),
        )
    }

    @Test
    fun `recovery 先按电源键再重启`() {
        assertEquals(
            "/system/bin/input keyevent 26; /system/bin/svc power reboot recovery || /system/bin/reboot recovery",
            Reboot.shellCommand(RebootTarget.RECOVERY),
        )
    }
}
