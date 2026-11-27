package com.nekosu.manager.data

/** 模块卡片的数据。 */
data class ModuleEntry(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val enabled: Boolean = true,
    val remove: Boolean = false,
    val metaModule: Boolean = false,
    val hasAction: Boolean = false,
    val hasWebUi: Boolean = false,
    val hasUpdate: Boolean = false,
)

object MockData {
    const val ksuVersion = "v1.1.0-12851"
    const val kernelUapiVersion = "1.0"
    const val lkmMode = "GKI"

    val modules =
        listOf(
            ModuleEntry(
                id = "zygisk_lsposed",
                name = "LSPosed",
                version = "v1.9.3",
                author = "LSPosed Developers",
                description = "基于 Zygisk 的 ART hooking 框架，提供与原 Xposed 一致的 API，用于模块化修改应用行为。",
                hasAction = false,
                hasWebUi = true,
            ),
            ModuleEntry(
                id = "tricky_store",
                name = "Tricky Store",
                version = "v1.2.0",
                author = "5ec1cff",
                description = "为指定应用提供完整的密钥库（KeyStore）与硬件证明支持，修复部分应用的完整性校验失败问题。",
                hasAction = true,
            ),
            ModuleEntry(
                id = "susfs4ksu",
                name = "SUSFS4KSU",
                version = "v2.0.0",
                author = "sidex15",
                description = "内核级路径与挂载隐藏方案，配合 KernelSU 使用可让 root 痕迹对检测方不可见。",
                enabled = false,
                hasUpdate = true,
            ),
            ModuleEntry(
                id = "meta_hybrid",
                name = "Hybrid Mount",
                version = "v3.1.2",
                author = "HuskyDG",
                description = "元模块：把模块文件以 overlayfs 方式挂载，减少对系统分区的写入。",
                metaModule = true,
                hasAction = true,
            ),
            ModuleEntry(
                id = "play_integrity_fix",
                name = "Play Integrity Fix",
                version = "v18.2",
                author = "chiteroman",
                description = "修复 Play Integrity 基础完整性校验，需要在模块内配置一份可用的指纹（fingerprint）。",
                enabled = false,
                remove = true,
            ),
        )
}
