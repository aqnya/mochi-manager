package com.nekosu.manager.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 外观设置。
 *
 * 目前只有「玻璃材质」一项：开启后 tab bar、顶栏按钮、菜单、弹窗和列表分组都改用
 * Mochi 的玻璃材质（实时采样背后的页面），关闭则回到不透明材质。
 *
 * 值放在 Compose 状态里，切换后主题根节点会重组，整棵树立刻换材质；落盘用
 * `settings` 这个 SharedPreferences，和 KernelSU 的设置键位一致。
 */
class AppearanceSettings private constructor(
    private val prefs: SharedPreferences,
) {
    /** 是否使用玻璃材质。 */
    var glass by mutableStateOf(prefs.getBoolean(KEY_GLASS, false))
        private set

    fun updateGlass(value: Boolean) {
        glass = value
        prefs.edit().putBoolean(KEY_GLASS, value).apply()
    }

    companion object {
        private const val PREFS = "settings"
        private const val KEY_GLASS = "mochi_glass"

        @Volatile
        private var instance: AppearanceSettings? = null

        fun get(context: Context): AppearanceSettings =
            instance ?: synchronized(this) {
                instance ?: AppearanceSettings(
                    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
                ).also { instance = it }
            }
    }
}
