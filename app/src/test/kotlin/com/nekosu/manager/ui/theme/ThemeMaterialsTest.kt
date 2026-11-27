package com.nekosu.manager.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 玻璃开关必须真的换材质：关 = 不透明，开 = 带玻璃的那一套。 */
class ThemeMaterialsTest {
    @Test
    fun `glass off uses opaque materials`() {
        assertFalse(mochiMaterialsFor(false).anyGlass)
    }

    @Test
    fun `glass on uses glass materials`() {
        assertTrue(mochiMaterialsFor(true).anyGlass)
    }
}
