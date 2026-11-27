package com.nekosu.manager.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nekosu.manager.ui.theme.NekoSUTheme
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 二级页面导航测试：标签页 → 点应用行 → 根导航侧推进入配置页 → 系统返回 → 回到列表。
 *
 * 这里验证的是导航栈、返回分发，以及「二级页里没有底部标签栏」这条约定：
 * 截图 `nav-secondary.png` 用来人工核对，标签栏此时跟着下层页一起被新页盖住。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w411dp-h914dp-xxhdpi")
class NavFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val outDir = File("build/screens").apply { mkdirs() }

    @Before
    fun seedPackages() {
        val pm = context.packageManager
        listOf(
            Triple("微信", "com.tencent.mm", 10_123),
            Triple("Termux", "com.termux", 10_345),
        ).forEachIndexed { index, (label, pkg, uid) ->
            val info =
                PackageInfo().apply {
                    packageName = pkg
                    firstInstallTime = 1_700_000_000_000L + index
                    applicationInfo =
                        ApplicationInfo().apply {
                            packageName = pkg
                            nonLocalizedLabel = label
                            this.uid = uid
                            flags = 0
                        }
                }
            shadowOf(pm).installPackage(info)
        }
    }

    private fun settle() {
        repeat(12) {
            compose.waitForIdle()
            Thread.sleep(50)
        }
        compose.waitForIdle()
    }

    private fun snap(name: String) {
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
    fun openProfileThenBackReturnsToList() {
        compose.setContent { NekoSUTheme { MochiRoot() } }
        settle()

        // 切到「超级用户」标签：标签格子的语义就是它的名字
        compose.onNodeWithContentDescription("超级用户").performClick()
        settle()

        val rows = compose.onAllNodesWithText("Termux")
        assertTrue("列表里应出现 Termux", rows.fetchSemanticsNodes().isNotEmpty())
        // 根页（标签页）里的标签栏仍然在，和二级页形成对照
        snap("nav-tabs")
        rows[0].performClick()
        settle()

        // 侧推完成后进入配置页（标签骨架那页仍在栈底，但屏幕上以配置页为准）
        assertTrue(
            "点击后应进入应用配置页",
            compose.onAllNodesWithText("允许超级用户").fetchSemanticsNodes().isNotEmpty(),
        )
        snap("nav-secondary")

        // 系统返回（侧滑/返回键最终都走 OnBackPressedDispatcher）
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settle()

        assertTrue(
            "返回后应回到超级用户列表，而不是退出应用",
            compose.onAllNodesWithText("允许超级用户").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("Termux").fetchSemanticsNodes().isNotEmpty(),
        )
    }
}
