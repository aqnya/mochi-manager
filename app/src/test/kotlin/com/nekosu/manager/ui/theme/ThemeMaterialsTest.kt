package com.nekosu.manager.ui.theme

import com.nekosu.manager.data.GlassStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ui.feice.mochi.theme.MochiGlassStyle

/** 玻璃开关必须真的换材质：关 = 不透明，开 = 带玻璃的那一套；样式要真的换到对应的玻璃上。 */
class ThemeMaterialsTest {
    @Test
    fun `glass off uses opaque materials`() {
        assertFalse(mochiMaterialsFor(false).anyGlass)
    }

    @Test
    fun `glass on uses glass materials`() {
        assertTrue(mochiMaterialsFor(true).anyGlass)
    }

    @Test
    fun `glass style maps to library glass`() {
        // 标准 / 厚实分别是库里的 MochiGlassDefaults.Regular / Thick，接错就白设了
        assertEquals(MochiGlassStyle.Regular, mochiMaterialsFor(true, GlassStyle.Regular).bar?.style)
        assertEquals(MochiGlassStyle.Thick, mochiMaterialsFor(true, GlassStyle.Thick).bar?.style)
        // 关闭时样式不该残留玻璃
        assertFalse(mochiMaterialsFor(false, GlassStyle.Thick).anyGlass)
    }
}
