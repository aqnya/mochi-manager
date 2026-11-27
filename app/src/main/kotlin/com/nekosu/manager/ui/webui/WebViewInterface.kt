package com.nekosu.manager.ui.webui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nekosu.manager.data.ModuleStore
import com.nekosu.manager.data.RootShell
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 注入给模块页的 `window.ksu`。
 *
 * 保持 KernelSU WebUI API 的兼容子集：模块页都按这套约定写的，缺一个方法整页就废。
 * 调用发生在 WebView 的 JS 桥线程上，这里可以阻塞；涉及 UI 的一律 post 回主线程。
 * spawn / pm / io / 分块下载这些上游扩展暂未提供，模块页要兼容降级路径（都有）。
 */
class WebViewInterface(
    private val state: WebUIState,
) {
    private val webView get() = state.webView
    private val modDir get() = state.modDir

    /**
     * 同步执行：返回 stdout。
     *
     * RootShell 把 stderr 合并进了输出（管理器的 shell 通道只留一路管道），模块页
     * 要分开拿两路时用回调版本——那里 stdout 给合并输出、stderr 置空，并在注释里写明。
     */
    @JavascriptInterface
    fun exec(cmd: String): String =
        runBlocking {
            RootShell.exec(cmd).output
        }

    @JavascriptInterface
    fun exec(
        cmd: String,
        callbackFunc: String,
    ) {
        exec(cmd, null, callbackFunc)
    }

    /** `options`：{"cwd": "...", "env": {...}}，先 cd / export 再跑命令，回调 (code, stdout, stderr)。 */
    @JavascriptInterface
    fun exec(
        cmd: String,
        options: String?,
        callbackFunc: String,
    ) {
        val finalCommand = buildString {
            val opts = if (options == null) JSONObject() else JSONObject(options)
            opts.optString("cwd").takeIf { it.isNotEmpty() }?.let { append("cd $it; ") }
            opts.optJSONObject("env")?.let { env ->
                for (key in env.keys()) append("export $key=${env.getString(key)}; ")
            }
            append(cmd)
        }
        val result = runBlocking { RootShell.exec(finalCommand) }
        val stdout = result.output
        val stderr = ""
        val jsCode =
            "javascript: (function() { try { ${callbackFunc}(${result.exitCode}, ${
                JSONObject.quote(stdout)
            }, ${JSONObject.quote(stderr)}); } catch(e) { console.error(e); } })();"
        webView?.post { webView?.loadUrl(jsCode) }
    }

    @JavascriptInterface
    fun toast(msg: String) {
        webView?.post {
            webView?.let { Toast.makeText(it.context, msg, Toast.LENGTH_SHORT).show() }
        }
    }

    /** 全屏：隐藏系统栏并开启 insets 模式（`internal/insets.css` 开始生效）。 */
    @JavascriptInterface
    fun fullScreen(enable: Boolean) {
        val context = webView?.context
        if (context is Activity) {
            Handler(Looper.getMainLooper()).post {
                if (enable) hideSystemUI(context.window) else showSystemUI(context.window)
            }
        }
        enableEdgeToEdge(enable)
    }

    @JavascriptInterface
    fun enableEdgeToEdge(enable: Boolean) {
        state.isInsetsEnabled = enable
    }

    /** 当前模块的信息 + moduleDir，模块页靠它知道自己装在哪。 */
    @JavascriptInterface
    fun moduleInfo(): String =
        runBlocking {
            val current = JSONObject()
            current.put("moduleDir", modDir)
            val moduleId = File(modDir).name
            val entry =
                runCatching { ModuleStore.load() }.getOrNull()
                    ?.firstOrNull { it.id == moduleId }
            if (entry != null) {
                current.put("id", entry.id)
                current.put("name", entry.name)
                current.put("version", entry.version)
                current.put("versionCode", entry.versionCode)
                current.put("author", entry.author)
                current.put("description", entry.description)
                current.put("enabled", entry.enabled)
                current.put("action", entry.hasAction)
                current.put("web", entry.hasWebUi)
                current.put("updateJson", entry.updateJson)
            }
            current.toString()
        }

    /** 上游的兼容接口：返回模块 id 列表。 */
    @JavascriptInterface
    fun listModules(): String {
        val entries = runBlocking { runCatching { ModuleStore.load() }.getOrNull().orEmpty() }
        val array = JSONArray()
        for (entry in entries) {
            val obj = JSONObject()
            obj.put("id", entry.id)
            obj.put("name", entry.name)
            obj.put("version", entry.version)
            obj.put("versionCode", entry.versionCode)
            obj.put("author", entry.author)
            obj.put("description", entry.description)
            obj.put("enabled", entry.enabled)
            array.put(obj)
        }
        return array.toString()
    }

    @JavascriptInterface
    fun exit() {
        state.requestExit()
    }

    fun destroy() {
        // 目前没有需要注销的资源；保留以对齐上游的生命周期约定
    }
}

fun hideSystemUI(window: android.view.Window) =
    WindowInsetsControllerCompat(window, window.decorView).let { controller ->
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

fun showSystemUI(window: android.view.Window) =
    WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
