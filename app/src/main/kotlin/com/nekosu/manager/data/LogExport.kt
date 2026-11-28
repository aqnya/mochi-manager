package com.nekosu.manager.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 把生成好的报告落到用户能找到的位置。
 *
 * 不走系统的 `ACTION_CREATE_DOCUMENT`：HyperOS 的文档界面拿到 `application/gzip`
 * 会立刻返回一个没有 extras 的结果，MIUI 的 `ActivityThread.deliverResultsIfNeeded`
 * 直接 NPE（`Bundle.getString` on null），应用只收到 `uri = null`，表现就是「点了没反应」。
 * 这里直接写公共下载目录的 `NekoSU/` 子目录，省掉这一步交互。
 */
object LogExport {
    private const val TAG = "NekoSU/LogExport"
    private const val FOLDER = "NekoSU"
    private const val MIME = "application/gzip"

    /** 返回给人看的保存位置，例如 `Download/NekoSU/NekoSU_bugreport_...tar.gz`。 */
    suspend fun save(
        context: Context,
        file: File,
        displayName: String,
        mime: String = MIME,
    ): String =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, file, displayName, mime)
            } else {
                saveToDownloads(context, file, displayName)
            }
        }

    /** Android 10+：MediaStore 不需要任何权限，文件会出现在「下载」里并被媒体库索引。 */
    private fun saveViaMediaStore(
        context: Context,
        file: File,
        displayName: String,
        mime: String,
    ): String {
        val resolver = context.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        val uri =
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("无法在下载目录创建文件（MediaStore.insert 返回 null）")
        Log.d(TAG, "MediaStore insert uri=$uri srcBytes=${file.length()}")
        try {
            val written =
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: throw IOException("无法写入下载目录（openOutputStream 返回 null）")
            if (written != file.length()) {
                throw IOException("写入不完整：源 ${file.length()} 字节，实际 $written 字节")
            }
            val cleared =
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            Log.d(TAG, "write done bytes=$written cleared=$cleared uri=$uri")
        } catch (e: Throwable) {
            Log.e(TAG, "write to downloads failed uri=$uri", e)
            // 写了一半就失败：删掉占位记录，别在「下载」里留个 0 字节文件
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/$displayName"
    }

    /** Android 9 及以下：有写外部存储权限就写公共下载目录，没有就退到应用自己的外部目录。 */
    @Suppress("DEPRECATION")
    private fun saveToDownloads(
        context: Context,
        file: File,
        displayName: String,
    ): String {
        val granted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        val dir =
            if (granted) {
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
            } else {
                context.getExternalFilesDir(FOLDER) ?: File(context.filesDir, FOLDER)
            }
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("无法创建 ${dir.absolutePath}")
        val target = File(dir, displayName)
        file.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        return target.absolutePath
    }
}
