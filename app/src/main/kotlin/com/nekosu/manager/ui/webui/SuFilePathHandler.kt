package com.nekosu.manager.ui.webui

import android.util.Log
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import com.nekosu.manager.data.RootShell
import com.nekosu.manager.neko.NekoNative
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLConnection
import java.util.zip.GZIPInputStream

private const val TAG = "NekoSU/WebUI"

/**
 * 把请求路径解析成 webroot 内的绝对路径，越界返回 null。
 *
 * 只做字符串级校验，**绝不 stat**：应用进程对 /data/adb 没有搜索权限，
 * `File.canonicalFile` / `isFile` 全都会失败（bugreport 里 index.html 被误判
 * 越界就是这么来的）。符号链接不解析——能往 webroot 里放软链接的模块本身就有
 * root，这里防的是网页路径穿越（`..`、绝对路径），不是提权。
 */
internal fun resolveWithinWebRoot(
    webRootPath: String,
    childPath: String,
): String? {
    // 绝对路径必须显式拒绝：Unix 上 File(parent, child) 不区分 child 是否绝对路径，
    // 只做字符串拼接，"/etc/passwd" 会被拼成 "<webroot>//etc/passwd"
    if (childPath.isEmpty() || childPath.startsWith('/')) return null
    val root = File(webRootPath).normalize()
    val normalized = File(root, childPath).normalize()
    val prefix = root.path.let { if (it.endsWith("/")) it else "$it/" }
    if (!normalized.path.startsWith(prefix)) return null
    return normalized.path
}

/**
 * 通过 root 把模块的 `webroot/` 挂成一个 https 站点（WebViewAssetLoader 的 PathHandler）。
 *
 * 为什么不直接 `file://`：WebView 的 file 域没有同源隔离，模块页里跑的第三方 JS 能读
 * 整个 app 私有目录；https 域名只暴露 webroot 一个目录，和 KernelSU 的做法一致。
 *
 * 文件读取优先走 [NekoNative.readRootFile]（fork 子进程里 setresuid 临时提权，
 * 无需每次 exec 一个 `su -c cat`），内核不在线或文件超上限时回退到
 * [RootShell.openSuReadStream]。webroot 在 /data/adb 下，应用进程自己读不到。
 * 访问范围用 [resolveWithinWebRoot] 的字符串级前缀校验限定，
 * 保留路径 `internal/` 下发管理器侧的 CSS。
 */
class SuFilePathHandler(
    private val webRoot: File,
    private val insetsSupplier: () -> WebInsets,
) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse {
        // 模块页按约定引用这两条保留路径拿管理器的 CSS
        if (path == "internal/colors.css") {
            return cssResponse(WebUiCss.vars)
        }
        if (path == "internal/insets.css") {
            return cssResponse(insetsSupplier().css)
        }
        val target = resolveWithinWebRoot(webRoot.path, path)
        if (target == null) {
            Log.w(TAG, "请求越出 webroot：$path")
            return notFound()
        }
        Log.d(TAG, "serve $path")
        return try {
            val stream =
                openFile(target, path)
                    ?: run {
                        Log.w(TAG, "root 读文件失败：$path")
                        return notFound()
                    }
            WebResourceResponse(guessMimeType(path), null, stream)
        } catch (e: IOException) {
            Log.e(TAG, "处理请求失败：$path", e)
            notFound()
        }
    }

    private fun cssResponse(css: String) =
        WebResourceResponse("text/css", "utf-8", ByteArrayInputStream(css.toByteArray(Charsets.UTF_8)))

    /** 真正的 404：null 流的 WebResourceResponse 会被 WebView 当成 ERR_INVALID_RESPONSE 而不是 404。 */
    private fun notFound() =
        WebResourceResponse(
            "text/plain",
            "utf-8",
            404,
            "Not Found",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )

    private fun openFile(
        targetPath: String,
        path: String,
    ): InputStream? {
        // 原生读取：内核在线时子进程 setresuid 临时提权，比每次 fork 一个
        // `su -c cat`（多一次 su 二进制 exec + shell 解析 + SELinux 域迁移）便宜一个量级。
        // null（内核不在线 / 不是 manager / 文件超上限）回退到 su 流式读取。
        NekoNative.readRootFile(targetPath)?.let { return decompressIfSvgz(ByteArrayInputStream(it), path) }
        val raw = RootShell.openSuReadStream("cat -- '${quote(targetPath)}'") ?: return null
        return decompressIfSvgz(raw, path)
    }

    /** `.svgz` 是 gzip 压缩的 svg，解压成流再交给 WebView；解不开按 404 处理。 */
    private fun decompressIfSvgz(
        stream: InputStream,
        path: String,
    ): InputStream? {
        if (!path.endsWith(".svgz")) return stream
        return runCatching { GZIPInputStream(stream) }
            .getOrElse {
                runCatching { stream.close() }
                null
            }
    }

    /** `URLConnection` 对 html/js/woff2 一类给不出正确类型，先查表再兜底。 */
    private fun guessMimeType(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        val mapped =
            when (ext) {
                "html", "htm" -> "text/html"
                "js", "mjs" -> "text/javascript"
                "css" -> "text/css"
                "json" -> "application/json"
                "svg" -> "image/svg+xml"
                "svgz" -> "image/svg+xml"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "ico" -> "image/x-icon"
                "woff" -> "font/woff"
                "woff2" -> "font/woff2"
                "ttf" -> "font/ttf"
                "otf" -> "font/otf"
                "mp4" -> "video/mp4"
                "webm" -> "video/webm"
                "mp3" -> "audio/mpeg"
                "ogg" -> "audio/ogg"
                "wav" -> "audio/wav"
                "txt" -> "text/plain"
                "xml" -> "text/xml"
                "wasm" -> "application/wasm"
                else -> null
            }
        if (mapped != null) return mapped
        return URLConnection.guessContentTypeFromName(path) ?: "text/plain"
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
