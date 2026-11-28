package com.nekosu.manager.data.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** NcoreBoot 的纯逻辑：版本比对与 shell 命令构造，不依赖 Android 运行时。 */
class NcoreBootTest {
    @Test
    fun `缓存版本与运行版本一致时跳过安装`() {
        assertFalse(NcoreBoot.shouldInstall(cachedVersion = 42L, runningVersion = 42L))
    }

    @Test
    fun `运行版本变化时触发安装`() {
        assertTrue(NcoreBoot.shouldInstall(cachedVersion = 41L, runningVersion = 42L))
        assertTrue(NcoreBoot.shouldInstall(cachedVersion = 42L, runningVersion = 41L))
    }

    @Test
    fun `拿不到运行版本时仍触发安装`() {
        // version=0 与上游一致：版本未知不能当「已匹配」，要装一次确保换代
        assertTrue(NcoreBoot.shouldInstall(cachedVersion = 42L, runningVersion = 0L))
    }

    @Test
    fun `首次安装时触发`() {
        // 缓存默认值是 -1，任何有效版本都大于它
        assertTrue(NcoreBoot.shouldInstall(cachedVersion = -1L, runningVersion = 1L))
    }

    @Test
    fun `install 命令使用单引号包裹路径`() {
        assertEquals(
            "'/data/app/~~abc/base.apk/lib/arm64-v8a/libncore.so' install",
            NcoreBoot.installCommand("/data/app/~~abc/base.apk/lib/arm64-v8a/libncore.so"),
        )
    }

    @Test
    fun `路径中的单引号被正确转义`() {
        assertEquals("'a'\\''b' install", NcoreBoot.installCommand("a'b"))
    }
}
