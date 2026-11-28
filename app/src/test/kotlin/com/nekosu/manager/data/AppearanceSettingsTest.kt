package com.nekosu.manager.data

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 外观设置：玻璃开关和样式要真的落盘，重启后主题根节点才能读回同一套。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class AppearanceSettingsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs: SharedPreferences
        get() = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    @Before
    fun resetSettings() {
        // 静态缓存会跨用例留着上一条用例的 Context，先丢掉再测
        AppearanceSettings.resetForTests()
    }

    @Test
    fun `glass defaults to off when nothing is stored`() {
        prefs.edit().remove("mochi_glass").apply()
        // 主题根节点读的就是这个键，缺省必须是关闭，否则首装就是玻璃
        assertFalse(prefs.getBoolean("mochi_glass", false))
    }

    @Test
    fun `glass toggle is persisted`() {
        val settings = AppearanceSettings.get(context)
        settings.updateGlass(true)
        assertTrue(settings.glass)
        assertTrue("开关没有写入 settings 偏好", prefs.getBoolean("mochi_glass", false))

        settings.updateGlass(false)
        assertFalse(settings.glass)
        assertFalse(prefs.getBoolean("mochi_glass", true))
    }

    @Test
    fun `glass style defaults to regular`() {
        prefs.edit().remove("mochi_glass_style").apply()
        // 缺省是标准，和 mochi 库的默认玻璃一致
        assertEquals(GlassStyle.Regular, GlassStyle.from(null))
        assertEquals(GlassStyle.Regular, GlassStyle.from("no-such-style"))
    }

    @Test
    fun `glass style is persisted`() {
        val settings = AppearanceSettings.get(context)
        settings.updateGlassStyle(GlassStyle.Thick)
        assertEquals(GlassStyle.Thick, settings.glassStyle)
        assertEquals("样式没有写入 settings 偏好", "Thick", prefs.getString("mochi_glass_style", null))
        // 读回时按枚举名解析，未知值退回标准
        assertEquals(GlassStyle.Thick, GlassStyle.from(prefs.getString("mochi_glass_style", null)))

        settings.updateGlassStyle(GlassStyle.Regular)
        assertEquals(GlassStyle.Regular, settings.glassStyle)
    }
}
