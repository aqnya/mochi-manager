package com.nekosu.manager.neko

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nekosu.manager.data.SuCapabilities
import com.nekosu.manager.data.capsFromMask
import com.nekosu.manager.data.capsMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        val expected =
            mapOf(
                "CAP_DAC_OVERRIDE" to 1,
                "CAP_DAC_READ_SEARCH" to 2,
                "CAP_SYS_ADMIN" to 21,
                "CAP_SYS_PTRACE" to 19,
                "CAP_NET_ADMIN" to 12,
                "CAP_NET_RAW" to 13,
                "CAP_SETUID" to 7,
                "CAP_SETGID" to 6,
            )
        assertEquals(expected, SuCapabilities.associate { it.name to it.bit })
    }
}
