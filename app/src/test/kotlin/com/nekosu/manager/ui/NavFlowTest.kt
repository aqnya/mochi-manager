package com.nekosu.manager.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nekosu.manager.ui.superuser.SuperUserFlow
import com.nekosu.manager.ui.theme.NekoSUTheme
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 二级页面导航测试：点击应用行 → MochiNavHost 侧推进入配置页 → 系统返回 → 回到列表。
 *
 * 这里验证的是导航栈和返回事件的分发，不校验动画帧本身（转场由库的 graphicsLayer 完成，
 * 无头环境只保证状态正确、不会直接退出 Activity）。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w411dp-h914dp-xxhdpi")
class NavFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()

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

    @Test
    fun openProfileThenBackReturnsToList() {
        compose.setContent { NekoSUTheme { SuperUserFlow() } }
        settle()

        // 列表加载完成：能看到应用行
        val rows = compose.onAllNodesWithText("Termux")
        assertTrue("列表里应出现 Termux", rows.fetchSemanticsNodes().isNotEmpty())
        rows[0].performClick()
        settle()

        // 侧推完成后进入配置页（列表页仍在栈底，但屏幕上以配置页为准）
        assertTrue(
            "点击后应进入应用配置页",
            compose.onAllNodesWithText("允许超级用户").fetchSemanticsNodes().isNotEmpty(),
        )

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
