package com.nekosu.manager.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "NekoSU/ModuleUpdate"

/** updateJson 里的一个版本记录。 */
data class ModuleUpdateInfo(
    val version: String,
    val versionCode: Long,
    val zipUrl: String,
    val changelog: String,
)

/** `module.prop` 里跟更新相关的两个字段。 */
data class ModuleProp(
    val versionCode: Long,
    val updateJson: String,
)

/**
 * 模块的「更新」链路，对齐 KernelSU 的行为：
 *
 * 1. 读 `module.prop` 拿 `updateJson`（ncore 的列表 JSON 不一定带，缺了就补读一次 prop）；
 * 2. 拉取 updateJson，比较 `versionCode`，有新版才下载 `zipUrl`；
 * 3. ZIP 落到缓存后走和本地安装完全同一条链路（安装输出页），不另起炉灶。
 */
object ModuleUpdate {
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 30_000

    /** updateJson 上限：它只是个版本描述，超过 1 MB 基本可以判定返回的不是它。 */
    private const val MAX_MANIFEST_BYTES = 1L * 1024 * 1024

    /**
     * 检查一个模块的可用更新。
     *
     * @return [CheckResult.Available] 有新版；[CheckResult.UpToDate] 版本不比当前高；
     *   [CheckResult.NoUpdateUrl] 模块没写 updateJson；[CheckResult.Failed] 地址存在但拉不到或解析不了。
     */
    suspend fun check(
        module: ModuleEntry,
        propFallback: suspend (String) -> ModuleProp? = ::readProp,
    ): CheckResult {
        val updateJson =
            module.updateJson.ifBlank {
                // 列表 JSON 缺字段：补读一次 module.prop，一次 shell 调用
                propFallback(module.id)?.updateJson.orEmpty()
            }
        if (updateJson.isBlank()) return CheckResult.NoUpdateUrl

        val currentVersionCode =
            module.versionCode.takeIf { it > 0 } ?: propFallback(module.id)?.versionCode ?: 0L

        val info =
            try {
                fetchManifest(updateJson)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return CheckResult.Failed(e.message ?: "网络错误")
            }
        return decide(info, currentVersionCode)
    }

    /** 清单拿到之后的判定，独立出来供单测覆盖比较逻辑。 */
    internal fun decide(
        info: ModuleUpdateInfo?,
        currentVersionCode: Long,
    ): CheckResult =
        when {
            info == null -> CheckResult.Failed("updateJson 的内容无法解析")
            info.zipUrl.isBlank() -> CheckResult.Failed("updateJson 里没有 zipUrl")
            currentVersionCode > 0 && info.versionCode <= currentVersionCode ->
                CheckResult.UpToDate(info.version)
            else -> CheckResult.Available(info)
        }

    sealed interface CheckResult {
        data class Available(
            val info: ModuleUpdateInfo,
        ) : CheckResult

        data class UpToDate(
            val version: String,
        ) : CheckResult

        data object NoUpdateUrl : CheckResult

        data class Failed(
            val message: String,
        ) : CheckResult
    }

    /** 读模块的 `module.prop`（root shell cat），解析 versionCode / updateJson。 */
    suspend fun readProp(id: String): ModuleProp? {
        val result =
            runCatching { RootShell.exec("cat -- '/data/adb/modules/${quote(id)}/module.prop'") }
                .onFailure { Log.w(TAG, "read module.prop failed", it) }
                .getOrNull() ?: return null
        if (result.exitCode != 0) return null
        return parseProp(result.output)
    }

    /** 把 updateJson 指向的 ZIP 下载到缓存并校验是包；成功返回可直接交给安装页的 [StagedModule]。 */
    suspend fun download(
        context: Context,
        module: ModuleEntry,
        info: ModuleUpdateInfo,
        onProgress: (Float) -> Unit,
    ): StagedModule =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "module_update").apply { mkdirs() }
            val temp = File(dir, "${module.id}.part")
            val target = File(dir, "${module.id}.zip")
            temp.delete()
            target.delete()
            try {
                fetchZip(info.zipUrl, temp, onProgress)
                if (!LocalModuleImport.looksLikeZip(temp)) throw IOException("下载的内容不是模块包")
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                StagedModule(target, "${module.name} ${info.version}".trim(), target.length())
            } catch (e: Throwable) {
                temp.delete()
                if (e is CancellationException) throw e
                throw IOException("下载失败：${e.message}", e)
            }
        }

    /** 解析 updateJson（单个 JSON 对象）。字段都当可选，缺 zipUrl 由调用方按错误处理。 */
    internal fun parseManifest(raw: String): ModuleUpdateInfo? =
        runCatching {
            val json = JSONObject(raw.trim())
            ModuleUpdateInfo(
                version = json.optString("version"),
                versionCode = json.optLong("versionCode"),
                zipUrl = json.optString("zipUrl"),
                changelog = json.optString("changelog"),
            )
        }.getOrNull()?.takeIf { it.zipUrl.isNotBlank() }

    /** module.prop 的 key=value 解析；注释行与无 = 的行跳过。 */
    internal fun parseProp(raw: String): ModuleProp {
        var versionCode = 0L
        var updateJson = ""
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val key = trimmed.substringBefore('=', "").trim()
            val value = trimmed.substringAfter('=', "").trim()
            when (key) {
                "versionCode" -> versionCode = value.toLongOrNull() ?: 0L
                "updateJson" -> updateJson = value
            }
        }
        return ModuleProp(versionCode, updateJson)
    }

    private suspend fun fetchManifest(url: String): ModuleUpdateInfo? =
        withContext(Dispatchers.IO) {
            val connection = openConnection(url)
            try {
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
                val text = connection.inputStream.use { input ->
                    // minSdk 覆盖不到 InputStream.readNBytes，手动限长读
                    val out = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8 * 1024)
                    var total = 0
                    while (total < MAX_MANIFEST_BYTES) {
                        val read = input.read(chunk, 0, minOf(chunk.size, (MAX_MANIFEST_BYTES - total).toInt()))
                        if (read < 0) break
                        out.write(chunk, 0, read)
                        total += read
                    }
                    String(out.toByteArray(), Charsets.UTF_8)
                }
                parseManifest(text)
            } finally {
                connection.disconnect()
            }
        }

    private suspend fun fetchZip(
        url: String,
        target: File,
        onProgress: (Float) -> Unit,
    ) {
        val connection = openConnection(url)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    var lastReported = -1
                    while (true) {
                        currentCoroutineContext().ensureActive()
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

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "NekoSU-Manager")
        }

    private fun quote(value: String): String = value.replace("'", "'\\''")
}
