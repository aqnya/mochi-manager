package com.nekosu.manager.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 玻璃样式，对应 mochi 的 [ui.feice.mochi.theme.MochiGlassStyle]。
 *
 * mochi 的 0.3.0 只公开了 [Regular] 和 [Thick] 两种：前者更透、更接近系统控制中心，
 * 后者磨砂更实、深色壁纸下文字更清楚。demo 里的「毛玻璃」(Frosted) 还没进正式版本，
 * 等库发版再加。
 */
enum class GlassStyle {
    Regular,
    Thick,
    ;

    companion object {
        fun from(raw: String?): GlassStyle = entries.firstOrNull { it.name == raw } ?: Regular
    }
}

/**
 * 外观设置。
 *
 * 两项都和玻璃有关：总开关 [glass]，开启后 tab bar、顶栏按钮、菜单、弹窗和列表分组
 * 改用 Mochi 的玻璃材质（实时采样背后的页面）；[glassStyle] 决定用哪一种玻璃。
 * 关闭时用不透明材质，样式留到下次开启继续生效。
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

    /** 玻璃样式；[glass] 关闭时不影响界面，但会保留用户的选择。 */
    var glassStyle by mutableStateOf(GlassStyle.from(prefs.getString(KEY_GLASS_STYLE, null)))
        private set

    fun updateGlass(value: Boolean) {
        glass = value
        prefs.edit().putBoolean(KEY_GLASS, value).apply()
    }

    fun updateGlassStyle(value: GlassStyle) {
        glassStyle = value
        prefs.edit().putString(KEY_GLASS_STYLE, value.name).apply()
    }

    companion object {
        private const val PREFS = "settings"
        private const val KEY_GLASS = "mochi_glass"
        private const val KEY_GLASS_STYLE = "mochi_glass_style"

        @Volatile
        private var instance: AppearanceSettings? = null

        fun get(context: Context): AppearanceSettings =
            instance ?: synchronized(this) {
                instance ?: AppearanceSettings(
                    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
                ).also { instance = it }
            }

        /**
         * 丢掉缓存实例，下一次 [get] 会重新绑定当前 Context 的偏好。
         * 单测里 Robolectric 每条用例换一个 Application，静态缓存不重置就会写到上一条用例的偏好里。
         */
        internal fun resetForTests() {
            instance = null
        }
    }
}
