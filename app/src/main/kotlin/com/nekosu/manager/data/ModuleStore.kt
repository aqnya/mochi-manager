package com.nekosu.manager.data

import android.util.Log
import com.nekosu.manager.neko.Ncore
import org.json.JSONArray
import org.json.JSONObject

/**
 * 已安装模块的真实数据源。
 *
 * 数据来自 ncore 的 `module list --json`（neko 包里模块运行时的输出），字段与
 * `module.prop` 一致；开关、卸载、撤销卸载全部转给 ncore 的 CLI，界面不再维护本地假状态。
 *
 * ncore 还没被内核引导起来（没 root、内核没装、或设备不是 arm64）时 [load] 返回 null，
 * 让界面显示空态而不是一份假的模块列表。
 */
object ModuleStore {
    private const val TAG = "NekoSU/Modules"

    suspend fun load(): List<ModuleEntry>? {
        // 不前置判断「ncore 是否部署过」：任何本地记录都只是历史，文件被删/被替换时会撒谎。
        val result =
            runCatching { Ncore.moduleListJson() }
                .onFailure { Log.w(TAG, "ncore exec failed", it) }
                .getOrNull()
                ?: return null
        if (result.exitCode != 0) {
            Log.w(TAG, "module list failed exit=${result.exitCode} out=${result.output.take(200)}")
            return null
        }
        return runCatching { parse(result.output) }
            .onFailure { Log.w(TAG, "parse module list failed", it) }
            .getOrNull()
    }

    suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    ): Boolean {
        val result = if (enabled) Ncore.moduleEnable(id) else Ncore.moduleDisable(id)
        return result.exitCode == 0
    }

    suspend fun uninstall(id: String): Boolean = Ncore.moduleUninstall(id).exitCode == 0

    suspend fun undoUninstall(id: String): Boolean = Ncore.moduleUndoUninstall(id).exitCode == 0

    suspend fun runAction(id: String): Boolean = Ncore.moduleAction(id).exitCode == 0

    /** 解析 `module list --json`。字段缺失时按「无」处理，不让一条坏数据丢掉整张表。 */
    internal fun parse(raw: String): List<ModuleEntry> {
        // 正常情况 stdout 是纯 JSON，但 RootShell 合并了 stderr：su 或内核在异常路径上
        // 可能往同一管道写一行提示。只取最外层 [...]，一行噪音不该把整张表打成空态。
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        require(start >= 0 && end > start) { "不是 JSON 数组：${raw.take(120)}" }
        val array = JSONArray(raw.substring(start, end + 1))
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.text("id")
                if (id.isBlank()) continue
                add(
                    ModuleEntry(
                        id = id,
                        name = item.text("name").ifBlank { id },
                        version = item.text("version"),
                        author = item.text("author"),
                        description = item.text("description"),
                        enabled = item.flag("enabled"),
                        remove = item.flag("remove"),
                        metaModule = item.flag("metamodule"),
                        hasAction = item.flag("action"),
                        hasWebUi = item.flag("web") || item.flag("hasWebUi"),
                        hasUpdate = item.flag("update"),
                        versionCode = item.optLong("versionCode"),
                        updateJson = item.text("updateJson"),
                    ),
                )
            }
        }
    }

    /** module.prop 的值都是字符串，这里兼容布尔与字符串两种写法。 */
    private fun JSONObject.text(key: String): String {
        if (isNull(key)) return ""
        return when (val value = opt(key)) {
            is String -> value
            null -> ""
            else -> value.toString()
        }
    }

    private fun JSONObject.flag(key: String): Boolean =
        when (val value = opt(key)) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true)
            else -> false
        }
}
