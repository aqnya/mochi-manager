package com.nekosu.manager.neko

import com.nekosu.manager.data.SuProfile
import com.nekosu.manager.data.isValidSelinuxContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内核 `IOC_GET_PROFILES` 文本解析。
 *
 * 内核每行输出 `<uid> <caps_hex> <ns> <root_uid> <selinux_domain>`，
 * 之前本地少读了一列、把 root_uid（默认 0）当成 SELinux 域，
 * 结果授权页里上下文偶发变成 "0"，再次保存时把这个非法域写回内核导致授权异常。
 */
class NekoKernelProfileTest {
    private val header =
        "# nksu allow.profile v1\n" +
            "# <uid> <caps_hex> <ns> <root_uid> <selinux_domain>\n"

    @Test
    fun `新格式读第5列作为SELinux域而不是root_uid`() {
        val profiles = parseAllowProfile("${header}10123 0000000000280ccf 0 0 u:r:nksu:s0\n")
        assertEquals(1, profiles.size)
        val p = profiles.single()
        assertEquals(10_123, p.uid)
        assertEquals(0x00280CCFL, p.caps)
        assertEquals(0, p.namespace)
        assertEquals(0, p.rootUid)
        assertEquals("u:r:nksu:s0", p.domain)
        assertFalse(p.domain == "0")
    }

    @Test
    fun `非root运行身份也不会把root_uid误当成域`() {
        val profiles = parseAllowProfile("10123 0000000000000001 2 2000 u:r:app:s0\n")
        assertEquals(2000, profiles.single().rootUid)
        assertEquals("u:r:app:s0", profiles.single().domain)
    }

    @Test
    fun `内核未设置域时会回退到默认域`() {
        val profiles = parseAllowProfile("${header}10123 0000000000280ccf 0 0 -\n")
        assertEquals(SuProfile.DEFAULT_CONTEXT, profiles.single().domain)
    }

    @Test
    fun `旧版四列格式仍按第4列解析域`() {
        val profiles = parseAllowProfile("10123 0000000000280ccf 0 u:r:legacy:s0\n")
        val p = profiles.single()
        assertEquals(0, p.rootUid)
        assertEquals("u:r:legacy:s0", p.domain)
    }

    @Test
    fun `旧版三列格式回退默认域`() {
        val profiles = parseAllowProfile("10123 0000000000280ccf 0\n")
        val p = profiles.single()
        assertEquals(SuProfile.DEFAULT_CONTEXT, p.domain)
    }

    @Test
    fun `注释空行和坏行被跳过但其他条目保留`() {
        val text =
            "$header\n" +
                "10123 0000000000280ccf 0 0 u:r:nksu:s0\n" +
                "not-a-uid 0000000000280ccf 0 0 u:r:nksu:s0\n" +
                "10124 invalid 0 0 u:r:nksu:s0\n" +
                "10125 1\n" +
                "10126 0000000000280ccf 0 0 u:r:other:s0\n"
        val profiles = parseAllowProfile(text)
        assertEquals(listOf(10_123, 10_126), profiles.map { it.uid })
    }

    @Test
    fun `tab分隔也能解析`() {
        val profiles = parseAllowProfile("10123\t0000000000280ccf\t0\t0\tu:r:nksu:s0\n")
        assertEquals("u:r:nksu:s0", profiles.single().domain)
    }

    @Test
    fun `域格式校验拒绝数字和占位符`() {
        assertTrue(isValidSelinuxContext("u:r:nksu:s0"))
        assertTrue(isValidSelinuxContext("u:r:su:s0:c512,c768"))
        assertFalse(isValidSelinuxContext("0"))
        assertFalse(isValidSelinuxContext("-"))
        assertFalse(isValidSelinuxContext(""))
        assertFalse(isValidSelinuxContext("12345"))
    }
}
