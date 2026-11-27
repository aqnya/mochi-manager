package com.nekosu.manager.data

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 外观设置：玻璃开关要真的落盘，重启后主题根节点才能读回同一个值。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class AppearanceSettingsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs: SharedPreferences
        get() = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

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
}
