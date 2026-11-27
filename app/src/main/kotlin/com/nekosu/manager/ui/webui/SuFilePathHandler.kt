package com.nekosu.manager.ui.webui

import android.util.Log
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import com.nekosu.manager.data.RootShell
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLConnection
import java.util.zip.GZIPInputStream

private const val TAG = "NekoSU/WebUI"

/**
 * 通过 root 把模块的 `webroot/` 挂成一个 https 站点（WebViewAssetLoader 的 PathHandler）。
 *
 * 为什么不直接 `file://`：WebView 的 file 域没有同源隔离，模块页里跑的第三方 JS 能读
 * 整个 app 私有目录；https 域名只暴露 webroot 一个目录，和 KernelSU 的做法一致。
 *
 * 文件读取走 [RootShell.openSuReadStream]（`su -c cat`），webroot 在 /data/adb 下，
 * 应用进程自己读不到。访问范围在校验路径后限定在 webroot 内（canonicalPath 前缀比对，
 * `../` 和符号链接都进不来），保留路径 `internal/` 下发管理器侧的 CSS。
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
        try {
            val file = canonicalFileIfChild(webRoot, path)
            if (file != null) {
                val stream = openFile(file, path)
                if (stream != null) {
                    return WebResourceResponse(guessMimeType(path), null, stream)
                }
                Log.w(TAG, "root 读文件失败：$path")
                return notFound()
            }
            Log.w(TAG, "请求越出 webroot：$path")
        } catch (e: IOException) {
            Log.e(TAG, "处理请求失败：$path", e)
        }
        return notFound()
    }

    private fun cssResponse(css: String) =
        WebResourceResponse("text/css", "utf-8", ByteArrayInputStream(css.toByteArray(Charsets.UTF_8)))

    /** 404：null 流的响应在 WebView 里就是没有 body 的 404，不让请求落到网络去。 */
    private fun notFound() = WebResourceResponse(null, null, null)

    private fun openFile(
        file: File,
        path: String,
    ): InputStream? {
        val raw = RootShell.openSuReadStream("cat -- '${quote(file.absolutePath)}'") ?: return null
        return if (path.endsWith(".svgz")) {
            runCatching { GZIPInputStream(raw) }.getOrElse {
                raw.close()
                null
            }
        } else {
            raw
        }
    }

    /** 路径必须在 webroot 内；`..` 与软链接跳出去的都拒绝。 */
    private fun canonicalFileIfChild(
        parent: File,
        childPath: String,
    ): File? {
        val canonicalParent = parent.canonicalPath.let { if (it.endsWith("/")) it else "$it/" }
        val child = File(parent, childPath).canonicalFile
        if (child.canonicalPath.startsWith(canonicalParent) && child.isFile) return child
        return null
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
