package com.nekosu.manager.ui.webui

import android.util.Log
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import com.nekosu.manager.data.RootShell
import com.nekosu.manager.neko.NekoNative
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLConnection
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

private const val TAG = "NekoSU/WebUI"

/**
 * 拦截响应必须带的头。
 *
 * 这是 WebView 的一个硬性坑：`shouldInterceptRequest` 返回的 `WebResourceResponse`
 * **不会**被 WebView 当作「同源可信响应」，带 `crossorigin` 的请求（Vite 动态插入的
 * `<link rel=stylesheet crossorigin>`、ES module 的 modulepreload）会因 CORS 校验
 * 不通过而失败。实测表现就是模块页的 CSS 预加载报
 * `Unable to preload CSS for https://.../App-xxx.css`，应用启动即中断、整页白屏；
 * 而 HTML 里静态写的、没有 crossorigin 的 `<link>` 反而正常。
 *
 * 实测 `Access-Control-Allow-Origin: *` 在该机型（HyperOS 3 + WebView 157）上
 * 仍被拒绝——同源请求默认带凭证，通配符在凭证模式下不合法。所以这里回显页面
 * 自己所在的精确 Origin 并允许凭证；配合 [WEBUI_HTML_SHIM] 去掉动态 link 的
 * crossorigin，两条路都留好。
 */
internal val WEBUI_RESPONSE_HEADERS: Map<String, String> =
    mapOf(
        "Access-Control-Allow-Origin" to "https://$WEB_DOMAIN",
        "Access-Control-Allow-Credentials" to "true",
        "Access-Control-Allow-Methods" to "GET, OPTIONS",
        "Access-Control-Allow-Headers" to "*",
        // 模块更新后同名资源可能内容变化，别让 WebView 用旧缓存
        "Cache-Control" to "no-cache",
    )

/**
 * 注入到模块页 `<head>` 的引导脚本：让动态创建的 `<link>` 忽略 `crossorigin`。
 *
 * Vite 的预加载 helper 会给 CSS 链接设 `link.crossOrigin = ''`，请求因此变成
 * CORS 模式；而 WebView 的拦截响应在 CORS 模式下会失败（见 [WEBUI_RESPONSE_HEADERS]）。
 * 静态写在 HTML 里的样式链接没有 crossorigin，走 no-cors，从来都是好的——
 * 这里把动态链接拉回同一条路。模块页的资源全在本机同一个虚拟域内，
 * 去掉 crossorigin 不影响任何合法用法。
 */
private val WEBUI_HTML_SHIM =
    """
    <script>(function(){try{
      Object.defineProperty(HTMLLinkElement.prototype,'crossOrigin',{
        configurable:true,enumerable:true,get:function(){return null;},
        set:function(){}
      });
    }catch(e){}})();</script>
    """.trimIndent().replace("\n", "")

/**
 * 把引导脚本插进 HTML：`<head>` 后面优先，否则紧跟在 doctype 之后。
 * 经典脚本先于模块脚本（deferred）执行，所以一定赶在 Vite 的预加载之前。
 */
