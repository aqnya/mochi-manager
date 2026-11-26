package com.nekosu.manager.data

/**
 * 共享 UID 的应用组。
 *
 * Android 允许用 `android:sharedUserId` / `sharedUserLabel` 让多个包跑在同一个 uid 下
 * （最典型的是 `android.uid.system`、`android.uid.phone`，以及同厂商的套件）。
 * KernelSU 的授权粒度是 **uid**，不是包名：给其中一个包放行，同组的其他包自动一起放行；
 * 内核里的 profile 也是按 uid 存的。所以管理器的列表必须按 uid 归并成一行，
 * 否则同一 uid 会出现多行各自显示不同授权状态，纯属误导。
 *
 * 这里的 [primary] / [title] 尽量对齐 PR 里的 `pickPrimary` 与 `ownerNameForUid`：
 * 优先用 `sharedUserLabel` 声明的名字，其次 `android.uid.*` 的固定映射，最后才退回首个应用。
 */
data class AppGroup(
    val uid: Int,
    val apps: List<InstalledApp>,
    val primary: InstalledApp,
    val ownerName: String? = null,
    /** 搜索命中的包名，命中时把副标题换成它们，便于看清组里到底是哪个应用匹配。 */
    val matchedPackages: Set<String> = emptySet(),
) {
    val userId: Int get() = uid / PER_USER_RANGE
    val shared: Boolean get() = apps.size > 1

    /** 共享 uid 有属主名就用属主名，否则退回主应用名（副标题会标出组内应用数）。 */
    val title: String get() = ownerName ?: primary.label
}

private const val PER_USER_RANGE = 100_000

/** `android.uid.*` 下优先挑哪个包作为组的代表，避免图标随机落在某个后装的包上。 */
private val PREFERRED_PKG_BY_SUID =
    mapOf(
        "android.uid.system" to "android",
        "android.uid.phone" to "com.android.phone",
        "android.uid.bluetooth" to "com.android.bluetooth",
        "android.uid.nfc" to "com.android.nfc",
    )

/**
 * 把一个 uid 下的多个包排序并挑出代表应用。
 *
 * 顺序与 KernelSU 一致：先看有没有 `sharedUserLabel`（取包名最短的，通常是最稳定的那个），
 * 再看 `android.uid.*` 的固定映射，最后按包名长度 + 字典序兜底。
 */
fun pickPrimary(apps: List<InstalledApp>): InstalledApp {
    require(apps.isNotEmpty()) { "apps must not be empty" }
    val labeled = apps.filter { it.sharedLabel != null }
    if (labeled.isNotEmpty()) {
        return labeled.minWith(compareBy({ it.packageName.length }, { it.packageName }))
    }
    val bySuid =
        apps.filter { it.sharedUserId?.startsWith("android.uid.") == true }
            .groupBy { it.sharedUserId.orEmpty() }
    if (bySuid.isEmpty()) return apps.first()
    val suid = bySuid.keys.min()
    val group = bySuid[suid].orEmpty().ifEmpty { apps }
    PREFERRED_PKG_BY_SUID[suid]?.let { preferred ->
        group.firstOrNull { it.packageName == preferred }?.let { return it }
    }
    return group.minWith(compareBy({ it.packageName.length }, { it.packageName }))
}

/**
 * 共享 uid 的显示名：`sharedUserLabel` 的文案 > `android.uid.xxx` 这类 sharedUserId > null。
 *
 * 正常的单应用组不需要属主名，返回 null 让界面直接用应用名。
 * 纯应用共享（如 `com.google.uid.shared`）也返回 sharedUserId，比「XX 等 3 个应用」更能说明问题。
 */
fun ownerNameFor(apps: List<InstalledApp>): String? {
    if (apps.size < 2) return null
    apps.firstNotNullOfOrNull { it.sharedLabel }?.let { return it }
    return apps
        .mapNotNull { it.sharedUserId }
        .firstOrNull { it.startsWith("android.uid.") || it.contains(".uid.") }
}

/**
 * 按 uid 归并应用列表；组内按「已授权优先、配置过的其次、再按名称」排序，
 * 这样组里被点过的那一行会成为代表，图标与标签不会跳来跳去。
 */
fun groupApps(
    apps: List<InstalledApp>,
    rank: (InstalledApp) -> Int = { 0 },
): List<AppGroup> {
    val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
    return apps
        .groupBy { it.uid }
        .map { (uid, list) ->
            val sorted = list.sortedWith(compareBy<InstalledApp>(rank).thenBy(collator) { it.label })
            AppGroup(
                uid = uid,
                apps = sorted,
                primary = pickPrimary(sorted),
                ownerName = ownerNameFor(sorted),
            )
        }
}
