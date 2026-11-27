package com.nekosu.manager.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * `ncore module list --json` 的解析。
 *
 * 字段来自 module.prop，ncore 会把已知开关转成真布尔，其余保持字符串；
 * 两种写法都要能吃下，缺字段时不能把整条丢掉。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class ModuleStoreTest {
    @Test
    fun `解析完整的模块条目`() {
        val json =
            """
            [
              {
                "id": "zygisk_lsposed",
                "name": "LSPosed",
                "version": "v1.9.3",
                "author": "LSPosed Developers",
                "description": "ART hook framework",
                "enabled": true,
                "remove": false,
                "metamodule": false,
                "action": true,
                "web": true,
                "update": true
              }
            ]
            """.trimIndent()

        val list = ModuleStore.parse(json)

        assertEquals(1, list.size)
        val module = list.single()
        assertEquals("zygisk_lsposed", module.id)
        assertEquals("LSPosed", module.name)
        assertEquals("v1.9.3", module.version)
        assertEquals("LSPosed Developers", module.author)
        assertEquals("ART hook framework", module.description)
        assertTrue(module.enabled)
        assertFalse(module.remove)
        assertFalse(module.metaModule)
        assertTrue(module.hasAction)
        assertTrue(module.hasWebUi)
        assertTrue(module.hasUpdate)
    }

    @Test
    fun `字符串形式的布尔也能识别`() {
        val json = """[{"id":"m","name":"M","enabled":"true","remove":"false","metamodule":"true"}]"""

        val module = ModuleStore.parse(json).single()

        assertTrue(module.enabled)
        assertFalse(module.remove)
        assertTrue(module.metaModule)
    }

    @Test
    fun `缺字段时用 id 兜底并忽略空 id`() {
        val json = """[{"id":"only_id"},{"name":"没有 id"}]"""

        val list = ModuleStore.parse(json)

        assertEquals(1, list.size)
        assertEquals("only_id", list.single().name)
        assertFalse(list.single().hasWebUi)
        assertEquals("", list.single().version)
    }

    @Test
    fun `hasWebUi 兼容 web 与 hasWebUi 两种键`() {
        val json = """[{"id":"a","hasWebUi":true},{"id":"b","web":true}]"""

        val list = ModuleStore.parse(json)

        assertTrue(list[0].hasWebUi)
        assertTrue(list[1].hasWebUi)
    }
}
