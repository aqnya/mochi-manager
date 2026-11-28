package com.nekosu.manager.data.registry

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 仓库 v1 文档的解析规则：
 *
 * - schemaVersion 不认识就整页拒绝，不能拿半懂的数据去拼下载地址；
 * - 单条坏掉（未知 kind、detail 路径逃逸、zip 不是 GitHub Release、sha256 非法）只丢这一条；
 * - 下载地址只允许 `https://github.com/<owner>/<repo>/releases/download/...`，
 *   避免索引把管理器带到任意主机。
 *
 * Robolectric 起在 30 上是因为 `org.json` 在纯 JVM 单测里只有空壳实现。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class RegistryParserTest {
    private val base = "https://modules.kernelsu.org"

    @Test
    fun `index falls back to default modules path`() {
        val index = RegistryParser.parseIndex("""{"schemaVersion":1,"buildId":"b1"}""")
        assertEquals("b1", index.buildId)
        assertEquals("api/v1/modules.json", index.modulesPath)
    }

    @Test(expected = UnsupportedSchemaException::class)
    fun `unknown schema is rejected`() {
        RegistryParser.parseIndex("""{"schemaVersion":9,"buildId":"b1"}""")
    }

    @Test
    fun `modules keeps good entries and drops broken ones`() {
        val text =
            """
            {"schemaVersion":1,"buildId":"b1","modules":[
              {
                "id":"A-STEPS","name":"A-STEPS","summary":"步骤计数",
                "authors":[{"name":"linxueyuan","url":"https://github.com/linxueyuan"}],
                "tags":["tools","功能"],"icon":"icons/a-steps.png",
                "stable":{"version":"1.2.0","versionCode":12,"publishedAt":"2026-11-10T03:07:15Z"},
                "stars":42,"detail":"api/v1/modules/A-STEPS.json"
              },
              {"id":"script-one","kind":"script","detail":"api/v1/modules/script-one.json"},
              {"id":"weird","kind":"theme","detail":"api/v1/modules/weird.json"},
              {"id":"escape","detail":"../secrets.json"},
              {"id":"null-icon","icon":null,"summary":null,"detail":"api/v1/modules/null-icon.json"},
              {"id":"no-detail"}
            ]}
            """.trimIndent()
        val modules = RegistryParser.parseModules(text, base).modules

        // 只留下 A-STEPS、脚本条目和图标为 JSON null 的那条，三个坏条目被丢掉
        assertEquals(3, modules.size)
        val a = modules[0]
        assertEquals("A-STEPS", a.id)
        assertEquals("步骤计数", a.summary)
        assertEquals("linxueyuan", a.authors.single().name)
        assertEquals(listOf("tools", "功能"), a.tags)
        assertEquals("$base/icons/a-steps.png", a.iconUrl)
        assertEquals(12, a.versionCode)
        assertEquals(42, a.stars)
        assertFalse(a.isScript)
        assertTrue(a.releaseEpochMillis > 0L)

        // icon 是 JSON null 时不能拼成 https://modules.kernelsu.org/null
        assertNull(modules[2].iconUrl)
        assertEquals("", modules[2].summary)

        val script = modules[1]
        assertTrue(script.isScript)
        // 缺省字段用 id 兜底，星标没给就是 null（排序时排在已知之后）
        assertEquals("script-one", script.name)
        assertNull(script.stars)
        assertNull(script.iconUrl)
    }

    @Test
    fun `detail sorts releases and drops unsafe downloads`() {
        val module =
            RepoModule(
                id = "A-STEPS",
                name = "A-STEPS",
                summary = "",
                authors = emptyList(),
                tags = emptyList(),
                metamodule = false,
                isScript = false,
                iconUrl = null,
                version = "1.2.0",
                versionCode = 12,
                publishedAt = "",
                releaseEpochMillis = 0L,
                stars = null,
                starsRepository = null,
                detailPath = "api/v1/modules/A-STEPS.json",
            )
        val sha = "a".repeat(64)
        val text =
            """
            {"schemaVersion":1,"buildId":"b1",
             "license":"GPL-3.0","homepage":"http://insecure.example","support":"https://t.me/x",
             "source":{"fullName":"linxueyuan/A-STEPS","url":"https://github.com/linxueyuan/A-STEPS","commit":"abc1234"},
             "releases":[
               {"tag":"v1.0.0","version":"1.0.0","versionCode":10,"publishedAt":"2026-01-01T00:00:00Z",
                "changelogHtml":"<h3>修复</h3><ul><li>步骤统计</li></ul><script>x()</script>",
                "zip":{"url":"https://github.com/linxueyuan/A-STEPS/releases/download/v1.0.0/a.zip","name":"a.zip","size":1024,"sha256":"$sha"},
                "releaseUrl":"https://github.com/linxueyuan/A-STEPS/releases/tag/v1.0.0","immutable":true},
               {"tag":"v1.2.0","version":"1.2.0","versionCode":12,"publishedAt":"2026-11-10T03:07:15Z",
                "zip":{"url":"https://github.com/linxueyuan/A-STEPS/releases/download/v1.2.0/a.zip","name":"a.zip","size":2048,"sha256":"$sha"}},
               {"tag":"evil","zip":{"url":"https://example.com/a.zip","sha256":"$sha"}},
               {"tag":"short-sha","zip":{"url":"https://github.com/linxueyuan/A-STEPS/releases/download/x/a.zip","sha256":"abc"}}
            ]}
            """.trimIndent()
        val detail = RegistryParser.parseDetail(text, base, module, "zh-CN")

        assertEquals("GPL-3.0", detail.license)
        // 明文 http 的主页不收，免得点开被降级
        assertNull(detail.homepage)
        assertEquals("https://t.me/x", detail.support)
        assertEquals("linxueyuan/A-STEPS", detail.sourceName)
        assertEquals("abc1234", detail.sourceCommit)
        assertEquals("$base/zh-CN/modules/A-STEPS/", detail.webUrl)

        // 非 GitHub 的 zip 与非法 sha256 被丢掉，剩下的按 versionCode 从新到旧
        assertEquals(listOf("v1.2.0", "v1.0.0"), detail.releases.map { it.tag })
        assertEquals("v1.2.0", detail.stableRelease?.tag)
        assertEquals(2048L, detail.stableRelease?.zipSize)
        assertEquals(sha, detail.stableRelease?.sha256)

        // changelog 去掉标签与脚本，只留文字
        val changelog = detail.releases[1].changelog.orEmpty()
        assertTrue(changelog.contains("修复"))
        assertTrue(changelog.contains("步骤统计"))
        assertFalse(changelog.contains("<"))
        assertFalse(changelog.contains("x()"))
    }

    @Test
    fun `download urls are limited to github releases`() {
        assertTrue(
            RegistryParser.isGitHubReleaseUrl(
                "https://github.com/linxueyuan/A-STEPS/releases/download/v1.2.0/a.zip",
            ),
        )
        // 少了 releases/download 段
        assertFalse(RegistryParser.isGitHubReleaseUrl("https://github.com/linxueyuan/A-STEPS/raw/main/a.zip"))
        // 别的 host、明文 http、带 query、带用户名都不行
        assertFalse(RegistryParser.isGitHubReleaseUrl("https://example.com/a/b/releases/download/v/a.zip"))
        assertFalse(RegistryParser.isGitHubReleaseUrl("http://github.com/a/b/releases/download/v/a.zip"))
        assertFalse(
            RegistryParser.isGitHubReleaseUrl(
                "https://github.com/a/b/releases/download/v/a.zip?token=1",
            ),
        )
        assertFalse(RegistryParser.isGitHubReleaseUrl("https://user@github.com/a/b/releases/download/v/a.zip"))
    }

    @Test
    fun `epoch millis parses utc and tolerates junk`() {
        assertEquals(1794280035000L, RegistryParser.epochMillis("2026-11-10T03:07:15Z"))
        // 带毫秒与偏移时只取前 19 位（秒级精度够排序）
        assertEquals(1794280035000L, RegistryParser.epochMillis("2026-11-10T03:07:15.123Z"))
        assertEquals(0L, RegistryParser.epochMillis(""))
        assertEquals(0L, RegistryParser.epochMillis("yesterday"))
    }
}