internal fun injectWebUiShim(html: String): String {
    if (html.contains(WEBUI_HTML_SHIM)) return html
    val headIndex =
        listOf("<head>", "<head ", "<HEAD>", "<HEAD ", "<head\n").map { html.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull()
    if (headIndex != null) {
        val insertAt = html.indexOf('>', headIndex) + 1
        if (insertAt > 0) return html.substring(0, insertAt) + WEBUI_HTML_SHIM + html.substring(insertAt)
    }
    val doctypeEnd = html.indexOf('>')
    if (html.take(15).contains("<!doctype", ignoreCase = true) && doctypeEnd >= 0) {
        return html.substring(0, doctypeEnd + 1) + WEBUI_HTML_SHIM + html.substring(doctypeEnd + 1)
    }
    return WEBUI_HTML_SHIM + html
}

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
    val root = java.io.File(webRootPath).normalize()
    val normalized = java.io.File(root, childPath).normalize()
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
 *
 * 读到的字节按路径缓存：WebView 会对同一资源重复请求（预加载 + 实际加载、
 * 重载、SPA 路由），每次 fork 一个 root 子进程代价不低；404 也缓存，避免反复
 * fork 去确认「这个文件不存在」。
 */
class SuFilePathHandler(
    private val webRoot: java.io.File,
    /** true 表示 [webRoot] 是应用自己的缓存副本，直接读文件即可，不需要 root。 */
    private val directRead: Boolean = false,
    private val insetsSupplier: () -> WebInsets,
) : WebViewAssetLoader.PathHandler {
    /** 命中即直接返回；`bytes == null` 表示“确认不存在”。 */
    private class Entry(
        val mime: String,
        val bytes: ByteArray?,
    )

    private val cache = ConcurrentHashMap<String, Entry>()

    override fun handle(path: String): WebResourceResponse {
        // 模块页按约定引用这两条保留路径拿管理器的 CSS
        if (path == "internal/colors.css") {
            return ok("text/css", WebUiCss.vars.toByteArray(Charsets.UTF_8), path)
        }
        if (path == "internal/insets.css") {
            return ok("text/css", insetsSupplier().css.toByteArray(Charsets.UTF_8), path)
        }
        val target = resolveWithinWebRoot(webRoot.path, path)
        if (target == null) {
            Log.w(TAG, "请求越出 webroot：$path")
            return notFound(path)
        }
        val entry = cache.getOrPut(path) { load(target, path) }
        val bytes = entry.bytes
        if (bytes == null) {
            Log.w(TAG, "root 读文件失败：$path")
            return notFound(path)
        }
        return ok(entry.mime, bytes, path)
    }

    /**
     * 一次读取 + MIME 猜测。缓存块在 ConcurrentHashMap 的锁外执行，
     * 首屏的多个子资源仍能并行读取。
     */
    private fun load(
        targetPath: String,
        path: String,
    ): Entry {
        val mime = guessMimeType(path)
        val raw =
            runCatching { readAll(targetPath, path) }
                .onFailure { Log.e(TAG, "处理请求失败：$path", it) }
                .getOrNull()
        val bytes =
            when {
                // 0 字节按不存在处理：`su -c cat` 读不到文件时给的是空流而不是失败
                raw == null || raw.isEmpty() -> null
                mime == "text/html" -> injectWebUiShim(String(raw, Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
                else -> raw
            }
        return Entry(mime, bytes)
    }

    private fun readAll(
        targetPath: String,
        path: String,
    ): ByteArray? {
        // 缓存副本：应用自己可读，普通文件读取，不碰 su / 提权
        if (directRead) {
            val direct = runCatching { java.io.File(targetPath).readBytes() }.getOrNull()
            if (direct != null) return decompressIfSvgz(direct, path)
        }
        // 原生读取：内核在线时子进程 setresuid 临时提权，比每次 fork 一个
        // `su -c cat`（多一次 su 二进制 exec + shell 解析 + SELinux 域迁移）便宜一个量级。
        // null（内核不在线 / 不是 manager / 文件超上限）回退到 su 流式读取。
        NekoNative.readRootFile(targetPath)?.let { return decompressIfSvgz(it, path) }
        val stream: InputStream =
            RootShell.openSuReadStream("cat -- '${quote(targetPath)}'") ?: return null
        val raw = stream.use { it.readBytes() }
        return decompressIfSvgz(raw, path)
    }

    /** `.svgz` 是 gzip 压缩的 svg，解压后再交给 WebView；解不开按 404 处理。 */
    private fun decompressIfSvgz(
        bytes: ByteArray,
        path: String,
    ): ByteArray? {
        if (!path.endsWith(".svgz")) return bytes
        return runCatching { GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() } }
            .getOrNull()
    }

    private fun ok(
        mime: String,
        bytes: ByteArray,
        path: String,
    ): WebResourceResponse {
        Log.d(TAG, "serve $path ${bytes.size}B ($mime)")
        return WebResourceResponse(
            mime,
            null,
            200,
            "OK",
            WEBUI_RESPONSE_HEADERS,
            ByteArrayInputStream(bytes),
        )
    }

    /**
     * 404 时返回一段可见的 HTML（而不是空白），这样即使不抓 logcat，
     * 页面也会直接显示是哪个文件没读到、webroot 是什么。
     */
    private fun notFound(path: String): WebResourceResponse {
        val html =
            "<!doctype html><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                "<body style=\"font-family:monospace;padding:16px;line-height:1.6\">" +
                "<h3>NekoSU WebUI</h3><p>404: ${escapeHtml(path.ifBlank { "index.html" })}</p>" +
                "<p>webroot: ${escapeHtml(webRoot.absolutePath)}</p>" +
                "<p>请确认模块存在 webroot/index.html，且管理器已获得 root。</p></body>"
        return WebResourceResponse(
            "text/html",
            "utf-8",
            404,
            "Not Found",
            WEBUI_RESPONSE_HEADERS,
            ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)),
        )
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

    private fun escapeHtml(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
