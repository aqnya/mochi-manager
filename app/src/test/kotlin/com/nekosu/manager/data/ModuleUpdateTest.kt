package com.nekosu.manager.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * updateJson 清单与 module.prop 的解析，以及更新比较逻辑。
 *
 * 字段都当可选处理：坏一条数据丢 null，让界面给出明确提示而不是崩。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class ModuleUpdateTest {
    @Test
    fun `解析标准 updateJson`() {
        val raw =
            """
            {
              "version": "v2.0",
              "versionCode": 20,
              "zipUrl": "https://example.com/m.zip",
              "changelog": "修了一堆"
            }
            """.trimIndent()

        val info = ModuleUpdate.parseManifest(raw)!!

        assertEquals("v2.0", info.version)
        assertEquals(20L, info.versionCode)
        assertEquals("https://example.com/m.zip", info.zipUrl)
        assertEquals("修了一堆", info.changelog)
    }

    @Test
    fun `缺 zipUrl 的清单按无效处理`() {
        assertNull(ModuleUpdate.parseManifest("""{"version":"v1","versionCode":1}"""))
        assertNull(ModuleUpdate.parseManifest("not json at all"))
    }

    @Test
    fun `moduleprop 解析 versionCode 与 updateJson`() {
        val prop =
            """
            id=demo
            name=Demo
            version=v1.0
            versionCode=10
            updateJson=https://example.com/demo.json
            # 注释行
            """.trimIndent()

        val parsed = ModuleUpdate.parseProp(prop)

        assertEquals(10L, parsed.versionCode)
        assertEquals("https://example.com/demo.json", parsed.updateJson)
    }

    @Test
    fun `versionCode 不是数字时按 0 处理`() {
        assertEquals(0L, ModuleUpdate.parseProp("versionCode=abc").versionCode)
    }

    @Test
    fun `decide 新版可用`() {
        val info = ModuleUpdateInfo("v3.0", 30, "https://example.com/m.zip", "")

        val result = ModuleUpdate.decide(info, currentVersionCode = 20L)

        assertTrue(result is ModuleUpdate.CheckResult.Available)
    }

    @Test
    fun `decide versionCode 相同或更旧提示已是最新`() {
        val info = ModuleUpdateInfo("v3.0", 30, "https://example.com/m.zip", "")

        assertTrue(ModuleUpdate.decide(info, currentVersionCode = 30L) is ModuleUpdate.CheckResult.UpToDate)
        assertTrue(ModuleUpdate.decide(info, currentVersionCode = 40L) is ModuleUpdate.CheckResult.UpToDate)
    }

    @Test
    fun `decide 当前版本未知时不误报已是最新`() {
        val info = ModuleUpdateInfo("v3.0", 30, "https://example.com/m.zip", "")

        assertTrue(ModuleUpdate.decide(info, currentVersionCode = 0L) is ModuleUpdate.CheckResult.Available)
    }

    @Test
    fun `decide 无效清单给失败结果`() {
        assertTrue(ModuleUpdate.decide(null, currentVersionCode = 1L) is ModuleUpdate.CheckResult.Failed)
        assertTrue(
            ModuleUpdate.decide(ModuleUpdateInfo("v1", 1, zipUrl = "", changelog = ""), 1L)
                is ModuleUpdate.CheckResult.Failed,
        )
    }
}
