package com.nekosu.manager.ui.module

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nekosu.manager.R
import com.nekosu.manager.data.AppearanceSettings
import com.nekosu.manager.data.GlassStyle
import com.nekosu.manager.data.ModuleInstallSession
import com.nekosu.manager.data.StagedModule
import com.nekosu.manager.data.registry.InstallOutcome
import com.nekosu.manager.ui.theme.NekoSUTheme
import java.io.File
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 安装输出页的无头渲染。
 *
 * 回归点：安装脚本的提示要实时出现（而不是装完才一次性刷出来），
 * 出现「按音量键」这类提示时输入区要展开，结束后按钮从「取消」变成「完成」。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w411dp-h914dp-xxhdpi")
class ModuleInstallScreenRenderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File("build/screens").apply { mkdirs() }
    private val original = ModuleInstallSession.installer

    @Before
    fun resetAppearance() {
        AppearanceSettings.get(ApplicationProvider.getApplicationContext()).apply {
            updateGlass(false)
            updateGlassStyle(GlassStyle.Regular)
        }
    }

    @After
    fun tearDown() {
        ModuleInstallSession.cancel()
        ModuleInstallSession.installer = original
        ModuleInstallSession.dismiss()
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

    private fun staged(name: String): StagedModule =
        StagedModule(File.createTempFile("neko-", ".zip").apply { deleteOnExit() }, name, 2_048_000L)

    private fun awaitText(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `安装中实时显示输出并展开输入区`() {
        ModuleInstallSession.installer = { _, _, onLine ->
            onLine("- Installing to /data/adb/modules_update/demo")
            onLine("- Module size: 1.9 MiB")
            onLine("- 按音量上键继续，按音量下键跳过")
            // 交互式脚本会停在这里等用户
            delay(30_000)
            error("测试结束前不会返回")
        }

        ModuleInstallSession.start(listOf(staged("demo-v1.2.zip")))
        compose.setContent { NekoSUTheme { ModuleInstallScreen(onExit = {}) } }

        awaitText("按音量上键继续")
        compose.onNodeWithText(compose.activity.getString(R.string.module_install_waiting)).assertExists()
        snap("module-install-running")
    }

    @Test
    fun `完成后显示结果与完成按钮`() {
        ModuleInstallSession.installer = { _, _, onLine ->
            onLine("- Installing to /data/adb/modules_update/demo")
            onLine("- Done")
            InstallOutcome.Success("ok")
        }

        ModuleInstallSession.start(listOf(staged("demo-v1.2.zip")))
        compose.setContent { NekoSUTheme { ModuleInstallScreen(onExit = {}) } }

        awaitText("安装完成")
        compose.onNodeWithText(compose.activity.getString(R.string.module_install_finish)).assertExists()
        snap("module-install-done")
    }
}
