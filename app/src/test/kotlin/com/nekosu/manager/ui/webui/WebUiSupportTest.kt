package com.nekosu.manager.ui.webui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebUI 静态资源处理的纯逻辑部分：MIME 猜测与安全区 CSS。
 *
 * 这两块是从上游 NekoSU 直接移植的，行为要和 KernelSU 一致——
 * 模块网页按 `internal/insets.css` 里的变量名排版，猜错 MIME 则浏览器不执行脚本。
 */
class WebUiSupportTest {
    @Test
    fun `常见前端资源按内置表给出 MIME`() {
        assertEquals("text/html", MimeUtil.guess("index.html"))
        assertEquals("application/javascript", MimeUtil.guess("assets/index-abc.js"))
        assertEquals("application/javascript", MimeUtil.guess("chunk.mjs"))
        assertEquals("text/css", MimeUtil.guess("ASSETS/App.CSS"))
        assertEquals("application/json", MimeUtil.guess("manifest.json"))
        assertEquals("image/svg+xml", MimeUtil.guess("icon.svg"))
        assertEquals("font/woff2", MimeUtil.guess("font.woff2"))
        assertEquals("application/wasm", MimeUtil.guess("module.wasm"))
    }

    @Test
    fun `未知扩展名退回 text-plain`() {
        assertEquals("text/plain", MimeUtil.guess("noext"))
        assertEquals("text/plain", MimeUtil.guess("file.unknownext"))
    }

    @Test
    fun `安全区 CSS 变量名与 KernelSU 一致`() {
        val css = Insets(top = 10, bottom = 20, left = 30, right = 40).css
        assertTrue(css.contains("--safe-area-inset-top: 10px;"))
        assertTrue(css.contains("--safe-area-inset-right: 40px;"))
        assertTrue(css.contains("--safe-area-inset-bottom: 20px;"))
        assertTrue(css.contains("--safe-area-inset-left: 30px;"))
        assertTrue(css.contains("--window-inset-top"))
    }

    @Test
    fun `运行时注入的 JS 写入同一组变量`() {
        val js = Insets(top = 1, bottom = 2, left = 3, right = 4).js
        assertTrue(js.contains("setProperty('--safe-area-inset-top', '1px')"))
        assertTrue(js.contains("setProperty('--safe-area-inset-right', '4px')"))
        assertTrue(js.startsWith("(function() {"))
    }
}
