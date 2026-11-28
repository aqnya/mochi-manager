package com.nekosu.manager.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nekosu.manager.data.AppearanceSettings
import com.nekosu.manager.data.GlassStyle
import com.nekosu.manager.data.InstalledApp
import com.nekosu.manager.data.groupApps
import com.nekosu.manager.data.SuProfile
import com.nekosu.manager.data.SuProfileStore
import com.nekosu.manager.ui.home.HomeScreen
import com.nekosu.manager.ui.module.ModuleScreen
import com.nekosu.manager.ui.profile.AppProfileScreen
import com.nekosu.manager.ui.settings.AboutScreen
import com.nekosu.manager.ui.settings.OpenSourceScreen
import com.nekosu.manager.ui.settings.SettingsScreen
import com.nekosu.manager.ui.settings.ThemeScreen
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

    private data class Quad(
        val label: String,
        val pkg: String,
        val uid: Int,
        val sharedUserId: String? = null,
    )
    private val outDir = File("build/screens").apply { mkdirs() }

    @Before
    fun seedPackages() {
        // 截图固定用不透明材质 + 标准玻璃，避免上一个用例的设置影响这一张
        AppearanceSettings.get(context).updateGlass(false)
        AppearanceSettings.get(context).updateGlassStyle(GlassStyle.Regular)
        val pm = context.packageManager
        // 后两个包共享 uid（模拟小米服务套件），列表里应合成一行
        listOf(
            Quad("微信", "com.tencent.mm", 10_123),
            Quad("支付宝", "com.eg.android.AlipayGphone", 10_234),
            Quad("Termux", "com.termux", 10_345),
            Quad("Chrome", "com.android.chrome", 10_456),
            Quad("文件管理", "com.google.android.documentsui", 10_567),
            Quad("小米服务框架", "com.xiaomi.xmsf", 10_600, "com.xiaomi.shared"),
            Quad("小米云服务", "com.miui.cloudservice", 10_600, "com.xiaomi.shared"),
        ).forEachIndexed { index, (label, pkg, uid, sharedUserId) ->
            val info =
                PackageInfo().apply {
                    packageName = pkg
                    this.sharedUserId = sharedUserId
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
        SuProfileStore.save(context, SuProfile("com.tencent.mm", 10_123, allowSu = true))
        SuProfileStore.save(
            context,
            SuProfile("com.termux", 10_345, allowSu = true, runAsUid = 2000, runAsGid = 2000),
        )
    }

    private fun groupOf(vararg apps: InstalledApp) = groupApps(apps.toList()).single()

    private fun render(
        name: String,
        scrollTo: String? = null,
        content: @Composable () -> Unit,
    ) {
        compose.setContent { NekoSUTheme { content() } }
        if (scrollTo != null) {
            compose.waitForIdle()
            runCatching {
                compose.onNode(hasScrollAction()).performScrollToNode(hasText(scrollTo))
            }
        }
        // 真实应用列表在 IO 线程加载，等一会儿再截
        repeat(10) {
            compose.waitForIdle()
            Thread.sleep(50)
        }
        snap(name)
    }

    private fun snap(name: String) {
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
    fun homeRebootMenu() {
        render("home-reboot-menu") { HomeScreen(onOpenTab = {}) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("重启").performClick()
        compose.waitForIdle()
        // Robolectric 的 PowerManager 不支持用户空间重启，菜单里不应出现这一项
        compose.onNodeWithText("重启到 Recovery").assertExists()
        compose.onNodeWithText("重启到 Bootloader").assertExists()
        compose.onNodeWithText("重启到 EDL 模式").assertExists()
        compose.onNodeWithText("用户空间重启").assertDoesNotExist()
        // mochi 菜单是挂在 DecorView 上的浮层，直接画整棵 view 树才能拿到展开态
        snap("home-reboot-menu")
    }

    @Test
    fun superUser() = render("superuser") { SuperUserScreen(onOpenApp = {}) }

    @Test
    fun profile() = render("profile") {
        AppProfileScreen(
            // 用共享 uid 的一组应用做截图：验证组员列表、共享说明与按 uid 的配置
            group =
                groupOf(
                    InstalledApp("com.xiaomi.xmsf", "小米服务框架", 10_600, 0, false, true, 0L, true, "com.xiaomi.shared", "小米服务"),
                    InstalledApp("com.miui.cloudservice", "小米云服务", 10_600, 0, false, true, 1L, true, "com.xiaomi.shared", "小米服务"),
                ),
            onBack = {},
        )
    }

    @Test
    fun modules() = render("modules") { ModuleScreen() }

    @Test
    fun settings() = render("settings") { SettingsScreen(onOpenTheme = {}, onOpenAbout = {}) }

    @Test
    fun theme() = render("theme") { ThemeScreen(onBack = {}) }

    @Test
    fun about() = render("about") { AboutScreen(onBack = {}, onOpenOpenSource = {}) }

    @Test
    fun openSource() = render("open-source") { OpenSourceScreen(onBack = {}) }

    /** 「保存日志 / 发送日志」在设置页底部，滚动过去单独截一张。 */
    @Test
    fun settingsLog() = render("settings-log", scrollTo = "保存日志") {
        SettingsScreen(onOpenTheme = {}, onOpenAbout = {})
    }

    @Test
    fun glass() {
        AppearanceSettings.get(context).updateGlass(true)
        try {
            render("glass") { SuperUserScreen(onOpenApp = {}) }
        } finally {
            AppearanceSettings.get(context).updateGlass(false)
        }
    }

    @Test
    fun themeGlass() {
        // 玻璃开着时主题页会多出「玻璃样式」选择，单独截一张核对版式
        val settings = AppearanceSettings.get(context)
        settings.updateGlass(true)
        settings.updateGlassStyle(GlassStyle.Thick)
        try {
            render("theme-glass") { ThemeScreen(onBack = {}) }
        } finally {
            settings.updateGlass(false)
            settings.updateGlassStyle(GlassStyle.Regular)
        }
    }
}
