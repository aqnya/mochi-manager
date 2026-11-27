package com.nekosu.manager.data.registry

import android.content.Context
import android.util.Log
import com.nekosu.manager.neko.Ncore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

private const val LOG_TAG = "NekoSU/Module"

/** 缓存目录里的一份文本，键直接当文件名用（都由 [RegistryClient] 生成，安全）。 */
class FileRegistryCache(
    private val dir: File,
) : RegistryCache {
    init {
        dir.mkdirs()
    }

    override fun read(key: String): String? =
        runCatching {
            val file = File(dir, key)
            if (file.isFile) file.readText() else null
        }.getOrNull()

    override fun write(
        key: String,
        value: String,
    ) {
        runCatching {
            val file = File(dir, key)
            val temp = File(dir, "$key.tmp")
            temp.writeText(value)
            if (!temp.renameTo(file)) {
                file.writeText(value)
                temp.delete()
            }
        }
    }
}

/** 下载并校验好的模块 ZIP。 */
data class DownloadedModule(
    val file: File,
    val fromCache: Boolean,
)

/**
 * 模块 ZIP 的下载与校验。
 *
 * 地址在解析阶段已经限定为 GitHub Release，这里再校验一次记录的大小和 SHA-256：
 * 仓库索引是不可变的 Release，内容对不上就删除，绝不交给 `ksud` 安装。
 */
object RepoDownloads {
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 60_000

    suspend fun download(
        release: RepoRelease,
        dir: File,
        onProgress: (Float) -> Unit,
    ): DownloadedModule =
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val target = File(dir, "${release.sha256}.zip")
            if (target.isFile && verify(target, release.zipSize, release.sha256)) {
                onProgress(1f)
                return@withContext DownloadedModule(target, fromCache = true)
            }
            val temp = File(dir, "${release.sha256}.part")
            temp.delete()
            try {
                fetch(release.zipUrl, temp, release.zipSize, onProgress)
                requireIntegrity(temp, release)
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                DownloadedModule(target, fromCache = false)
            } catch (e: Throwable) {
                temp.delete()
                if (e is CancellationException) throw e
                throw if (e is RegistryIntegrityException) e else IOException("下载失败：${e.message}", e)
            }
        }

    private suspend fun fetch(
        url: String,
        target: File,
        expectedSize: Long,
        onProgress: (Float) -> Unit,
    ) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "NekoSU-Manager")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: expectedSize
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    var lastReported = -1
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            val percent = ((written * 100) / total).toInt().coerceIn(0, 100)
                            if (percent != lastReported) {
                                lastReported = percent
                                onProgress(percent / 100f)
                            }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** 大小 + SHA-256 都对才算通过；大小未知（0）时只比哈希。 */
    fun verify(
        file: File,
        expectedSize: Long,
        expectedSha256: String,
    ): Boolean {
        if (!file.isFile) return false
        if (expectedSize > 0 && file.length() != expectedSize) return false
        return sha256(file).equals(expectedSha256, ignoreCase = true)
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 校验失败时抛 [RegistryIntegrityException]，带上实际大小方便排查。 */
    private fun requireIntegrity(
        file: File,
        release: RepoRelease,
    ) {
        if (!verify(file, release.zipSize, release.sha256)) {
            val actual = if (file.isFile) file.length() else -1L
            throw RegistryIntegrityException(
                "${release.zipName} 未通过完整性校验（期望 ${release.zipSize} 字节，实际 $actual）",
            )
        }
    }
}

/** 安装结果：成功时带上 ksud 的输出，失败时是可读的原因。 */
sealed interface InstallOutcome {
    data class Success(
        val output: String,
    ) : InstallOutcome

    data class Failure(
        val message: String,
        val output: String = "",
    ) : InstallOutcome
}

/**
 * 用 KernelSU 的 `ksud module install` 安装 ZIP。
 *
 * 这一步需要 root：`su -c` 起一个 shell 跑 ksud。管理器自身没有安装逻辑，
 * 真正的解包、校验 `module.prop`、写 `/data/adb/modules` 都由 ksud 完成，
 * 所以这里只负责把文件放到 root 可读的位置并把输出带回来。
 */
object ModuleInstaller {
    private const val TIMEOUT_SECONDS = 120L

