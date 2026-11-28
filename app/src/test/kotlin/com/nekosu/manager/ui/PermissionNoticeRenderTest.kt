package com.nekosu.manager.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nekosu.manager.ui.superuser.SuperUserScreen
import com.nekosu.manager.ui.theme.NekoSUTheme
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 「读取应用列表」被限制时的界面：不注入任何第三方包，超级用户页应显示引导卡而不是空白。
 * 截图落在 app/build/screens/superuser_no_permission.png。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [31], qualifiers = "w411dp-h914dp-xxhdpi")
class PermissionNoticeRenderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun showsPermissionGuideWhenListRestricted() {
        compose.setContent { NekoSUTheme { SuperUserScreen(onOpenApp = {}) } }
        repeat(12) {
            compose.waitForIdle()
            Thread.sleep(50)
        }
        compose.waitForIdle()

        val cards = compose.onAllNodesWithText("允许读取应用列表").fetchSemanticsNodes()
        assertTrue("列表读不到第三方应用时应显示授权引导卡", cards.isNotEmpty())

        val view = compose.activity.window.decorView
        if (view.width <= 0 || view.height <= 0) return
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val out = File("build/screens").apply { mkdirs() }
        File(out, "superuser_no_permission.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
