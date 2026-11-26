package com.nekosu.manager.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 单个应用的超级用户配置，字段对齐 KernelSU 的 AppProfile：
 * 是否放行、以什么身份运行（uid/gid/附加组）、能力位、命名空间、SELinux 域、是否卸载模块。
 *
 * 工程还没有 root 数据层，这里只做配置的本地存储与界面呈现，不会真正影响 su。
 */
data class SuProfile(
    val packageName: String,
    val allowSu: Boolean = false,
    val uid: Int = 0,
    val gid: Int = 0,
    val groups: Set<Int> = setOf(GROUP_ROOT),
    val capabilities: Set<String> = emptySet(),
    val namespace: Int = NAMESPACE_INHERIT,
    val umountModules: Boolean = false,
    val context: String = DEFAULT_CONTEXT,
) {
    /** 与默认值不同就算「自定义配置」，用于列表的 CUSTOM 标签与分组。 */
    val isCustom: Boolean
        get() =
            uid != 0 || gid != 0 || groups != setOf(GROUP_ROOT) || capabilities.isNotEmpty() ||
                namespace != NAMESPACE_INHERIT || umountModules || context != DEFAULT_CONTEXT

    fun reset(): SuProfile = SuProfile(packageName)

    companion object {
        const val GROUP_ROOT = 0
        const val NAMESPACE_INHERIT = 0
        const val NAMESPACE_GLOBAL = 1
        const val NAMESPACE_INDIVIDUAL = 2
        const val DEFAULT_CONTEXT = "u:r:su:s0"

        fun default(packageName: String) = SuProfile(packageName)
    }
}

/** 附加组的可选项，gid 与 AOSP / KernelSU 的常用组一致。 */
data class SuGroup(
    val gid: Int,
    val name: String,
    val desc: String,
)

val SuGroups =
    listOf(
        SuGroup(0, "root", "超级用户组，等同 uid 0 的全部权限"),
        SuGroup(1000, "system", "系统组"),
        SuGroup(1005, "adb", "ADB 调试组"),
        SuGroup(1015, "sdcard_rw", "外置存储读写"),
        SuGroup(1023, "media_rw", "媒体目录读写"),
        SuGroup(2000, "shell", "shell 组，adb shell 的默认身份"),
        SuGroup(3003, "inet", "网络访问"),
        SuGroup(3004, "net_raw", "原始套接字"),
    )

data class SuCapability(
    val name: String,
    val desc: String,
)

val SuCapabilities =
    listOf(
        SuCapability("CAP_DAC_OVERRIDE", "绕过文件读写权限检查"),
        SuCapability("CAP_DAC_READ_SEARCH", "绕过文件与目录的读权限检查"),
        SuCapability("CAP_SYS_ADMIN", "系统管理（挂载、命名空间等）"),
        SuCapability("CAP_SYS_PTRACE", "调试与读写其他进程内存"),
        SuCapability("CAP_NET_ADMIN", "网络接口与路由配置"),
        SuCapability("CAP_NET_RAW", "原始套接字"),
        SuCapability("CAP_SETUID", "切换用户 ID"),
        SuCapability("CAP_SETGID", "切换用户组 ID"),
    )

/** 配置的本地存储，按包名索引。 */
object SuProfileStore {
    private const val PREFS = "neko_su_profiles"
    private const val KEY_PROFILES = "profiles"

    private var prefs: SharedPreferences? = null
    private val _profiles = MutableStateFlow<Map<String, SuProfile>>(emptyMap())
    val profiles: StateFlow<Map<String, SuProfile>> = _profiles

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _profiles.value = decode(p.getString(KEY_PROFILES, null))
    }

    fun get(
        context: Context,
        packageName: String,
    ): SuProfile {
        init(context)
        return _profiles.value[packageName] ?: SuProfile.default(packageName)
    }

    fun save(
        context: Context,
        profile: SuProfile,
    ) {
        init(context)
        val next = _profiles.value.toMutableMap().apply { put(profile.packageName, profile) }
        _profiles.value = next
        prefs?.edit()?.putString(KEY_PROFILES, encode(next))?.apply()
    }

    fun reset(
        context: Context,
        packageName: String,
    ) {
        init(context)
        val next = _profiles.value.toMutableMap().apply { remove(packageName) }
        _profiles.value = next
        prefs?.edit()?.putString(KEY_PROFILES, encode(next))?.apply()
    }

    private fun encode(profiles: Map<String, SuProfile>): String =
        JSONArray().apply {
            profiles.values.forEach { p ->
                put(
                    JSONObject().apply {
                        put("pkg", p.packageName)
                        put("allowSu", p.allowSu)
                        put("uid", p.uid)
                        put("gid", p.gid)
                        put("groups", JSONArray(p.groups.toList()))
                        put("caps", JSONArray(p.capabilities.toList()))
                        put("namespace", p.namespace)
                        put("umount", p.umountModules)
                        put("context", p.context)
                    },
                )
            }
        }.toString()

    private fun decode(raw: String?): Map<String, SuProfile> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val array = JSONArray(raw)
            buildMap {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    val pkg = o.getString("pkg")
                    put(
                        pkg,
                        SuProfile(
                            packageName = pkg,
                            allowSu = o.optBoolean("allowSu", false),
                            uid = o.optInt("uid", 0),
                            gid = o.optInt("gid", 0),
                            groups = o.optJSONArray("groups").toIntSet(setOf(SuProfile.GROUP_ROOT)),
                            capabilities = o.optJSONArray("caps").toStringSet(),
                            namespace = o.optInt("namespace", SuProfile.NAMESPACE_INHERIT),
                            umountModules = o.optBoolean("umount", false),
                            context = o.optString("context", SuProfile.DEFAULT_CONTEXT),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun JSONArray?.toIntSet(fallback: Set<Int>): Set<Int> {
        if (this == null) return fallback
        val result = buildSet {
            for (i in 0 until length()) add(optInt(i))
        }
        return result.ifEmpty { fallback }
    }

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        return buildSet {
            for (i in 0 until length()) optString(i)?.takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}
