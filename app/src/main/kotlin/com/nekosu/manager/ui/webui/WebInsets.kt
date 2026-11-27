package com.nekosu.manager.ui.webui

/**
 * 给模块 WebUI 的安全区数据（参考 MMRL/WebUI-X 的约定）。
 *
 * 模块页想避开的刘海、状态栏和导航栏通过 CSS 变量下发；只在 JS 调
 * `ksu.fullScreen(true)` 进入全屏后才生效——普通页面 Compose 的 safeDrawing 已经排好版，
 * 再叠一份变量反而会把页面顶下来。
 */
data class WebInsets(
    val top: Int,
    val bottom: Int,
    val left: Int,
    val right: Int,
) {
    /** `internal/insets.css` 的内容，模块页通过 <link> 引进来。 */
    val css: String
        get() = buildString {
            appendLine(":root {")
            appendLine("\t--safe-area-inset-top: ${top}px;")
            appendLine("\t--safe-area-inset-right: ${right}px;")
            appendLine("\t--safe-area-inset-bottom: ${bottom}px;")
            appendLine("\t--safe-area-inset-left: ${left}px;")
            appendLine("\t--window-inset-top: var(--safe-area-inset-top, 0px);")
            appendLine("\t--window-inset-bottom: var(--safe-area-inset-bottom, 0px);")
            appendLine("\t--window-inset-left: var(--safe-area-inset-left, 0px);")
            appendLine("\t--window-inset-right: var(--safe-area-inset-right, 0px);")
            appendLine("\t--f7-safe-area-top: var(--window-inset-top, 0px) !important;")
            appendLine("\t--f7-safe-area-bottom: var(--window-inset-bottom, 0px) !important;")
            appendLine("\t--f7-safe-area-left: var(--window-inset-left, 0px) !important;")
            appendLine("\t--f7-safe-area-right: var(--window-inset-right, 0px) !important;")
            append("}")
        }

    /** 已经加载后的页面不用刷新，直接把变量写进 documentElement。 */
    val js: String
        get() = buildString {
            append("(function() {")
            append(" var s = document.documentElement.style;")
            append(" s.setProperty('--safe-area-inset-top', '${top}px');")
            append(" s.setProperty('--safe-area-inset-right', '${right}px');")
            append(" s.setProperty('--safe-area-inset-bottom', '${bottom}px');")
            append(" s.setProperty('--safe-area-inset-left', '${left}px');")
            append("})();")
        }
}
