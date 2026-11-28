package com.nekosu.manager.data

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.util.Log
import com.nekosu.manager.neko.NekoKernel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "NekoSU/SuProfile"

/**
 * SELinux 域：`user:role:type[:range]`。range 本身可以带冒号与点，
 * 例如 `s0:c512,c768` / `s0:c0.c1023`，这段要一起放行。
 */
private val SELINUX_CONTEXT_REGEX =
    Regex("^[a-z_][a-z0-9_]*:[a-z_][a-z0-9_]*:[a-z_][a-z0-9_]*(:[a-zA-Z0-9_:,.\\-]+)?$")

internal fun isValidSelinuxContext(value: String): Boolean = SELINUX_CONTEXT_REGEX.matches(value)

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
    val capabilities: Set<String> = DEFAULT_CAPABILITIES,
    val namespace: Int = NAMESPACE_INHERIT,
    val umountModules: Boolean = false,
    val context: String = DEFAULT_CONTEXT,
) {
    /** 与默认值不同就算「自定义配置」，用于列表的 CUSTOM 标签与分组。 */
    val isCustom: Boolean
        get() =
            runAsUid != 0 || runAsGid != 0 || groups != setOf(GROUP_ROOT) ||
                capabilities != DEFAULT_CAPABILITIES ||
                namespace != NAMESPACE_INHERIT || umountModules || context != DEFAULT_CONTEXT

    fun reset(): SuProfile = SuProfile(packageName, appUid)

    companion object {
        const val GROUP_ROOT = 0
        const val NAMESPACE_INHERIT = 0
        const val NAMESPACE_GLOBAL = 1
        const val NAMESPACE_INDIVIDUAL = 2
        const val DEFAULT_CONTEXT = "u:r:nksu:s0"

        /**
         * 新授权的默认能力位，与上游 NekoSU 的 `DEFAULT_CAPS` 一致。
         *
         * 空集不是「安全」而是「不能用」：以 uid 0 身份运行但 permitted/effective 全空，
         * 连 mount、ptrace、改文件属主这类基本操作都会被拒。这 8 项覆盖
         * 「正常当一个 root shell 用」所需要的范围，其余高危项（SYS_MODULE、
         * SYS_RAWIO、SYS_TIME、MAC_ADMIN 等）仍要用户手动勾选。
         */
        val DEFAULT_CAPABILITIES: Set<String> =
            linkedSetOf(
                "CAP_CHOWN",
                "CAP_DAC_OVERRIDE",
                "CAP_DAC_READ_SEARCH",
                "CAP_FOWNER",
                "CAP_SETUID",
                "CAP_SETGID",
                "CAP_SYS_ADMIN",
                "CAP_SYS_PTRACE",
            )

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
    /** 界面分组：全部 41 项平铺难找，按文件系统/进程/网络等归类展示。 */
    val category: String,
)

/**
 * 全部 Linux capability（对齐内核 `include/uapi/linux/capability.h`，
 * CAP_LAST_CAP = CAP_CHECKPOINT_RESTORE = 40）。
 *
 * 位编号与内核保持一致，`capsMask`/`capsFromMask` 直接按位映射，
 * 不会漏掉 nor 报错——未知名字双向都安全。
 */
val SuCapabilities =
    listOf(
        SuCapability("CAP_CHOWN", "修改文件属主与属组", 0, "文件系统"),
        SuCapability("CAP_DAC_OVERRIDE", "绕过文件读写执行权限检查", 1, "文件系统"),
        SuCapability("CAP_DAC_READ_SEARCH", "绕过文件与目录读权限检查", 2, "文件系统"),
        SuCapability("CAP_FOWNER", "绕过文件属主身份检查", 3, "文件系统"),
        SuCapability("CAP_FSETID", "设置文件的 setuid/setgid 位", 4, "文件系统"),
        SuCapability("CAP_LINUX_IMMUTABLE", "设置文件不可变属性", 9, "文件系统"),
        SuCapability("CAP_MKNOD", "创建设备文件", 27, "文件系统"),
        SuCapability("CAP_LEASE", "建立文件租借", 28, "文件系统"),
        SuCapability("CAP_SETFCAP", "设置文件能力", 31, "文件系统"),
        SuCapability("CAP_KILL", "向任意进程发送信号", 5, "进程"),
        SuCapability("CAP_SETGID", "切换用户组 ID", 6, "进程"),
        SuCapability("CAP_SETUID", "切换用户 ID", 7, "进程"),
        SuCapability("CAP_SETPCAP", "授予/收回其他进程能力", 8, "进程"),
        SuCapability("CAP_SYS_PTRACE", "调试与读写其他进程内存", 19, "进程"),
        SuCapability("CAP_SYS_PACCT", "进程记账", 20, "进程"),
        SuCapability("CAP_SYS_NICE", "提升进程优先级", 23, "进程"),
        SuCapability("CAP_WAKE_ALARM", "触发唤醒系统的闹钟", 35, "进程"),
        SuCapability("CAP_CHECKPOINT_RESTORE", "进程快照与恢复（CRIU）", 40, "进程"),
        SuCapability("CAP_NET_BIND_SERVICE", "绑定 1024 以下特权端口", 10, "网络"),
        SuCapability("CAP_NET_BROADCAST", "网络广播与组播监听", 11, "网络"),
        SuCapability("CAP_NET_ADMIN", "网络接口、路由与防火墙配置", 12, "网络"),
        SuCapability("CAP_NET_RAW", "使用原始套接字", 13, "网络"),
        SuCapability("CAP_IPC_LOCK", "锁定内存（防换出）", 14, "IPC 与资源"),
        SuCapability("CAP_IPC_OWNER", "绕过 IPC 对象属主检查", 15, "IPC 与资源"),
        SuCapability("CAP_SYS_RESOURCE", "突破系统资源限制", 24, "IPC 与资源"),
        SuCapability("CAP_BLOCK_SUSPEND", "阻止系统休眠", 36, "IPC 与资源"),
        SuCapability("CAP_SYS_MODULE", "加载与卸载内核模块", 16, "系统与内核"),
        SuCapability("CAP_SYS_RAWIO", "原始 I/O（/dev/mem、裸块设备）", 17, "系统与内核"),
        SuCapability("CAP_SYS_CHROOT", "使用 chroot", 18, "系统与内核"),
        SuCapability("CAP_SYS_ADMIN", "系统管理（挂载、命名空间等）", 21, "系统与内核"),
        SuCapability("CAP_SYS_BOOT", "重启系统", 22, "系统与内核"),
        SuCapability("CAP_SYS_TIME", "设置系统时钟", 25, "系统与内核"),
        SuCapability("CAP_SYS_TTY_CONFIG", "配置 TTY 设备", 26, "系统与内核"),
        SuCapability("CAP_SYSLOG", "读取内核日志", 34, "系统与内核"),
        SuCapability("CAP_PERFMON", "性能监控（perf 事件）", 38, "系统与内核"),
        SuCapability("CAP_BPF", "创建与管理 BPF 程序", 39, "系统与内核"),
        SuCapability("CAP_AUDIT_WRITE", "写入内核审计日志", 29, "安全与审计"),
        SuCapability("CAP_AUDIT_CONTROL", "配置内核审计", 30, "安全与审计"),
        SuCapability("CAP_MAC_OVERRIDE", "绕过强制访问控制（SELinux）检查", 32, "安全与审计"),
        SuCapability("CAP_MAC_ADMIN", "配置强制访问控制策略", 33, "安全与审计"),
        SuCapability("CAP_AUDIT_READ", "读取内核审计日志", 37, "安全与审计"),
    )

/** [SuCapabilities] 的分组顺序，界面按这个顺序出段头。 */
val SuCapabilityCategories = SuCapabilities.map { it.category }.distinct()

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

    /** 内核写入串行化：开关来回点时，落盘顺序必须跟界面最终状态一致。 */
    private val kernelLock = Mutex()

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
        syncKernel(profile)
    }

    fun reset(
        context: Context,
        uid: Int,
    ) {
        init(context)
        val previous = _profiles.value[uid]
        val next = _profiles.value.toMutableMap().apply { remove(uid) }
        _profiles.value = next
        persist(next)
        // 本地条目删掉了，内核表项与落盘条目也要一起清（JNI 的 delUid 一步做完）：
        // 留下任何一份，重启后 ncore 都会把它恢复成「已授权」。
        syncKernel((previous ?: SuProfile("uid $uid", uid)).copy(allowSu = false))
    }

    private fun persist(profiles: Map<Int, SuProfile>) {
        prefs?.edit()?.putString(KEY_PROFILES, encode(profiles))?.apply()
    }

    /**
     * 把一次授权/撤销同步到内核。落盘由 JNI 一并完成（见 [applyKernel]），
     * 这里只负责「什么时候写、按什么顺序写」。
     *
     * 内核写入放在单线程串行的 [kernelLock] 里：用户在开关上来回点时，
     * 内核与落盘的最终状态必须和界面一致，否则「先授权后撤销」可能落成
     * 「先撤销后授权」，重启后又变回已授权。
     */
    private fun syncKernel(profile: SuProfile) {
        if (!NekoKernel.available) return
        scope.launch {
            kernelLock.withLock { applyKernel(profile) }
        }
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
                                // 字段缺失（更早的版本没写 caps）时补默认值；空数组是用户主动清空，保持原样
                                capabilities =
                                    if (o.has("caps")) {
                                        o.optJSONArray("caps").toStringSet()
                                    } else {
                                        SuProfile.DEFAULT_CAPABILITIES
                                    },
                                namespace = o.optInt("namespace", SuProfile.NAMESPACE_INHERIT),
                                umountModules = o.optBoolean("umount", false),
                                // 旧版本可能把 root_uid（0）误存成 domain，这种脏值会让
                                // 内核拒绝 setcon，直接回退默认值，避免坏配置继续扩散。
                                context =
                                    o.optString("context", SuProfile.DEFAULT_CONTEXT).let { saved ->
                                        saved.takeIf { isValidSelinuxContext(it) } ?: SuProfile.DEFAULT_CONTEXT
                                    },
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

/**
 * 内核侧的两个动作。落盘不在这里——它和内核写一起封装在 JNI 里（对齐上游
 * `adduid` / `deluid` / `setProfileProto`），管理器只需要这两步。
 *
 * 抽成接口是为了让「撤销只调一次、授权按固定顺序」能被单测覆盖。
 */
internal interface KernelOps {
    fun setAllowed(
        uid: Int,
        allow: Boolean,
    ): Boolean

    fun setProfile(
        uid: Int,
        caps: Long,
        domain: String,
        ns: Int,
        rootUid: Int,
    ): Boolean
}

/** [KernelOps] 的生产实现，直接转给 [NekoKernel]。 */
internal object NekoKernelOps : KernelOps {
    override fun setAllowed(
        uid: Int,
        allow: Boolean,
    ): Boolean = NekoKernel.setAllowed(uid, allow)

    override fun setProfile(
        uid: Int,
        caps: Long,
        domain: String,
        ns: Int,
        rootUid: Int,
    ): Boolean = NekoKernel.setProfile(uid, caps, domain, ns, rootUid)
}

/**
 * 把一次授权/撤销落到内核。
 *
 * 落盘（`/data/adb/nksu/profile`）不在这一层做：JNI 的 `addUid` / `delUid` /
 * `setProfile` 已经把「内核表项 + 落盘」封装成一次调用，与上游 NekoSU 一致。
 * 管理器这边只要顺序对：
 *
 *  - 授权：先建表项（内核顺带同步路径规则），再写 profile（能力位/域/命名空间/root uid，
 *    这一步同时落盘）；
 *  - 撤销：一次 `setAllowed(uid, false)` 就够了，内核表项与落盘条目一起删。
 */
internal fun applyKernel(
    profile: SuProfile,
    kernel: KernelOps = NekoKernelOps,
) {
    val uid = profile.appUid
    if (profile.allowSu) {
        if (!kernel.setAllowed(uid, true)) Log.w(TAG, "set allowed failed uid=$uid allow=true")
        val caps = capsMask(profile.capabilities)
        // 最后一道防线：任何路径漏进来的非法域（例如旧版误存的 "0"）都不能写进内核，
        // 否则 setcon 失败会让整个授权流程异常。
        val domain = profile.context.takeIf { isValidSelinuxContext(it) } ?: SuProfile.DEFAULT_CONTEXT
        if (!kernel.setProfile(uid, caps, domain, profile.namespace, profile.runAsUid)) {
            Log.w(TAG, "persist profile failed uid=$uid")
        }
        return
    }
    if (!kernel.setAllowed(uid, false)) Log.w(TAG, "revoke failed uid=$uid")
}
