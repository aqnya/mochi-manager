package com.nekosu.manager.data

import android.content.Context
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
 * ncore 还没落地（没 root、没跑过部署、或设备不是 arm64）时 [load] 返回 null，
 * 让界面显示空态而不是一份假的模块列表。
 */
object ModuleStore {
    private const val TAG = "NekoSU/Modules"

    suspend fun load(context: Context): List<ModuleEntry>? {
        if (!Ncore.isDeployed(context)) {
            Log.i(TAG, "ncore 未部署，模块列表不可用")
            return null
        }
        val result =
            runCatching { Ncore.moduleListJson() }
                .onFailure { Log.w(TAG, "执行 ncore 失败：${it.message}") }
                .getOrNull()
                ?: return null
        if (result.exitCode != 0) {
            Log.w(TAG, "module list 退出码 ${result.exitCode}：${result.output.take(200)}")
            return null
        }
        return runCatching { parse(result.output) }
            .onFailure { Log.w(TAG, "解析模块列表失败：${it.message}") }
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
    internal fun parse(json: String): List<ModuleEntry> {
        val array = JSONArray(json)
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
