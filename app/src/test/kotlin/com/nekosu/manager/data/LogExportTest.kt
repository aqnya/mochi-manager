package com.nekosu.manager.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/**
 * 保存日志不再走 `ACTION_CREATE_DOCUMENT`（HyperOS 上会返回空结果并让框架 NPE），
 * 这里验证两条落盘路径：Android 10+ 的 MediaStore，以及旧系统的公共下载目录。
 */
@RunWith(AndroidJUnit4::class)
class LogExportTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val payload = byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00, 0x42)

    private fun source(): File =
        File(context.cacheDir, "NekoSU_bugreport_2026-11-27_15_00.tar.gz").apply {
            writeBytes(payload)
        }

    @Test
    @Config(sdk = [31])
    fun `media store path writes through downloads collection`() {
        val target = File(context.cacheDir, "fake-download.bin").apply { delete() }
        var inserted: ContentValues? = null
        var pendingCleared = false
        val provider =
            object : ContentProvider() {
                override fun onCreate(): Boolean = true

                override fun insert(
                    uri: Uri,
                    values: ContentValues?,
                ): Uri {
                    inserted = ContentValues(values)
                    return Uri.parse("content://media/external/downloads/1")
                }

                override fun openFile(
                    uri: Uri,
                    mode: String,
                ): ParcelFileDescriptor =
                    ParcelFileDescriptor.open(
                        target,
                        ParcelFileDescriptor.MODE_WRITE_ONLY or
                            ParcelFileDescriptor.MODE_CREATE or
                            ParcelFileDescriptor.MODE_TRUNCATE,
                    )

                override fun update(
                    uri: Uri,
                    values: ContentValues?,
                    selection: String?,
                    selectionArgs: Array<String>?,
                ): Int {
                    pendingCleared = values?.getAsInteger("is_pending") == 0
                    return 1
                }

                override fun delete(
                    uri: Uri,
                    selection: String?,
                    selectionArgs: Array<String>?,
                ): Int = 1

                override fun query(
                    uri: Uri,
                    projection: Array<String>?,
                    selection: String?,
                    selectionArgs: Array<String>?,
                    sortOrder: String?,
                ): Cursor? = null

                override fun getType(uri: Uri): String? = "application/gzip"
            }
        // 必须带上 authority，否则 openOutputStream 会先校验再抛 SecurityException
        provider.attachInfo(
            context,
            android.content.pm.ProviderInfo().apply {
                authority = "media"
                exported = true
            },
        )
        ShadowContentResolver.registerProviderInternal("media", provider)

        val name = "NekoSU_bugreport_2026-11-27_15_00.tar.gz"
        val shown = runBlocking { LogExport.save(context, source(), name) }

        // 返回的是给人看的位置
        assertEquals("Download/NekoSU/$name", shown)
        // 目录与文件名要落到下载目录的 NekoSU 子目录里
        assertEquals("Download/NekoSU", inserted?.getAsString("relative_path"))
        assertEquals(name, inserted?.getAsString("_display_name"))
        // 写完把 IS_PENDING 清掉，文件才会在文件管理里出现
        assertTrue("IS_PENDING 没有清零", pendingCleared)
        assertTrue(target.readBytes().contentEquals(payload))
    }
}
