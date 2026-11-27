package com.nekosu.manager.ui.module

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nekosu.manager.R
import com.nekosu.manager.data.AppearanceSettings
import com.nekosu.manager.data.GlassStyle
import com.nekosu.manager.data.ModuleEntry
import com.nekosu.manager.ui.theme.NekoSUTheme
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 已安装模块页的无头渲染。
 *
 * 重点回归「加载完成后列表不刷新」：`modules` 是 SnapshotStateList，早先的
 * `remember(query, …)` 把首次组合的空列表缓存住了，数据到了界面仍停在空态。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w411dp-h914dp-xxhdpi")
class ModuleScreenRenderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File("build/screens").apply { mkdirs() }

    @Before
    fun resetAppearance() {
        AppearanceSettings.get(ApplicationProvider.getApplicationContext()).apply {
            updateGlass(false)
            updateGlassStyle(GlassStyle.Regular)
        }
    }

    private fun snap(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(outDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun module(
        id: String,
        name: String,
        description: String,
        enabled: Boolean = true,
        action: Boolean = false,
    ) = ModuleEntry(
        id = id,
        name = name,
        version = "v1.0.0",
        author = "NekoSU",
        description = description,
        enabled = enabled,
        hasAction = action,
    )

    @Test
    fun `加载完成后显示模块卡片`() {
        compose.setContent {
            NekoSUTheme {
                ModuleScreen(
                    loader = {
                        listOf(
                            module("lsposed", "LSPosed", "ART hook framework，作用于 zygote", action = true),
                            module("busybox", "BusyBox", "常用命令行工具集", enabled = false),
                        )
                    },
                )
            }
        }

        compose.onNodeWithText("LSPosed").assertExists()
        compose.onNodeWithText("ART hook framework，作用于 zygote").assertExists()
        compose.onNodeWithText("BusyBox").assertExists()
        // 原来的缓存 bug 会一直停在这里
        compose
            .onNodeWithText(ApplicationProvider.getApplicationContext<android.content.Context>().getString(R.string.module_empty))
            .assertDoesNotExist()

        snap("module-list")
    }

    @Test
    fun `读取失败时给出失败提示而不是空模块`() {
        compose.setContent {
            NekoSUTheme { ModuleScreen(loader = { null }) }
        }

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onNodeWithText(context.getString(R.string.module_load_failed)).assertExists()
        compose.onNodeWithText(context.getString(R.string.module_empty)).assertDoesNotExist()

        snap("module-load-failed")
    }
}
