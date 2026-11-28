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
 * 落盘现在跟内核写一起封在 JNI 里（对齐上游 `adduid` / `deluid` / `setProfileProto`），
 * 这一层只负责「调哪个、按什么顺序」：
 *  - 撤销必须是**一次** `setAllowed(uid, false)`，它同时删内核表项与落盘条目；
 *  - 授权先建表项再写 profile（后者同时落盘），顺序反了路径规则可能没同步。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class SuProfileKernelTest {
    private class FakeKernel(
        private val allowResult: Boolean = true,
    ) : KernelOps {
        val calls = mutableListOf<String>()

        override fun setAllowed(
            uid: Int,
            allow: Boolean,
        ): Boolean {
            calls += "allow:$uid:$allow"
            return allowResult
        }

        override fun setProfile(
            uid: Int,
            caps: Long,
            domain: String,
            ns: Int,
            rootUid: Int,
        ): Boolean {
            calls += "profile:$uid:${caps.toString(16)}:$ns:$rootUid:$domain"
            return true
        }
    }

    @Test
    fun `撤销只调一次内核接口`() {
        val kernel = FakeKernel()
        applyKernel(SuProfile("com.demo", 10_123, allowSu = false), kernel)
        assertEquals(listOf("allow:10123:false"), kernel.calls)
    }

    @Test
    fun `撤销失败也要如实记录`() {
        val kernel = FakeKernel(allowResult = false)
        applyKernel(SuProfile("com.demo", 10_123, allowSu = false), kernel)
        // 没有额外步骤可补：失败就只剩这一条调用，日志里能看出撤销没生效
        assertEquals(listOf("allow:10123:false"), kernel.calls)
    }

    @Test
    fun `授权按 表项 profile 的顺序写入`() {
        val kernel = FakeKernel()
        val profile =
            SuProfile(
                "com.demo",
                10_123,
                allowSu = true,
                capabilities = setOf("CAP_CHOWN", "CAP_SYS_ADMIN"),
            )
        applyKernel(profile, kernel)
        assertEquals(2, kernel.calls.size)
        assertEquals("allow:10123:true", kernel.calls[0])
        val caps = capsMask(profile.capabilities).toString(16)
        assertTrue(kernel.calls[1].startsWith("profile:10123:$caps:"))
        assertTrue(kernel.calls[1].endsWith(":${profile.context}"))
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
        assertTrue(kernel.calls[1].endsWith(":${SuProfile.DEFAULT_CONTEXT}"))
        assertFalse(kernel.calls[1].endsWith(":0"))
    }
}
