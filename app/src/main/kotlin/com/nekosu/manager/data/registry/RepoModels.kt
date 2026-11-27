package com.nekosu.manager.data.registry

/**
 * 模块仓库（KernelSU Modules）的数据模型，字段对齐 `modules.kernelsu.org` 的 v1 协议。
 *
 * 索引里的每条只带摘要（[RepoModule]），详情（[RepoModuleDetail]）按需再拉一次，
 * 这样列表页不用为 60 多个模块下载 60 份 JSON + README。
 */
data class RepoAuthor(
    val name: String,
    val url: String?,
)

/** 索引里的一条模块。 */
data class RepoModule(
    val id: String,
    val name: String,
    val summary: String,
    val authors: List<RepoAuthor>,
    val tags: List<String>,
    val metamodule: Boolean,
    /** `kind: script` 的一次性脚本：只能到网页上运行，不参与安装。 */
    val isScript: Boolean,
    /** 已经拼成绝对地址的图标，可能为 null。 */
    val iconUrl: String?,
    val version: String,
    val versionCode: Int,
    /** 稳定版发布时间（原始 ISO 8601），用于展示。 */
    val publishedAt: String,
    /** 稳定版发布时间（epoch millis），用于排序；解析失败为 0。 */
    val releaseEpochMillis: Long,
    val stars: Int?,
    val starsRepository: String?,
    /** 详情文档的相对路径，例如 `api/v1/modules/A-STEPS.json`。 */
    val detailPath: String,
) {
    val authorText: String
        get() = authors.joinToString(", ") { it.name }.ifBlank { "—" }

    /** 搜索用的合并文本，拼音不展开（仓库里的名称以中英文混排为主，直接 contains 足够）。 */
    fun matches(query: String): Boolean =
        id.contains(query, true) ||
            name.contains(query, true) ||
            summary.contains(query, true) ||
            authorText.contains(query, true) ||
            tags.any { it.contains(query, true) }
}

/** 一个稳定的（非预览）Release，ZIP 下载后按大小与 SHA-256 校验。 */
data class RepoRelease(
    val tag: String,
    val name: String,
    val version: String,
    val versionCode: Int,
    val prerelease: Boolean,
    val publishedAt: String,
    /** 已经把 HTML 标签去掉的更新说明，可能为空。 */
    val changelog: String?,
    val zipUrl: String,
    val zipName: String,
    val zipSize: Long,
    val sha256: String,
    val releaseUrl: String,
    val immutable: Boolean,
)

/** 模块详情。 */
data class RepoModuleDetail(
    val module: RepoModule,
    val license: String?,
    val homepage: String?,
    val support: String?,
    val sourceName: String?,
    val sourceUrl: String?,
    val sourceCommit: String?,
    /** 网页版详情页，按界面语言选择 zh-CN / en。 */
    val webUrl: String,
    /** 新版本在前。 */
    val releases: List<RepoRelease>,
    /** 这份详情来自磁盘缓存（网络失败时）。 */
    val fromCache: Boolean = false,
) {
    val stableRelease: RepoRelease?
        get() = releases.firstOrNull { !it.prerelease } ?: releases.firstOrNull()
}

/** 一次索引加载的结果。[stale] 表示网络失败、用的是上一次的完整副本。 */
data class RepoCatalog(
    val modules: List<RepoModule>,
    val buildId: String,
    val stale: Boolean = false,
    val error: Throwable? = null,
)

/** 仓库文档的结构/版本不合法。 */
open class RegistryFormatException(
    message: String,
) : Exception(message)

/** 仓库用了客户端不认识的 schemaVersion（通常是站点升级了协议）。 */
class UnsupportedSchemaException(
    val schemaVersion: Int,
) : RegistryFormatException("Unsupported registry schema version: $schemaVersion")

/** 下载到的 ZIP 与 Release 记录的大小 / SHA-256 不一致。 */
class RegistryIntegrityException(
    message: String,
) : Exception(message)
