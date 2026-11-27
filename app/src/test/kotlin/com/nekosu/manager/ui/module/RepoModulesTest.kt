package com.nekosu.manager.ui.module

import com.nekosu.manager.data.registry.RepoCatalog
import com.nekosu.manager.data.registry.RepoModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仓库列表的搜索与排序。排序只用索引里已有的字段，不额外请求详情：
 * 更新时间按 stable 的发布时间倒序，名称按中文 Collator，星标数未知的排在已知之后。
 */
class RepoModulesTest {
    private fun module(
        id: String,
        name: String = id,
        summary: String = "",
        tags: List<String> = emptyList(),
        stars: Int? = null,
        epoch: Long = 0L,
    ) = RepoModule(
        id = id,
        name = name,
        summary = summary,
        authors = emptyList(),
        tags = tags,
        metamodule = false,
        isScript = false,
        iconUrl = null,
        version = "1.0.0",
        versionCode = 1,
        publishedAt = "",
        releaseEpochMillis = epoch,
        stars = stars,
        starsRepository = null,
        detailPath = "api/v1/modules/$id.json",
    )

    private val catalog =
        RepoCatalog(
            modules =
                listOf(
                    module("b-mod", name = "绑核", summary = "调度绑定", tags = listOf("性能"), stars = 3, epoch = 300),
                    module("a-mod", name = "A 模块", summary = "Steps counter", stars = null, epoch = 500),
                    module("c-mod", name = "存储", summary = "挂载", stars = 10, epoch = 100),
                ),
            buildId = "b1",
        )

    @Test
    fun `updated sorts newest first`() {
        val ids = repoModules(catalog, "", RepoSort.UPDATED).map { it.id }
        assertEquals(listOf("a-mod", "b-mod", "c-mod"), ids)
    }

    @Test
    fun `stars puts unknown last`() {
        val ids = repoModules(catalog, "", RepoSort.STARS).map { it.id }
        // 3 星、10 星在前，未知星的 a-mod 垫底
        assertEquals(listOf("c-mod", "b-mod", "a-mod"), ids)
    }

    @Test
    fun `name uses chinese collator`() {
        val ids = repoModules(catalog, "", RepoSort.NAME).map { it.id }
        // 中文按拼音排：A 模块 → 绑核 → 存储
        assertEquals(listOf("a-mod", "b-mod", "c-mod"), ids)
    }

    @Test
    fun `search matches id summary and tags ignoring case`() {
        assertEquals(listOf("a-mod"), repoModules(catalog, "steps", RepoSort.UPDATED).map { it.id })
        assertEquals(listOf("c-mod"), repoModules(catalog, "  挂载 ", RepoSort.UPDATED).map { it.id })
        assertEquals(listOf("b-mod"), repoModules(catalog, "性能", RepoSort.UPDATED).map { it.id })
        assertTrue(repoModules(catalog, "没有这个词", RepoSort.UPDATED).isEmpty())
    }

    @Test
    fun `blank query keeps everything and null catalog is empty`() {
        assertEquals(3, repoModules(catalog, "   ", RepoSort.UPDATED).size)
        assertTrue(repoModules(null, "", RepoSort.UPDATED).isEmpty())
    }
}
