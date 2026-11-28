package com.nekosu.manager.ui.webui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * WebUI 文件服务的路径校验。
 *
 * 关键回归：校验绝不能碰文件系统——应用进程对 /data/adb 没有搜索权限，
 * 一旦用 `File.canonicalFile` / `isFile`，真实设备上所有请求都会被误判越界，
 * 页面直接 ERR_INVALID_RESPONSE。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class SuFilePathHandlerTest {
    private val root = "/data/adb/modules/demo/webroot"

    @Test
    fun `普通相对路径解析到 webroot 内`() {
        assertEquals("$root/index.html", resolveWithinWebRoot(root, "index.html"))
        assertEquals("$root/assets/app.js", resolveWithinWebRoot(root, "assets/app.js"))
    }

    @Test
    fun `在 webroot 内来回走的相对路径放行`() {
        assertEquals("$root/index.html", resolveWithinWebRoot(root, "assets/../index.html"))
    }

    @Test
    fun `越出 webroot 的路径拒绝`() {
        assertNull(resolveWithinWebRoot(root, "../etc/passwd"))
        assertNull(resolveWithinWebRoot(root, "assets/../../passwd"))
    }

    @Test
    fun `绝对路径拒绝`() {
        assertNull(resolveWithinWebRoot(root, "/etc/passwd"))
        assertNull(resolveWithinWebRoot(root, "//data/data/x"))
    }

    @Test
    fun `空路径与目录本身拒绝`() {
        assertNull(resolveWithinWebRoot(root, ""))
        assertNull(resolveWithinWebRoot(root, "."))
    }

    @Test
    fun `webroot 带尾斜杠也正常`() {
        assertEquals("$root/index.html", resolveWithinWebRoot("$root/", "index.html"))
    }

    /**
     * 关键回归：WebView 不把 `shouldInterceptRequest` 返回的响应当作同源可信响应，
     * 带 crossorigin 的 CSS（Vite 预加载）缺 CORS 头就会报
     * `Unable to preload CSS`，模块页直接白屏。
     */
    @Test
    fun `拦截响应必须带 CORS 头`() {
        assertEquals("*", WEBUI_RESPONSE_HEADERS["Access-Control-Allow-Origin"])

        val handler = SuFilePathHandler(java.io.File(root)) { WebInsets(0, 0, 0, 0) }
        val css = handler.handle("internal/colors.css")
        assertEquals("text/css", css.mimeType)
        assertEquals("*", css.responseHeaders?.get("Access-Control-Allow-Origin"))
    }
}
