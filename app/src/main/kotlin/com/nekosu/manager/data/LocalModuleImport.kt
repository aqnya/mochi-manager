package com.nekosu.manager.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** 用户从文件选择器挑中的模块包，已经复制到应用自己的缓存目录。 */
data class StagedModule(
    val file: File,
    val displayName: String,
    val size: Long,
)

/**
 * 把 `content://` 的模块 ZIP 落到本地。
 *
 * `su -c ncore module install` 拿不到 SAF 的临时授权，root shell 也读不了文档提供者的
 * fd，所以必须先复制成真实文件，再把路径交给 ncore。
 */
object LocalModuleImport {
    /** 512 MB 上限：模块里最多是 so/apk，再大基本可以判定选错了文件。 */
    private const val MAX_BYTES = 512L * 1024 * 1024

    /**
     * 复制一组 ZIP。目录会先清空，文件名固定成 `imported-<n>.zip`——用户给的名字可能
     * 带空格、中文或路径分隔符，只用来展示，不参与落盘。
     */
    suspend fun stageAll(context: Context, uris: List<Uri>): List<StagedModule> =
        withContext(Dispatchers.IO) {
            require(uris.isNotEmpty()) { "没有选中文件" }
            val app = context.applicationContext
            val dir = File(app.cacheDir, "module_import").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }

            uris.mapIndexed { index, uri ->
                val name = displayName(app.contentResolver, uri) ?: "module-$index.zip"
                val target = File(dir, "imported-$index.zip")
                val bytes = copy(app.contentResolver, uri, target)
                if (bytes <= 4) {
                    target.delete()
                    throw IOException("$name 是空文件")
                }
                if (bytes > MAX_BYTES) {
                    target.delete()
                    throw IOException("$name 超过 512 MB，确认选的是模块包")
                }
                if (!looksLikeZip(target)) {
                    target.delete()
                    throw IOException("$name 不是 ZIP 包")
                }
                StagedModule(target, name, bytes)
            }
        }

    private fun copy(resolver: ContentResolver, uri: Uri, target: File): Long {
        val input = resolver.openInputStream(uri) ?: throw IOException("无法读取所选文件")
        input.use { source ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_BYTES) throw IOException("文件超过 512 MB")
                    output.write(buffer, 0, count)
                }
                return total
            }
        }
    }

    /** ZIP 的本地文件头 / 中央目录 / 空档标记都以 `PK` 开头。 */
    internal fun looksLikeZip(file: File): Boolean =
        runCatching {
            file.inputStream().use { input ->
                val head = ByteArray(2)
                input.read(head) == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
            }
        }.getOrDefault(false)

    /**
     * 从 ZIP 里的 `module.prop` 读模块 id，用来判断装完后模块目录有没有就位。
     *
     * 读不到或格式不合法就返回 null，调用方按「需要重启」保守处理——和 KernelSU
     * 的 `moduleNeedsReboot` 一致：探测不了就当它需要重启。
     */
    internal fun moduleId(file: File): String? =
        runCatching {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry("module.prop") ?: return@use null
                zip.getInputStream(entry).bufferedReader().useLines { lines ->
                    lines
                        .map { it.trim() }
                        .firstOrNull { it.startsWith("id=") }
                        ?.substringAfter('=')
                        ?.trim()
                }
            }
        }.getOrNull()?.takeIf { MODULE_ID.matches(it) }

    private val MODULE_ID = Regex("^[a-zA-Z][a-zA-Z0-9._-]+$")

    private fun displayName(resolver: ContentResolver, uri: Uri): String? =
        runCatching {
            resolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
            ?.takeIf { it.isNotBlank() }
}
