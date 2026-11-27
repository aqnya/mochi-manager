package com.nekosu.manager.data.registry

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 解析 `modules.kernelsu.org` 的 v1 文档。
 *
 * 和上游 KernelSU 的管理器一致，遇到不认识的 `schemaVersion` 直接拒绝（站点协议升级后
 * 旧客户端不能拿着半懂的数据去拼装下载地址），单个模块字段坏了则跳过该条而不是整页失败。
 */
internal object RegistryParser {
    const val SCHEMA_VERSION = 1

    private val PATH = Regex("^[A-Za-z0-9._\\-/]+$")
    private val TAG = Regex("^[^<>\\n\\r]{1,40}$")

    class Index(
        val buildId: String,
        val modulesPath: String,
    )

    /** 索引里的模块列表 + buildId。 */
    class Modules(
        val buildId: String,
        val modules: List<RepoModule>,
    )

    fun parseIndex(text: String): Index {
        val root = JSONObject(text)
        requireSchema(root)
        val modules = root.optText("modules").ifBlank { "api/v1/modules.json" }
        return Index(buildId = root.optText("buildId"), modulesPath = modules)
    }

    fun parseModules(
        text: String,
        baseUrl: String,
    ): Modules {
        val root = JSONObject(text)
        requireSchema(root)
        val array = root.optJSONArray("modules") ?: JSONArray()
        val modules = ArrayList<RepoModule>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            parseSummary(item, baseUrl)?.let(modules::add)
        }
        return Modules(buildId = root.optText("buildId"), modules = modules)
    }

    fun parseDetail(
        text: String,
        baseUrl: String,
        module: RepoModule,
        siteLocale: String,
    ): RepoModuleDetail {
        val root = JSONObject(text)
        requireSchema(root)
        val releases = root.optJSONArray("releases")?.let { array ->
            buildList {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { parseRelease(it) }?.let(::add)
                }
            }
        }.orEmpty()
        val source = root.optJSONObject("source")
        val webUrl = "${baseUrl.trimEnd('/')}/${siteLocale}/modules/${module.id}/"
        return RepoModuleDetail(
            module = module,
            license = root.optWebUrlOrText("license"),
            homepage = root.optHttps("homepage"),
            support = root.optHttps("support"),
            sourceName = source?.optText("fullName")?.takeIf { it.isNotBlank() },
            sourceUrl = source?.optText("url")?.let(::httpsUrl),
            sourceCommit = source?.optText("commit")?.takeIf { it.isNotBlank() },
            webUrl = webUrl,
            releases = releases.sortedByDescending { it.versionCode },
        )
    }

    private fun parseSummary(
        item: JSONObject,
        baseUrl: String,
    ): RepoModule? {
        val id = item.optText("id").trim()
        if (id.isEmpty()) return null
        // 未知 kind 的条目直接隐藏，避免把脚本当模块安装
        val kind = item.optString("kind", "module")
        if (kind != "module" && kind != "script") return null
        val detail = item.optText("detail").trim()
        if (!isValidPath(detail)) return null
        val stable = item.optJSONObject("stable")
        val version = stable?.optText("version").orEmpty()
        val versionCode = stable?.optInt("versionCode", 0) ?: 0
        val publishedAt = stable?.optText("publishedAt").orEmpty()
        return RepoModule(
            id = id,
            name = item.optText("name").ifBlank { id },
            summary = item.optText("summary"),
            authors = parseAuthors(item.optJSONArray("authors")),
            tags = parseTags(item.optJSONArray("tags")),
            metamodule = item.optBoolean("metamodule", false),
            isScript = kind == "script",
            iconUrl = item.optText("icon").takeIf { it.isNotBlank() }?.let { resolve(baseUrl, it) },
            version = version,
            versionCode = versionCode,
            publishedAt = publishedAt,
            releaseEpochMillis = epochMillis(publishedAt),
            stars = item.optIntOrNull("stars"),
            starsRepository = item.optText("starsRepository").takeIf { it.isNotBlank() },
            detailPath = detail,
        )
    }

    private fun parseRelease(item: JSONObject): RepoRelease? {
        val zip = item.optJSONObject("zip") ?: return null
        val url = httpsUrl(zip.optText("url")) ?: return null
        // 上游只允许 GitHub Release 的下载地址，防止索引把用户带到任意主机
        if (!isGitHubReleaseUrl(url)) return null
        val sha = zip.optText("sha256").trim().lowercase(Locale.ROOT)
        if (sha.length != 64 || sha.any { it !in "0123456789abcdef" }) return null
        return RepoRelease(
            tag = item.optText("tag"),
            name = item.optText("name").ifBlank { item.optText("tag") },
            version = item.optText("version").ifBlank { item.optText("tag") },
            versionCode = item.optInt("versionCode", 0),
            prerelease = item.optBoolean("prerelease", false),
            publishedAt = item.optText("publishedAt"),
            changelog = item.optText("changelogHtml").takeIf { it.isNotBlank() }?.let(::stripHtml)?.takeIf { it.isNotBlank() },
            zipUrl = url,
            zipName = zip.optText("name").ifBlank { "module.zip" },
            zipSize = zip.optLong("size", 0L),
            sha256 = sha,
            releaseUrl = httpsUrl(item.optText("releaseUrl")).orEmpty(),
            immutable = item.optBoolean("immutable", false),
        )
    }

    private fun parseAuthors(array: JSONArray?): List<RepoAuthor> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val author = array.optJSONObject(i) ?: continue
                val name = author.optText("name").trim()
                if (name.isNotEmpty()) add(RepoAuthor(name, author.optText("url").let(::httpsUrl)))
            }
        }
    }

    private fun parseTags(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val tag = if (array.isNull(i)) "" else array.optString(i).trim()
                if (TAG.matches(tag)) add(tag)
            }
        }
    }

    private fun requireSchema(root: JSONObject) {
        val schema = root.optInt("schemaVersion", -1)
        if (schema != SCHEMA_VERSION) throw UnsupportedSchemaException(schema)
    }

    fun isValidPath(path: String): Boolean =
        path.isNotEmpty() && PATH.matches(path) && !path.startsWith("/") && !path.contains("..")

    /** 相对路径拼到站点根上；不安全的路径视为没有。 */
    fun resolve(
        baseUrl: String,
        path: String,
    ): String? = if (isValidPath(path)) baseUrl.trimEnd('/') + "/" + path else null

    fun httpsUrl(raw: String): String? {
        val url = raw.trim()
        if (url.isEmpty()) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        return url.takeIf {
            uri.isAbsolute && !uri.isOpaque &&
                uri.scheme.equals("https", true) &&
                !uri.host.isNullOrEmpty() && uri.rawUserInfo == null
        }
    }

    /** 只接受 `https://github.com/<owner>/<repo>/releases/download/<tag>/<file>`。 */
    fun isGitHubReleaseUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", true) || !uri.host.equals("github.com", true)) return false
        if (uri.port != -1 && uri.port != 443) return false
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return false
        val segments = uri.rawPath.orEmpty().removePrefix("/").split('/')
        if (segments.size < 6) return false
        if (segments[2] != "releases" || segments[3] != "download") return false
        return segments.all { it.isNotEmpty() && it != "." && it != ".." }
    }

    /**
     * ISO 8601 → epoch millis。仓库里的时间都是 UTC（`2026-11-10T03:07:15Z`），
     * minSdk 24 用不了 java.time，这里只按 UTC 解析到秒，够排序用了。
     */
    fun epochMillis(iso: String): Long {
        if (iso.length < 19) return 0L
        return runCatching {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            format.parse(iso.substring(0, 19))?.time ?: 0L
        }.getOrDefault(0L)
    }

    private val SCRIPT_BLOCK = Regex("(?is)<(script|style)[^>]*>.*?</\\1>")
    private val LINE_BREAK = Regex("(?i)<(br|/p|/div|/li|/h[1-6])[^>]*>")
    private val TAG_BLOCK = Regex("(?is)<[^>]+>")
    private val SPACES = Regex("[ \\t\\u00A0]+")
    private val BLANK_LINES = Regex("\\n{3,}")

    /** 把更新日志的 HTML 压成纯文本：保留换行、丢掉标签和图片。 */
    fun stripHtml(html: String): String {
        var text = SCRIPT_BLOCK.replace(html, "")
        text = LINE_BREAK.replace(text, "\n")
        text = TAG_BLOCK.replace(text, "")
        text = text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
        return text
            .lines()
            .joinToString("\n") { it.replace(SPACES, " ").trim() }
            .replace(BLANK_LINES, "\n\n")
            .trim()
    }

    /** `optString` 在字段是 JSON null 时会返回字符串 "null"，拼路径/展示都会带上它。 */
    private fun JSONObject.optText(key: String): String = if (isNull(key)) "" else optString(key)

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (!has(key) || isNull(key)) null else optInt(key)

    /** 许可证通常是 SPDX 标识（MIT、GPL-3.0），也接受 https 链接。 */
    private fun JSONObject.optWebUrlOrText(key: String): String? {
        val raw = optText(key).trim()
        if (raw.isEmpty()) return null
        return httpsUrl(raw) ?: raw.takeIf { it.length <= 64 && TAG.matches(it) }
    }

    private fun JSONObject.optHttps(key: String): String? = httpsUrl(optText(key))
}
