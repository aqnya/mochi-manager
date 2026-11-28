package com.nekosu.manager.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 内核特性表解析：内核 `nksu_feature_render` 输出 `<id> <name> <value>\n`，
 * value 是 u64，非 0 即启用。解析器要兼容多空白（tab/空格混排）并跳过脏行。
 */
class KernelFeatureTest {
    @Test
    fun `解析内核上报的两个 feature`() {
        val text = "1 selinux_hide 1\n2 mount_hide 0\n"
        assertEquals(
            listOf(
                KernelFeature.Info(1, "selinux_hide", true),
                KernelFeature.Info(2, "mount_hide", false),
            ),
            parseFeatureTable(text),
        )
    }

    @Test
    fun `多空白分隔与行首尾空白都能解析`() {
        val text = "  1\tselinux_hide\t1  \n 2   umount   0"
        assertEquals(
            listOf(
                KernelFeature.Info(1, "selinux_hide", true),
                KernelFeature.Info(2, "umount", false),
            ),
            parseFeatureTable(text),
        )
    }

    @Test
    fun `空值按非零判定启用且大数值不溢出`() {
        val text = "1 selinux_hide 18446744073709551615\n"
        assertEquals(listOf(KernelFeature.Info(1, "selinux_hide", true)), parseFeatureTable(text))
    }

    @Test
    fun `脏行被跳过不影响其他行`() {
        val text = "garbage\nx no_value\n1 selinux_hide 1\n\n2 umount\n"
        assertEquals(
            listOf(KernelFeature.Info(1, "selinux_hide", true)),
            parseFeatureTable(text),
        )
    }

    @Test
    fun `null 与空串返回空列表`() {
        assertEquals(emptyList<KernelFeature.Info>(), parseFeatureTable(null))
        assertEquals(emptyList<KernelFeature.Info>(), parseFeatureTable(""))
    }
}
