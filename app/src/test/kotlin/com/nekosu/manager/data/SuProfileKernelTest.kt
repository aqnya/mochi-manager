package com.nekosu.manager.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 授权 / 撤销在内核侧的动作序列。
 *
 * 上游统一 profile ioctl（退役 per-field 的 add/del/has uid）后，内核表只由
 * 「整条 upsert」与「按 uid 删」驱动，落盘也封在同一次 JNI 调用里：
 *  - 授权：一次 `setProfile` 即建表项 + 写内容 + 落盘；
 *  - 撤销：一次 `removeProfile`，内核表项与落盘条目一起删（幂等）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class SuProfileKernelTest {
    private class FakeKernel(
        private val profileResult: Boolean = true,
        private val removeResult: Boolean = true,
    ) : KernelOps {
        val calls = mutableListOf<String>()

        override fun setProfile(
            uid: Int,
            caps: Long,
            domain: String,
            ns: Int,
            rootUid: Int,
        ): Boolean {
            calls += "profile:$uid:${caps.toString(16)}:$ns:$rootUid:$domain"
            return profileResult
        }

        override fun removeProfile(uid: Int): Boolean {
            calls += "remove:$uid"
            return removeResult
        }
    }

    @Test
    fun `撤销只调一次 removeProfile`() {
        val kernel = FakeKernel()
        applyKernel(SuProfile("com.demo", 10_123, allowSu = false), kernel)
        assertEquals(listOf("remove:10123"), kernel.calls)
    }

    @Test
    fun `撤销失败也要如实记录`() {
        val kernel = FakeKernel(removeResult = false)
        applyKernel(SuProfile("com.demo", 10_123, allowSu = false), kernel)
        // 没有额外步骤可补：失败就只剩这一条调用，日志里能看出撤销没生效
        assertEquals(listOf("remove:10123"), kernel.calls)
    }

    @Test
    fun `授权只调一次整条 setProfile`() {
        val kernel = FakeKernel()
        val profile =
            SuProfile(
                "com.demo",
                10_123,
                allowSu = true,
                capabilities = setOf("CAP_CHOWN", "CAP_SYS_ADMIN"),
            )
        applyKernel(profile, kernel)
        assertEquals(1, kernel.calls.size)
        val caps = capsMask(profile.capabilities).toString(16)
        assertTrue(kernel.calls[0].startsWith("profile:10123:$caps:"))
        assertTrue(kernel.calls[0].endsWith(":${profile.context}"))
    }

    @Test
    fun `撤销时不会写 profile`() {
        val kernel = FakeKernel()
        applyKernel(SuProfile("com.demo", 10_123, allowSu = false), kernel)
        assertTrue(kernel.calls.none { it.startsWith("profile:") })
    }

    @Test
    fun `损坏的SELinux域在写入内核前回退默认值`() {
        val kernel = FakeKernel()
        val profile = SuProfile("com.demo", 10_123, allowSu = true, context = "0")
        applyKernel(profile, kernel)
        assertTrue(kernel.calls[0].endsWith(":${SuProfile.DEFAULT_CONTEXT}"))
        assertFalse(kernel.calls[0].endsWith(":0"))
    }
}
