package com.nekosu.manager.data

/**
 * 模块卡片的数据，字段与 `module.prop` 对齐。
 *
 * 定义在这里而不是和假数据放在一起：模块列表已经全部来自 ncore 的
 * `module list --json`（见 [ModuleStore]），这里只留数据形状。
 */
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
    /** `module.prop` 的 versionCode；ncore 的列表 JSON 没给时是 0，更新检查会再读一次 prop。 */
    val versionCode: Long = 0L,
    /** `module.prop` 的 updateJson，模块更新的检查地址；缺省为空。 */
    val updateJson: String = "",
)