    suspend fun install(
        context: Context,
        zip: File,
    ): InstallOutcome =
        withContext(Dispatchers.IO) {
            if (!zip.isFile) return@withContext InstallOutcome.Failure("安装包不存在：${zip.name}")

            // 1) neko 的 ncore：解包、校验 module.prop、写 /data/adb/modules 都由它完成
            if (runCatching { Ncore.ensureInstalled(context) }.getOrDefault(false)) {
                val result = runCatching { Ncore.moduleInstall(zip) }.getOrNull()
                if (result != null && result.exitCode == 0) {
                    return@withContext InstallOutcome.Success(result.output.ifBlank { "安装完成" })
                }
                Log.w(LOG_TAG, "ncore 安装失败：${result?.output?.take(200)}")
            }

            // 2) 没有可用的 ncore 时退回 KernelSU 的 ksud
            val quoted = "'" + zip.absolutePath.replace("'", "'\\''") + "'"
            var lastFailure: InstallOutcome? = null
            for (ksud in listOf("/data/adb/ksud", "ksud")) {
                val result =
                    runCatching { exec("$ksud module install $quoted") }
                        .getOrElse { e ->
                            lastFailure = InstallOutcome.Failure("无法执行 su：${e.message}")
                            continue
                        }
                if (result.exitCode == 0) {
                    return@withContext InstallOutcome.Success(result.output.ifBlank { "安装完成" })
                }
                lastFailure =
                    InstallOutcome.Failure(
                        if (result.output.contains("not found", true)) {
                            "找不到 ksud，确认设备已安装 KernelSU 并能正常授权 root"
                        } else {
                            "ksud 返回 ${result.exitCode}"
                        },
                        result.output,
                    )
            }
            lastFailure ?: InstallOutcome.Failure("安装失败")
        }

    private class Result(
        val exitCode: Int,
        val output: String,
    )

    private fun exec(command: String): Result {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output =
            process.inputStream.bufferedReader().use { reader ->
                val text = StringBuilder()
                val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS)
                while (System.currentTimeMillis() < deadline) {
                    if (reader.ready()) {
                        text.append(reader.readLine()).append('\n')
                    } else if (!process.isAlive) {
                        break
                    } else {
                        Thread.sleep(50)
                    }
                }
                if (process.isAlive) {
                    process.destroy()
                    throw IOException("ksud 超时（${TIMEOUT_SECONDS}s）")
                }
                text.toString().trim()
            }
        return Result(process.waitFor(), output)
    }
}

/** 列表与详情的数据来源；单测用假实现替换网络。 */
interface ModuleRepoSource {
    suspend fun catalog(): RepoCatalog

    suspend fun detail(module: RepoModule): RepoModuleDetail
}

/** 下载 + 安装；单测用假实现替换 root 与网络。 */
interface ModuleInstallService {
    suspend fun download(
        release: RepoRelease,
        onProgress: (Float) -> Unit = {},
    ): DownloadedModule

    suspend fun install(zip: File): InstallOutcome
}

/** 仓库数据的入口：进程内单例，UI 直接调，缓存与网络都在 IO 线程。 */
class ModuleRepoRepository private constructor(
    private val context: Context,
) : ModuleRepoSource,
    ModuleInstallService {
    private val client: RegistryClient by lazy {
        RegistryClient(
            baseUrl = RegistryUrls.BASE_URL,
            fetcher = HttpRegistryFetcher(),
            cache = FileRegistryCache(File(context.filesDir, "module_registry")),
        )
    }

    override suspend fun catalog(): RepoCatalog =
        withContext(Dispatchers.IO) { client.loadCatalog().catalog }

    override suspend fun detail(module: RepoModule): RepoModuleDetail =
        withContext(Dispatchers.IO) { client.loadDetail(module).detail }

    override suspend fun download(
        release: RepoRelease,
        onProgress: (Float) -> Unit,
    ): DownloadedModule = RepoDownloads.download(release, File(context.cacheDir, "module_downloads"), onProgress)

    override suspend fun install(zip: File): InstallOutcome = ModuleInstaller.install(context, zip)

    /** 图标缓存目录，UI 层的图标加载器共用。 */
    val iconDir: File
        get() = File(context.filesDir, "module_registry/icons")

    companion object {
        @Volatile
        private var instance: ModuleRepoRepository? = null

        fun get(context: Context): ModuleRepoRepository =
            instance ?: synchronized(this) {
                instance ?: ModuleRepoRepository(context.applicationContext).also { instance = it }
            }

        internal fun resetForTests() {
            instance = null
        }
    }
}
