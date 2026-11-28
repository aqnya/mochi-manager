// SPDX-License-Identifier: GPL-3.0-or-later
package com.nekosu.manager.data

import com.nekosu.manager.neko.NekoNative

/** 特性表行内分隔符复用同一个 Regex，避免每行都构造一次。 */
internal val FEATURE_WHITESPACE = Regex("\\s+")

/**
 * 内核 feature 开关的数据层（对齐上游 `KernelFeature`）。
 *
 * 列表与状态都由内核给出（`IOC_FEATURE_LIST/GET/SET`，见 `uapi/ioctl.h`），管理器只
 * 负责解析、展示和切换。切换时 JNI 会把值同步到内核并落盘到开机标志文件，
 * 下次 ncore 启动时灌回内核——所以这里不需要自己持久化。
 *
 * feature id 是和内核、ncore 共享的线上契约，见 `kernel/manager/feature.h`：
 * ```
 * #define NKSU_FEATURE_SELINUX_HIDE 1
 * #define NKSU_FEATURE_UMOUNT 2
 * ```
 */
object KernelFeature {
    const val SELINUX_HIDE = 1
    const val UMOUNT = 2

    data class Info(
        val id: Int,
        val name: String,
        val enabled: Boolean,
    )

    fun list(): List<Info> = parseFeatureTable(NekoNative.featureList())

    /** 切换一个特性开关，成功返回 true。value 用 proto3 uint64，非零即启用。 */
    fun set(
        id: Int,
        enabled: Boolean,
    ): Boolean = NekoNative.featureSet(id, if (enabled) 1L else 0L)
}

/** 解析内核特性表文本；[text] 为 null 表示通道不可用，回退空列表。 */
internal fun parseFeatureTable(text: String?): List<KernelFeature.Info> =
    text
        ?.lineSequence()
        ?.mapNotNull { line ->
            val parts = line.trim().split(FEATURE_WHITESPACE)
            if (parts.size < 3) return@mapNotNull null
            val id = parts[0].toIntOrNull() ?: return@mapNotNull null
            KernelFeature.Info(
                id = id,
                name = parts[1],
                enabled = parts[2].toLongOrNull() != 0L,
            )
        }?.toList()
        .orEmpty()
