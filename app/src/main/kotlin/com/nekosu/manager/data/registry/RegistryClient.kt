package com.nekosu.manager.data.registry

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** 拉取一份文本文档；网络或 HTTP 失败时抛 [IOException]。 */
fun interface RegistryFetcher {
    @Throws(IOException::class)
    fun fetch(url: String): String
}

/** 上一次完整好用的副本，键是文件名安全的字符串。 */
interface RegistryCache {
    fun read(key: String): String?

    fun write(
        key: String,
        value: String,
    )
}

/**
 * 仓库协议客户端：index → modules → detail。
 *
 * 三条规则照搬上游，避免把不同时刻的文档拼在一起：
 * 1. index 与 modules 的 buildId 必须一致，不一致就重读一次，仍不一致则失败；
 * 2. 每次都保留最后一份完整副本，网络/解析失败时用它兜底，并标记 `stale`；
 * 3. 详情文档的 buildId 必须和当前索引一致，站点刚重新部署时重读一次索引再取。
 *
 * 全是阻塞调用，必须在 IO 线程执行。
 */
class RegistryClient(
    private val baseUrl: String = RegistryUrls.BASE_URL,
    private val fetcher: RegistryFetcher,
    private val cache: RegistryCache,
    private val siteLocale: () -> String = { RegistryUrls.SITE_LOCALE },
) {
    data class CatalogLoad(
        val catalog: RepoCatalog,
        /** 非空表示网络副本不可用，返回的是缓存副本。 */
        val error: Throwable? = null,
    )

    data class DetailLoad(
        val detail: RepoModuleDetail,
        val error: Throwable? = null,
    )

    @Volatile
    private var current: RepoCatalog? = null

    @Volatile
    private var currentBuildId: String? = null

    private val lock = Any()

    fun cachedCatalog(): RepoCatalog? = current

    /** 索引 + 列表；失败时退回上一次的完整副本。 */
    fun loadCatalog(): CatalogLoad =
        synchronized(lock) {
            try {
                CatalogLoad(fetchCatalog())
            } catch (e: Exception) {
                val fallback = current ?: readCachedCatalog()
                if (fallback != null) {
                    current = fallback
                    currentBuildId = fallback.buildId
                    CatalogLoad(fallback.copy(stale = true, error = e), e)
                } else {
                    throw e
                }
            }
        }

    private fun fetchCatalog(): RepoCatalog {
        var lastError: Exception? = null
        repeat(2) {
            val indexText = fetcher.fetch(resolve(RegistryUrls.INDEX_PATH))
            val index = RegistryParser.parseIndex(indexText)
            val modulesText = fetcher.fetch(resolve(index.modulesPath))
            val modules = RegistryParser.parseModules(modulesText, baseUrl)
            if (index.buildId.isNotEmpty() && modules.buildId == index.buildId) {
                cache.write(
                    CATALOG_KEY,
                    JSONObject()
                        .put("index", indexText)
                        .put("modules", modulesText)
                        .toString(),
                )
                val catalog = RepoCatalog(modules.modules, index.buildId)
                current = catalog
                currentBuildId = index.buildId
                return catalog
            }
            lastError = RegistryFormatException("index buildId ${index.buildId} != modules buildId ${modules.buildId}")
        }
        throw lastError ?: RegistryFormatException("empty registry index")
    }

    private fun readCachedCatalog(): RepoCatalog? =
        runCatching {
            val text = cache.read(CATALOG_KEY) ?: return null
            val root = JSONObject(text)
            val index = RegistryParser.parseIndex(root.getString("index"))
            val modules = RegistryParser.parseModules(root.getString("modules"), baseUrl)
            if (index.buildId.isNotEmpty() && modules.buildId != index.buildId) return null
            RepoCatalog(modules.modules, index.buildId, stale = true)
        }.getOrNull()

    /** 某个模块的详情；网络失败时用同一 buildId 的缓存副本。 */
    fun loadDetail(module: RepoModule): DetailLoad {
        val catalog = current ?: loadCatalog().catalog
        val buildId = currentBuildId
        try {
            val text = fetcher.fetch(resolve(module.detailPath))
            val detail = RegistryParser.parseDetail(text, baseUrl, module, siteLocale())
            if (detail.module.id != module.id) {
                throw RegistryFormatException("detail id ${detail.module.id} != ${module.id}")
            }
            writeDetail(module.id, buildId, text)
            return DetailLoad(detail)
        } catch (e: Exception) {
            if (e is RegistryFormatException && catalog.modules.none { it.id == module.id }) throw e
            val cached = readCachedDetail(module, buildId)
            if (cached != null) return DetailLoad(cached, e)
            throw e
        }
    }

    private fun writeDetail(
        id: String,
        buildId: String?,
        text: String,
    ) {
        cache.write("detail-$id", (buildId ?: "") + "\n" + text)
    }

    private fun readCachedDetail(
        module: RepoModule,
        buildId: String?,
    ): RepoModuleDetail? =
        runCatching {
            val cached = cache.read("detail-${module.id}") ?: return null
            val cachedBuild = cached.substringBefore('\n')
            // 只复用同一份索引的详情，避免列表里的版本和详情里的版本对不上
            if (buildId != null && cachedBuild != buildId) return null
            RegistryParser.parseDetail(cached.substringAfter('\n'), baseUrl, module, siteLocale())
                .copy(fromCache = true)
        }.getOrNull()

    private fun resolve(path: String): String =
        RegistryParser.resolve(baseUrl, path)
            ?: throw RegistryFormatException("Invalid registry path: $path")

    private companion object {
        const val CATALOG_KEY = "catalog"
    }
}

/**
 * 用 [HttpURLConnection] 拉文档：15s 超时、跟随重定向、上限 32 MB。
 * 单份文档超过上限说明拿到的不是索引而是别的东西（错误页 / 二进制），直接失败。
 */
class HttpRegistryFetcher(
    private val userAgent: String = "NekoSU-Manager",
) : RegistryFetcher {
    override fun fetch(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json, text/html;q=0.8, */*;q=0.5")
            setRequestProperty("User-Agent", userAgent)
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code for $url")
            if (connection.contentLengthLong > MAX_DOCUMENT_BYTES) {
                throw IOException("Document too large: $url")
            }
            val bytes =
                connection.inputStream.use { input ->
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(chunk)
                        if (read < 0) break
                        total += read
                        if (total > MAX_DOCUMENT_BYTES) throw IOException("Document too large: $url")
                        buffer.write(chunk, 0, read)
                    }
                    buffer.toByteArray()
                }
            return bytes.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val MAX_DOCUMENT_BYTES = 32L * 1024 * 1024
    }
}

/** 站点地址与语言。 */
object RegistryUrls {
    /** KernelSU Modules 的站点根，所有 API 路径都相对它解析。 */
    const val BASE_URL = "https://modules.kernelsu.org/"

    const val INDEX_PATH = "api/v1/index.json"

    /** 中文环境链到中文详情页，其余走英文。 */
    val SITE_LOCALE: String
        get() = if (Locale.getDefault().language.equals("zh", true)) "zh-CN" else "en"
}
