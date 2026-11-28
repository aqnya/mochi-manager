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
            .onFailure { Log.w(TAG, "load libneko.so failed", it) }
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
    private external fun nativeFeatureSet(id: Int, value: Long): Int

    @JvmStatic
    private external fun nativeReadRootFile(path: String): ByteArray?

    fun version(): String? = call { nativeVersion() }

    fun available(): Boolean = call { nativeAvailable() } ?: false

    /** 1 已授权 / 0 未授权 / null 调用失败。 */
    fun hasUid(uid: Int): Boolean? = call { nativeHasUid(uid) }?.let { it > 0 }

    fun addUid(uid: Int): Boolean = call { nativeAddUid(uid) } == 0

    /** 撤销授权：删内核表项 + 删落盘条目，一次 JNI 调用搞定。 */
    fun delUid(uid: Int): Boolean = call { nativeDelUid(uid) } == 0

    fun listProfiles(): String? = call { nativeListProfiles() }

    /**
     * 写一条 profile：内核表项 + `/data/adb/nksu/profile` 落盘，两者在同一次 JNI
     * 调用里完成（对齐上游 `setProfileProto`）。返回 false 表示落盘失败——内核那份
     * 可能已经写进去了，但重启后 ncore 恢复不出来。
     */
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
     * 写一个内核特性开关：内核 ioctl + `/data/adb/nksu/profile` 落盘在同一次 JNI
     * 调用里完成（对齐上游 `Ncore.featureSet`）。返回 false 表示落盘失败——内核那份
     * 通常已经生效，但重启后 ncore 恢复不出来。
     */
    fun featureSet(id: Int, value: Long): Boolean = call { nativeFeatureSet(id, value) } == 0

    /**
     * 以 root 读整个文件内容；失败返回 null。
     *
     * fork 出的子进程里 `setresuid(0,0,0)` 临时提权，文件内容经管道传回，
     * 子进程退出即权限消失（对齐上游 `Ncore.readFile`）。只在内核模块在线且
     * 本应用是 manager 时成立——内核给 manager 的 uid 放行 setresuid 到 0；
     * 内核不在线时调用方降级到 [com.nekosu.manager.data.RootShell]。
     */
    fun readRootFile(path: String): ByteArray? = call { nativeReadRootFile(path) }?.takeIf { it.isNotEmpty() }

    private inline fun <T> call(block: () -> T): T? {
        if (!loaded) return null
        return runCatching(block)
            .onFailure { Log.w(TAG, "JNI call failed", it) }
            .getOrNull()
    }
}
