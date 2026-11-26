package com.nekosu.manager.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nekosu.manager.ui.home.HomeScreen
import com.nekosu.manager.ui.module.ModuleScreen
import com.nekosu.manager.ui.settings.SettingsScreen
import com.nekosu.manager.ui.superuser.SuperUserScreen
import com.nekosu.manager.ui.theme.NekoSUTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 无头渲染冒烟测试：把四个页面分别组合进宿主 Activity，直接 draw 到 Bitmap 落盘，
 * 产物在 app/build/screens 下的 png 文件，用于人工核对版式。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi")
class ScreenRenderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File("build/screens").apply { mkdirs() }

    private fun render(name: String, content: @Composable () -> Unit) {
        compose.setContent { NekoSUTheme { content() } }
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        if (view.width <= 0 || view.height <= 0) return
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(outDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test
    fun home() = render("home") { HomeScreen(onOpenTab = {}) }

    @Test
    fun superUser() = render("superuser") { SuperUserScreen() }

    @Test
    fun modules() = render("modules") { ModuleScreen() }

    @Test
    fun settings() = render("settings") { SettingsScreen() }
}
