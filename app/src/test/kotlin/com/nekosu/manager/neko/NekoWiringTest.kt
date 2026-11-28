package com.nekosu.manager.neko

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nekosu.manager.data.KernelFeature
import com.nekosu.manager.data.SuCapabilities
import com.nekosu.manager.data.SuProfile
import com.nekosu.manager.data.capsFromMask
import com.nekosu.manager.data.capsMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 底层接线的边界行为。
 *
 * 单测跑在 JVM 上，`System.loadLibrary("neko")` 必然失败——这正好验证「没有 native 库时
 * 整套内核调用要安静地返回不可用」，而不是抛异常把界面拖垮。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class NekoWiringTest {
    @Test
    fun `没有 native 库时内核通道判为不可用`() {
        assertFalse(NekoNative.loaded)
        assertNull(NekoNative.version())
        assertFalse(NekoNative.available())
        assertNull(NekoNative.hasUid(10000))
        assertNull(NekoNative.listProfiles())
        assertFalse(NekoNative.addUid(10000))
        assertNull(NekoNative.featureList())
        assertFalse(NekoNative.featureSet(KernelFeature.SELINUX_HIDE, 1L))
    }

    @Test
    fun `内核 feature 在无 native 库时返回空列表与 false`() {
        assertEquals(emptyList<KernelFeature.Info>(), KernelFeature.list())
        assertFalse(KernelFeature.set(KernelFeature.UMOUNT, true))
    }

    @Test
    fun `内核 feature id 与内核 feature h 一致`() {
        // kernel/manager/feature.h：SELINUX_HIDE=1、UMOUNT=2，是跨端线上契约
        assertEquals(1, KernelFeature.SELINUX_HIDE)
        assertEquals(2, KernelFeature.UMOUNT)
    }

    @Test
    fun `NekoKernel 在无内核时返回 null 而不是抛异常`() {
        assertNull(NekoKernel.version())
        assertFalse(NekoKernel.available)
        assertNull(NekoKernel.profiles())
        assertNull(NekoKernel.features())
        assertNull(NekoKernel.isAllowed(10000))
    }

    @Test
    fun `能力位与内核 caps 互转`() {
        val names = setOf("CAP_DAC_OVERRIDE", "CAP_SYS_ADMIN", "CAP_SETGID")
        val mask = capsMask(names)
        assertEquals((1L shl 1) or (1L shl 21) or (1L shl 6), mask)
        assertEquals(names, capsFromMask(mask))
    }

    @Test
    fun `空能力集与未知名字不影响掩码`() {
        assertEquals(0L, capsMask(emptySet()))
        assertEquals(0L, capsMask(setOf("CAP_NOT_A_THING")))
        assertEquals(emptySet<String>(), capsFromMask(0L))
    }

    @Test
    fun `内置能力位的编号与 Linux capability 一致`() {
        // 全部 41 项（CAP_LAST_CAP = CAP_CHECKPOINT_RESTORE = 40），
        // 位编号连续且不重复，界面的分组列表不会漏项
        assertEquals(41, SuCapabilities.size)
        assertEquals((0..40).toList(), SuCapabilities.map { it.bit }.sorted())
        val expected =
            mapOf(
                "CAP_CHOWN" to 0,
                "CAP_DAC_OVERRIDE" to 1,
                "CAP_SYS_CHROOT" to 18,
                "CAP_SYS_PTRACE" to 19,
                "CAP_SYS_ADMIN" to 21,
                "CAP_NET_ADMIN" to 12,
                "CAP_SETFCAP" to 31,
                "CAP_PERFMON" to 38,
                "CAP_BPF" to 39,
                "CAP_CHECKPOINT_RESTORE" to 40,
            )
        val actual = SuCapabilities.associate { it.name to it.bit }
        expected.forEach { (name, bit) -> assertEquals(bit, actual[name]) }
        // 每一项都有分类与中文描述，弹窗分组展示才有内容
        assertTrue(SuCapabilities.all { it.category.isNotBlank() && it.desc.isNotBlank() })
    }

    @Test
    fun `默认能力位都是已知项，且不会顺手放开高危位`() {
        val known = SuCapabilities.map { it.name }.toSet()
        assertTrue(SuProfile.DEFAULT_CAPABILITIES.all { it in known })
        // 新授权的默认值要能把 root shell 正常跑起来
        assertTrue("CAP_SYS_ADMIN" in SuProfile.DEFAULT_CAPABILITIES)
        assertTrue("CAP_DAC_OVERRIDE" in SuProfile.DEFAULT_CAPABILITIES)
        // 内核模块加载、裸设备、改时钟、改 SELinux 策略这类必须用户自己勾
        listOf("CAP_SYS_MODULE", "CAP_SYS_RAWIO", "CAP_SYS_TIME", "CAP_MAC_ADMIN").forEach {
            assertFalse(it in SuProfile.DEFAULT_CAPABILITIES)
        }
        // 默认 profile 与 reset() 都带默认能力位，但不算「自定义」
        val profile = SuProfile.default("com.demo", 10_123)
        assertEquals(SuProfile.DEFAULT_CAPABILITIES, profile.capabilities)
        assertFalse(profile.isCustom)
        assertFalse(profile.reset().isCustom)
        // capsMask 能把这 8 项拼成内核位，再解回来还是同一组
        val expectedBits = setOf(0, 1, 2, 3, 7, 6, 21, 19).fold(0L) { mask, bit -> mask or (1L shl bit) }
        assertEquals(expectedBits, capsMask(SuProfile.DEFAULT_CAPABILITIES))
        assertEquals(SuProfile.DEFAULT_CAPABILITIES, capsFromMask(expectedBits))
    }
}
