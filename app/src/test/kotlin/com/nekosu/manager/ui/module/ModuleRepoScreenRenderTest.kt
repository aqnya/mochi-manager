package com.nekosu.manager.ui.module

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nekosu.manager.data.AppearanceSettings
import com.nekosu.manager.data.GlassStyle
import com.nekosu.manager.data.registry.ModuleRepoSource
import com.nekosu.manager.data.registry.RepoAuthor
import com.nekosu.manager.data.registry.RepoCatalog
import com.nekosu.manager.data.registry.RepoModule
import com.nekosu.manager.data.registry.RepoModuleDetail
import com.nekosu.manager.data.registry.RepoRelease
import com.nekosu.manager.ui.theme.NekoSUTheme
import java.io.File
import java.io.IOException
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 模块仓库的无头渲染截图：网络用假数据源替换，只验证版式（列表、搜索框、排序菜单、详情）。
 * 产物在 app/build/screens 下，供人工核对。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [31], qualifiers = "w411dp-h914dp-xxhdpi")
class ModuleRepoScreenRenderTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val outDir = File("build/screens").apply { mkdirs() }

    @Before
    fun resetAppearance() {
        AppearanceSettings.get(context).apply {
            updateGlass(false)
            updateGlassStyle(GlassStyle.Regular)
        }
    }

    private fun module(
        id: String,
        name: String,
        summary: String,
        authors: List<String>,
        tags: List<String> = emptyList(),
        stars: Int? = null,
        version: String = "1.0.0",
        epoch: Long = 1_794_280_035_000L,
    ) = RepoModule(
        id = id,
        name = name,
        summary = summary,
        authors = authors.map { RepoAuthor(it, null) },
        tags = tags,
        metamodule = false,
        isScript = false,
        iconUrl = null,
        version = version,
        versionCode = 1,
        publishedAt = "2026-11-10T03:07:15Z",
        releaseEpochMillis = epoch,
        stars = stars,
        starsRepository = null,
        detailPath = "api/v1/modules/$id.json",
    )

    private val aSteps =
        module(
            "A-STEPS",
            "A-STEPS",
            "记录每日步数，并把这些数据提供给其他应用读取。",
            listOf("linxueyuan"),
            tags = listOf("tools", "功能"),
            stars = 42,
            version = "1.2.0",
        )
    private val modules =
        listOf(
            aSteps,
            module(
                "bindhosts",
                "bindhosts",
                "基于 systemless hosts 的广告拦截，支持热更新订阅。",
                listOf("symbuzzer"),
                tags = listOf("network"),
                stars = 128,
                version = "2.0.3",
                epoch = 1_794_270_000_000L,
            ),
            module(
                "tricky-store",
                "Tricky Store",
                "在保持设备完整性的同时伪造 keybox。",
                listOf("5ec1cff"),
                stars = 96,
                version = "1.2.1",
                epoch = 1_794_200_000_000L,
            ),
            module(
                "susfs4ksu",
                "susfs4ksu",
                "SUSFS 的内核补丁，配合 KernelSU 隐藏 root 痕迹。",
                listOf("sidex15", "gitorup"),
                tags = listOf("kernel"),
                stars = 64,
                epoch = 1_794_100_000_000L,
            ),
            module(
                "meta-hymod",
                "海星模拟器",
                "不改包名的机型伪装，针对部分游戏与银行 App。",
                listOf("海星实验室"),
                stars = null,
                epoch = 1_794_000_000_000L,
            ),
        )

    private inner class FakeSource : ModuleRepoSource {
        override suspend fun catalog(): RepoCatalog = RepoCatalog(modules = modules, buildId = "b1")

        override suspend fun detail(module: RepoModule): RepoModuleDetail =
            RepoModuleDetail(
                module = module,
                license = "GPL-3.0",
                homepage = "https://github.com/linxueyuan/A-STEPS",
                support = "https://t.me/asteps",
                sourceName = "linxueyuan/A-STEPS",
                sourceUrl = "https://github.com/linxueyuan/A-STEPS",
                sourceCommit = "4de9c03",
                webUrl = "https://modules.kernelsu.org/zh-CN/modules/A-STEPS/",
                releases =
                    listOf(
                        RepoRelease(
                            tag = "v1.2.0",
                            name = "v1.2.0",
                            version = "1.2.0",
                            versionCode = 12,
                            prerelease = false,
                            publishedAt = "2026-11-10T03:07:15Z",
                            changelog =
                                "修复\n\n- 修复息屏后步数不再增加的问题\n- 新增按小时聚合的接口\n- 适配 HyperOS 3 的后台限制",
                            zipUrl = "https://github.com/linxueyuan/A-STEPS/releases/download/v1.2.0/a.zip",
                            zipName = "A-STEPS-1.2.0.zip",
                            zipSize = 1_887_436L,
                            sha256 = "a".repeat(64),
                            releaseUrl = "https://github.com/linxueyuan/A-STEPS/releases/tag/v1.2.0",
                            immutable = true,
                        ),
                    ),
            )
    }

    /** 加载失败：验证失败页的图标、说明与「重试」按钮没有挤在一起。 */
    private inner class FailingSource : ModuleRepoSource {
        override suspend fun catalog(): RepoCatalog = throw IOException("HTTP 503")

        override suspend fun detail(module: RepoModule): RepoModuleDetail = throw IOException("HTTP 503")
    }

    private fun render(
        name: String,
        content: @Composable () -> Unit,
    ) {
        compose.setContent { NekoSUTheme { content() } }
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
    fun repoList() = render("repo-list") {
        ModuleRepoScreen(onBack = {}, onOpenModule = {}, source = FakeSource())
    }

    @Test
    fun repoError() = render("repo-error") {
        ModuleRepoScreen(onBack = {}, onOpenModule = {}, source = FailingSource())
    }

    @Test
    fun repoDetail() = render("repo-detail") {
        ModuleRepoDetailScreen(module = aSteps, onBack = {}, source = FakeSource())
    }
}
