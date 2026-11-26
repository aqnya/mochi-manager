package com.nekosu.manager.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nekosu.manager.data.InstalledApp
import com.nekosu.manager.data.SuProfile
import com.nekosu.manager.data.SuProfileStore
import com.nekosu.manager.ui.home.HomeScreen
import com.nekosu.manager.ui.module.ModuleScreen
import com.nekosu.manager.ui.profile.AppProfileScreen
import com.nekosu.manager.ui.settings.SettingsScreen
import com.nekosu.manager.ui.superuser.SuperUserScreen
import com.nekosu.manager.ui.theme.NekoSUTheme
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 无头渲染冒烟测试：把四个页面分别组合进宿主 Activity，直接 draw 到 Bitmap 落盘，
 * 产物在 app/build/screens 下的 png，用于人工核对版式。
 *
 * 超级用户页读的是真实 PackageManager，这里先注入几个假包，让列表有内容可看。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w411dp-h914dp-xxhdpi")
class ScreenRenderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val outDir = File("build/screens").apply { mkdirs() }

    @Before
    fun seedPackages() {
        val pm = context.packageManager
        listOf(
            Triple("微信", "com.tencent.mm", 10_123),
            Triple("支付宝", "com.eg.android.AlipayGphone", 10_234),
            Triple("Termux", "com.termux", 10_345),
            Triple("Chrome", "com.android.chrome", 10_456),
            Triple("文件管理", "com.google.android.documentsui", 10_567),
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
                            flags = if (pkg.startsWith("com.android.")) ApplicationInfo.FLAG_SYSTEM else 0
                        }
                }
            shadowOf(pm).installPackage(info)
        }
        SuProfileStore.init(context)
        // 一个已授权、一个自定义配置（改了 uid），用于验证 ROOT / CUSTOM 标签与分组
        SuProfileStore.save(context, SuProfile("com.tencent.mm", allowSu = true))
        SuProfileStore.save(context, SuProfile("com.termux", allowSu = true, uid = 2000, gid = 2000))
    }

    private fun render(
        name: String,
        content: @Composable () -> Unit,
    ) {
        compose.setContent { NekoSUTheme { content() } }
        // 真实应用列表在 IO 线程加载，等一会儿再截
        repeat(10) {
            compose.waitForIdle()
            Thread.sleep(50)
        }
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
    fun profile() = render("profile") {
        AppProfileScreen(
            app = InstalledApp("com.termux", "Termux", 10_345, 0, false, true, 0L, true),
            onBack = {},
        )
    }

    @Test
    fun modules() = render("modules") { ModuleScreen() }

    @Test
    fun settings() = render("settings") { SettingsScreen() }
}
