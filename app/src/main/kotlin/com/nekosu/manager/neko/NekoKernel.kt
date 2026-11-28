package com.nekosu.manager.neko

import android.util.Log
import com.nekosu.manager.data.SuProfile

/**
 * 内核侧的能力查询与授权操作。
 *
 * 这一层只做「和内核打交道」的事，不碰界面状态；数据落到 [com.nekosu.manager.data.SuProfileStore]。
 * 每次调用都先看 [NekoNative.available]：没有内核模块（或者本应用没被 ncore 认定为 manager）时
 * 一律返回 null，让上层退回原来的 su / 本地配置路径。
 */
object NekoKernel {
    private const val TAG = "NekoSU/Kernel"

    data class Profile(
        val uid: Int,
        val caps: Long,
        val namespace: Int,
        val rootUid: Int,
        val domain: String,
    )

    /** 内核版本串，同时作为「通道是否可用」的判定依据。 */
    fun version(): String? = NekoNative.version()?.takeIf { it.isNotBlank() }

    val available: Boolean
        get() = version() != null

    /**
     * 读 profile 表。实际格式见 [parseAllowProfile]，解析失败的行跳过，
     * 不让一整张表因为一行脏数据全丢。
     */
    fun profiles(): List<Profile>? {
        val text = NekoNative.listProfiles() ?: return null
        return parseAllowProfile(text).also { Log.d(TAG, "profiles count=${it.size}") }
    }

    fun isAllowed(uid: Int): Boolean? = NekoNative.hasUid(uid)

    /**
     * 授权/取消授权。取消时内核表项与落盘条目一起清（JNI 里完成）。
     *
     * 返回 false：授权时是内核表项没写进去，撤销时是落盘条目没删掉，重启后状态可能回退。
     */
    fun setAllowed(
        uid: Int,
        allow: Boolean,
    ): Boolean {
        return if (allow) NekoNative.addUid(uid) else NekoNative.delUid(uid)
    }

    /**
     * 写一条 profile：内核表项 + 落盘一起，由 JNI 单次完成（对齐上游 setProfileProto）。
     * 失败只表示落盘没成功——内核那份通常已经生效。
     */
    fun setProfile(
        uid: Int,
        caps: Long,
        domain: String,
        namespace: Int,
        rootUid: Int,
    ): Boolean {
        return NekoNative.setProfile(uid, caps, domain, namespace, rootUid)
    }

    /** 内核特性表（`<id> <name> <value>`），用于关于页/诊断。 */
    fun features(): Map<String, Long>? {
        val text = NekoNative.featureList() ?: return null
        return text.lineSequence()
            .mapNotNull { line ->
                val parts = line.trim().split(' ')
                if (parts.size < 3) return@mapNotNull null
                val value = parts[2].toULongOrNull()?.toLong() ?: return@mapNotNull null
                parts[1] to value
            }
            .toMap()
    }
}

/** profile 文本里的分隔符：内核输出用空格，历史版本可能混用 tab。 */
private val PROFILE_WHITESPACE = Regex("\\s+")

/**
 * 解析内核 `IOC_GET_PROFILES` 返回的文本。
 *
 * 内核 `nksu_profile_to_text` 先输出两行 `#` 头，之后每行是：
 * ```
 * <uid> <caps_hex> <ns> <root_uid> <selinux_domain>
 * ```
 * 旧版内核没有 `root_uid` 一列，只有 4 段（甚至 3 段、省略 domain），所以按段数兼容。
 * SELinux 域没设置时内核写 `-`，这里回退到默认域。
 *
 * 这里曾把第 4 段（`root_uid`）当成了 SELinux 域：root_uid 默认就是 0，
 * 于是授权页里上下文会偶发变成 "0"，下次保存又把这个 "0" 写回内核，
 * 直接导致授权异常。位置必须按内核格式对齐。
 */
internal fun parseAllowProfile(text: String): List<NekoKernel.Profile> =
    text.lineSequence()
        .mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
            val parts = line.split(PROFILE_WHITESPACE)
            if (parts.size < 3) return@mapNotNull null
            val uid = parts[0].toIntOrNull() ?: return@mapNotNull null
            val caps = parts[1].toULongOrNull(16)?.toLong() ?: return@mapNotNull null
            val ns = parts[2].toIntOrNull() ?: return@mapNotNull null
            val rootUid = if (parts.size >= 5) parts[3].toIntOrNull() ?: 0 else 0
            val domainRaw =
                when {
                    parts.size >= 5 -> parts[4]
                    parts.size == 4 -> parts[3]
                    else -> null
                }
            val domain = domainRaw?.takeIf { it.isNotEmpty() && it != "-" } ?: SuProfile.DEFAULT_CONTEXT
            NekoKernel.Profile(uid, caps, ns, rootUid, domain)
        }
        .toList()
