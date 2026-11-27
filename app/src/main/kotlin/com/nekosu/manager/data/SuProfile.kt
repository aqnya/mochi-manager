package com.nekosu.manager.data

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import com.nekosu.manager.neko.NekoKernel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个 uid（而不是一个包）的超级用户配置，字段对齐 KernelSU 的 AppProfile：
 * 是否放行、以什么身份运行（uid/gid/附加组）、能力位、命名空间、SELinux 域、是否卸载模块。
 *
 * 主键是 [appUid]：KernelSU 内核侧按 uid 存 profile，共享 uid 的多个包天然共用一份配置。
 * [packageName] 只保存组里的代表包名，用于图标、名称与调试输出。
 *
 * 工程还没有 root 数据层，这里只做配置的本地存储与界面呈现，不会真正影响 su。
 */
data class SuProfile(
    val packageName: String,
    val appUid: Int,
    val allowSu: Boolean = false,
    val runAsUid: Int = 0,
    val runAsGid: Int = 0,
    val groups: Set<Int> = setOf(GROUP_ROOT),
    val capabilities: Set<String> = emptySet(),
    val namespace: Int = NAMESPACE_INHERIT,
    val umountModules: Boolean = false,
    val context: String = DEFAULT_CONTEXT,
) {
    /** 与默认值不同就算「自定义配置」，用于列表的 CUSTOM 标签与分组。 */
    val isCustom: Boolean
        get() =
            runAsUid != 0 || runAsGid != 0 || groups != setOf(GROUP_ROOT) || capabilities.isNotEmpty() ||
                namespace != NAMESPACE_INHERIT || umountModules || context != DEFAULT_CONTEXT

    fun reset(): SuProfile = SuProfile(packageName, appUid)

    companion object {
        const val GROUP_ROOT = 0
        const val NAMESPACE_INHERIT = 0
        const val NAMESPACE_GLOBAL = 1
        const val NAMESPACE_INDIVIDUAL = 2
        const val DEFAULT_CONTEXT = "u:r:su:s0"

        fun default(
            packageName: String,
            appUid: Int,
        ) = SuProfile(packageName, appUid)
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
    /** Linux capability 编号；内核 profile 的 caps 就是 `1L shl bit` 的按位或。 */
    val bit: Int,
)

val SuCapabilities =
    listOf(
        SuCapability("CAP_DAC_OVERRIDE", "绕过文件读写权限检查", 1),
        SuCapability("CAP_DAC_READ_SEARCH", "绕过文件与目录的读权限检查", 2),
        SuCapability("CAP_SYS_ADMIN", "系统管理（挂载、命名空间等）", 21),
        SuCapability("CAP_SYS_PTRACE", "调试与读写其他进程内存", 19),
        SuCapability("CAP_NET_ADMIN", "网络接口与路由配置", 12),
        SuCapability("CAP_NET_RAW", "原始套接字", 13),
        SuCapability("CAP_SETUID", "切换用户 ID", 7),
        SuCapability("CAP_SETGID", "切换用户组 ID", 6),
    )

/** 能力名集合 → 内核 caps 位（未知名字忽略）。 */
fun capsMask(names: Set<String>): Long =
    names.fold(0L) { mask, name ->
        val bit = SuCapabilities.firstOrNull { it.name == name }?.bit
        if (bit == null) mask else mask or (1L shl bit)
    }

/** 内核 caps 位 → 能力名集合（只保留界面能展示的那几位）。 */
fun capsFromMask(mask: Long): Set<String> =
    SuCapabilities.filterTo(mutableSetOf()) { mask and (1L shl it.bit) != 0L }.map { it.name }.toSet()


/**
 * 配置的本地存储，按 **应用 uid** 索引。
 *
 * 旧版本的 key 是包名，[init] 会把这批数据迁移到 uid（用 PackageManager 反查），
 * 这样共享 uid 的应用不必各自保存一份、也不会出现组内状态不一致。
 */
object SuProfileStore {
    private const val PREFS = "neko_su_profiles"
    private const val KEY_PROFILES = "profiles"

    private var prefs: SharedPreferences? = null
    private val _profiles = MutableStateFlow<Map<Int, SuProfile>>(emptyMap())
    val profiles: StateFlow<Map<Int, SuProfile>> = _profiles

    /** 写内核是 IO 操作，不挂在界面协程上（页面退出也要写完）。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val (loaded, migrated) = decode(p.getString(KEY_PROFILES, null), context.packageManager)
        _profiles.value = loaded
        if (migrated) p.edit().putString(KEY_PROFILES, encode(loaded)).apply()
    }

    /**
     * 用内核 profile 表覆盖授权状态。
     *
     * 内核是唯一事实来源：表里有这个 uid 才算授权，能力位/命名空间/域也从这里读。
     * 没有 NekoSU 内核模块时直接返回，保留本地数据（纯本地预览）。
     */
    suspend fun refreshFromKernel(context: Context) {
        init(context)
        val kernel = NekoKernel.profiles() ?: return
        val pm = context.packageManager
        val next = _profiles.value.toMutableMap()
        val allowed = kernel.mapTo(mutableSetOf()) { it.uid }

        next.replaceAll { _, profile ->
            if (profile.appUid in allowed) profile else profile.copy(allowSu = false)
        }
        for (entry in kernel) {
            val existing = next[entry.uid]
            val pkg =
                existing?.packageName?.takeIf { it.isNotBlank() }
                    ?: runCatching { pm.getPackagesForUid(entry.uid)?.firstOrNull() }.getOrNull()
                    ?: "uid ${entry.uid}"
            next[entry.uid] =
                (existing ?: SuProfile(pkg, entry.uid)).copy(
                    packageName = pkg,
                    allowSu = true,
                    capabilities = capsFromMask(entry.caps),
                    namespace = entry.namespace,
                    context = entry.domain,
                )
        }
        _profiles.value = next
        persist(next)
    }

    fun get(
        context: Context,
        uid: Int,
    ): SuProfile? {
        init(context)
        return _profiles.value[uid]
    }

    fun save(
        context: Context,
        profile: SuProfile,
    ) {
        init(context)
        val next = _profiles.value.toMutableMap().apply { put(profile.appUid, profile) }
        _profiles.value = next
        persist(next)
        writeKernel(profile)
    }

    fun reset(
        context: Context,
        uid: Int,
    ) {
        init(context)
        val next = _profiles.value.toMutableMap().apply { remove(uid) }
        _profiles.value = next
        persist(next)
        revokeKernel(uid)
    }

    private fun persist(profiles: Map<Int, SuProfile>) {
        prefs?.edit()?.putString(KEY_PROFILES, encode(profiles))?.apply()
    }

    /** 授权开关 + profile 一起写。内核不可用时什么也不做。 */
    private fun writeKernel(profile: SuProfile) {
        if (!NekoKernel.available) return
        scope.launch {
            val ok = NekoKernel.setAllowed(profile.appUid, profile.allowSu)
            if (!ok || !profile.allowSu) return@launch
            NekoKernel.setProfile(
                uid = profile.appUid,
                caps = capsMask(profile.capabilities),
                domain = profile.context,
                namespace = profile.namespace,
                rootUid = profile.runAsUid,
            )
        }
    }

    private fun revokeKernel(uid: Int) {
        if (!NekoKernel.available) return
        scope.launch { NekoKernel.setAllowed(uid, false) }
    }

    private fun encode(profiles: Map<Int, SuProfile>): String =
        JSONArray().apply {
            profiles.values.forEach { p ->
                put(
                    JSONObject().apply {
                        put("appUid", p.appUid)
                        put("pkg", p.packageName)
                        put("allowSu", p.allowSu)
                        put("runAsUid", p.runAsUid)
                        put("runAsGid", p.runAsGid)
                        put("groups", JSONArray(p.groups.toList()))
                        put("caps", JSONArray(p.capabilities.toList()))
                        put("namespace", p.namespace)
                        put("umount", p.umountModules)
                        put("context", p.context)
                    },
                )
            }
        }.toString()

    /** @return 解析结果 + 是否需要回写（有旧格式被迁移时）。 */
    private fun decode(
        raw: String?,
        pm: PackageManager,
    ): Pair<Map<Int, SuProfile>, Boolean> {
        if (raw.isNullOrBlank()) return emptyMap<Int, SuProfile>() to false
        var migrated = false
        val result =
            runCatching {
                val array = JSONArray(raw)
                buildMap {
                    for (i in 0 until array.length()) {
                        val o = array.getJSONObject(i)
                        val pkg = o.optString("pkg")
                        if (pkg.isBlank()) continue
                        var appUid = o.optInt("appUid", 0)
                        if (appUid == 0) {
                            // 旧格式（按包名存储）：反查这个包的 uid，查不到就丢掉这条
                            appUid = packageUid(pm, pkg)
                            if (appUid == 0) continue
                            migrated = true
                        }
                        // 新 key 优先；旧字段 uid/gid 表示运行身份
                        val runAsUid = o.optInt("runAsUid", o.optInt("uid", 0))
                        val runAsGid = o.optInt("runAsGid", o.optInt("gid", 0))
                        put(
                            appUid,
                            SuProfile(
                                packageName = pkg,
                                appUid = appUid,
                                allowSu = o.optBoolean("allowSu", false),
                                runAsUid = runAsUid,
                                runAsGid = runAsGid,
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
        return result to migrated
    }

    @Suppress("DEPRECATION")
    private fun packageUid(
        pm: PackageManager,
        packageName: String,
    ): Int = runCatching { pm.getPackageUid(packageName, 0) }.getOrDefault(0)

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
