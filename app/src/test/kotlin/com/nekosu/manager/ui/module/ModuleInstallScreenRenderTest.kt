package com.nekosu.manager.ui.module

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * 安装输出页的无头渲染，对齐 PR 的刷写页：
 * 顶栏标题随状态走（正在安装 / 安装成功 / 安装失败），正文是整块终端输出，
 * 成功后右下角只有一个操作（重启或关闭）。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [31], qualifiers = "w411dp-h914dp-xxhdpi")
class ModuleInstallScreenRenderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File("build/screens").apply { mkdirs() }
    private val originalInstaller = ModuleInstallSession.installer
    private val originalReboot = ModuleInstallSession.rebootRequired

    @Before
    fun resetAppearance() {
        AppearanceSettings.get(ApplicationProvider.getApplicationContext()).apply {
            updateGlass(false)
            updateGlassStyle(GlassStyle.Regular)
        }
        ModuleInstallSession.rebootRequired = { true }
    }

    @After
    fun tearDown() {
        ModuleInstallSession.cancel()
        ModuleInstallSession.installer = originalInstaller
        ModuleInstallSession.rebootRequired = originalReboot
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
    fun `安装中实时输出，标题是正在安装`() {
        ModuleInstallSession.installer = { _, _, onLine ->
            onLine("- Installing to /data/adb/modules_update/demo")
            onLine("- Module size: 1.9 MiB")
            onLine("- 按音量上键继续，按音量下键跳过")
            // 交互式脚本会停在这里等物理音量键
            delay(30_000)
            error("测试结束前不会返回")
        }

        ModuleInstallSession.start(listOf(staged("demo-v1.2.zip")))
        compose.setContent { NekoSUTheme { ModuleInstallScreen(onExit = {}) } }

        awaitText(compose.activity.getString(R.string.module_install_flashing))
        // 脚本提示本身就是一行输出，不需要额外的等待条
        awaitText("按音量上键继续")
        snap("module-install-running")
    }

    @Test
    fun `成功后标题为安装成功并出现重启按钮`() {
        ModuleInstallSession.rebootRequired = { true }
        ModuleInstallSession.installer = { _, _, onLine ->
            onLine("- Installing to /data/adb/modules_update/demo")
            onLine("- Done")
            InstallOutcome.Success("ok")
        }

        ModuleInstallSession.start(listOf(staged("demo-v1.2.zip")))
        compose.setContent { NekoSUTheme { ModuleInstallScreen(onExit = {}) } }

        awaitText(compose.activity.getString(R.string.module_install_success_title))
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.module_install_reboot)).assertExists()
        snap("module-install-done")
    }

    @Test
    fun `热安装成功后只给关闭按钮`() {
        ModuleInstallSession.rebootRequired = { false }
        ModuleInstallSession.installer = { _, _, onLine ->
            onLine("- Done")
            InstallOutcome.Success("ok")
        }

        ModuleInstallSession.start(listOf(staged("hot.zip")))
        compose.setContent { NekoSUTheme { ModuleInstallScreen(onExit = {}) } }

        awaitText(compose.activity.getString(R.string.module_install_success_title))
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.module_install_close)).assertExists()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.module_install_reboot)).assertDoesNotExist()
    }

    @Test
    fun `失败后标题为安装失败且没有悬浮按钮`() {
        ModuleInstallSession.installer = { _, _, onLine ->
            onLine("! 找不到 ncore")
            InstallOutcome.Failure("exit 127")
        }

        ModuleInstallSession.start(listOf(staged("broken.zip")))
        compose.setContent { NekoSUTheme { ModuleInstallScreen(onExit = {}) } }

        awaitText(compose.activity.getString(R.string.module_install_failed_title))
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.module_install_reboot)).assertDoesNotExist()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.module_install_close)).assertDoesNotExist()
        snap("module-install-failed")
    }
}
