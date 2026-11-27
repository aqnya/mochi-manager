package com.nekosu.manager.ui.mochi

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ui.feice.mochi.basic.Text
import ui.feice.mochi.theme.MochiTheme
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 模块图标。仓库里的图标是内容哈希命名的静态文件，按哈希落盘缓存，
 * 换版本时 URL 变了自然会重新下载，不用处理过期。
 *
 * 加载失败 / 没有图标时退回「首字母色块」，不占位成空白。
 */
@Composable
fun RepoIcon(
    url: String?,
    name: String,
    dir: File,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, url) {
        value = url?.let { withContext(Dispatchers.IO) { RepoIcons.load(it, dir) } }
    }
    val shape = RoundedCornerShape(size * 0.24f)
    val cs = MochiTheme.colorScheme
    val loaded = bitmap
    if (loaded != null) {
        Image(
            loaded,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(shape),
        )
    } else {
        Box(
            modifier
                .size(size)
                .clip(shape)
                .background(cs.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.trim().take(1).ifBlank { "?" },
                style = MochiTheme.textStyles.body.copy(fontWeight = FontWeight.SemiBold),
                color = cs.primary,
            )
        }
    }
}

/** 图标的内存 + 磁盘缓存。 */
object RepoIcons {
    private const val TARGET_PX = 160

    /** 以像素字节数计上限，滚过几十个模块也不会把位图堆在内存里。 */
    private val memory =
        object : android.util.LruCache<String, ImageBitmap>(12 * 1024 * 1024) {
            override fun sizeOf(
                key: String,
                value: ImageBitmap,
            ): Int = value.width * value.height * 4
        }

    suspend fun load(
        url: String,
        dir: File,
    ): ImageBitmap? {
        memory.get(url)?.let { return it }
        val file = File(dir, cacheName(url))
        if (!file.isFile) {
            runCatching {
                dir.mkdirs()
                download(url, file)
            }
        }
        val bitmap = runCatching { decode(file) }.getOrNull() ?: return null
        val image = bitmap.asImageBitmap()
        memory.put(url, image)
        return image
    }

    private fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_PX) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun download(
        url: String,
        target: File,
    ) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "NekoSU-Manager")
        }
        try {
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
            val temp = File(target.absolutePath + ".part")
            connection.inputStream.use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun cacheName(url: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
