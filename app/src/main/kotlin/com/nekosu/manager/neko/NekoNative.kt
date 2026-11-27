package com.nekosu.manager.neko

import android.util.Log

/**
 * 内核控制通道的 JNI 绑定。
 *
 * 符号名与 `app/src/main/cpp/neko_jni.cpp` 一一对应（`com.nekosu.manager.neko.NekoNative`）。
 * 上游 neko 包自带的 Rust `jni` crate 没有被采用：管理器只需要授权表与版本信息，
 * 用 C++ 直接按 `uapi/ioctl.h` 实现即可。
 *
 * 所有调用失败都返回 `null` / 负值，不会抛异常——设备没加载内核模块时这是常态，
 * 调用方据此降级到已有的 su / 本地数据路径。
 */
object NekoNative {
    private const val TAG = "NekoSU/Native"

    /** 库是否装上。设备 ABI 不是 arm64 时加载会失败，按不可用处理。 */
    val loaded: Boolean =
        runCatching { System.loadLibrary("neko") }
            .onFailure { Log.w(TAG, "加载 libneko.so 失败：${it.message}") }
            .isSuccess

    @JvmStatic
    private external fun nativeVersion(): String?

    @JvmStatic
    private external fun nativeAvailable(): Boolean

    @JvmStatic
    private external fun nativeHasUid(uid: Int): Int

    @JvmStatic
    private external fun nativeAddUid(uid: Int): Int

    @JvmStatic
    private external fun nativeDelUid(uid: Int): Int

    @JvmStatic
    private external fun nativeListProfiles(): String?

    @JvmStatic
    private external fun nativeSetProfile(
        uid: Int,
        caps: Long,
        domain: String,
        ns: Int,
        rootUid: Int,
    ): Int

    @JvmStatic
    private external fun nativeFeatureList(): String?

    @JvmStatic
    private external fun nativeFeatureGet(id: Int): Long

    @JvmStatic
    private external fun nativeReadRootFile(path: String): ByteArray?

    @JvmStatic
    private external fun nativeSaveProfile(
        uid: Int,
        rootUid: Int,
        caps: Long,
        ns: Int,
        domain: String,
    ): Int

    @JvmStatic
    private external fun nativeDeleteProfile(uid: Int): Int

    fun version(): String? = call { nativeVersion() }

    fun available(): Boolean = call { nativeAvailable() } ?: false

    /** 1 已授权 / 0 未授权 / null 调用失败。 */
    fun hasUid(uid: Int): Boolean? = call { nativeHasUid(uid) }?.let { it > 0 }

    fun addUid(uid: Int): Boolean = call { nativeAddUid(uid) } == 0

    fun delUid(uid: Int): Boolean = call { nativeDelUid(uid) } == 0

    fun listProfiles(): String? = call { nativeListProfiles() }

    fun setProfile(
        uid: Int,
        caps: Long,
        domain: String,
        ns: Int,
        rootUid: Int,
    ): Boolean = call { nativeSetProfile(uid, caps, domain, ns, rootUid) } == 0

    fun featureList(): String? = call { nativeFeatureList() }

    fun featureGet(id: Int): Long? = call { nativeFeatureGet(id) }?.takeIf { it >= 0 }

    /**
     * 以 root 读整个文件内容；失败返回 null。
     *
     * fork 出的子进程里 `setresuid(0,0,0)` 临时提权，文件内容经管道传回，
     * 子进程退出即权限消失（对齐上游 `Ncore.readFile`）。只在内核模块在线且
     * 本应用是 manager 时成立——内核给 manager 的 uid 放行 setresuid 到 0；
     * 内核不在线时调用方降级到 [com.nekosu.manager.data.RootShell]。
     */
    fun readRootFile(path: String): ByteArray? = call { nativeReadRootFile(path) }?.takeIf { it.isNotEmpty() }

    /**
     * 把一条 profile 落盘到内核侧存储 `/data/adb/nksu/profile`（libstore 实现）。
     *
     * fork 子进程 `setresuid(0,0,0)` 临时提权后调 `nksu_store_upsert`——与上游
     * `setProfileProto`（内核 set_profile + store::profile::save）的落盘语义一致：
     * 读现有文件 → 按 uid upsert → 临时文件 + fsync + rename 原子写回。ncore 重启
     * 时从这份文件恢复授权，管理器只写内核不落盘的话授权活不过一次重启。
     * 成功返回 true；内核不在线 / 提权失败 / 写失败返回 false。
     */
    fun saveProfile(
        uid: Int,
        rootUid: Int,
        caps: Long,
        ns: Int,
        domain: String,
    ): Boolean = call { nativeSaveProfile(uid, rootUid, caps, ns, domain) } == 0

    /** 从落盘存储删除一条 profile（撤销授权）；幂等，成功返回 true。 */
    fun deleteProfile(uid: Int): Boolean = call { nativeDeleteProfile(uid) } == 0

    private inline fun <T> call(block: () -> T): T? {
        if (!loaded) return null
        return runCatching(block)
            .onFailure { Log.w(TAG, "JNI 调用失败：${it.message}") }
            .getOrNull()
    }
}
