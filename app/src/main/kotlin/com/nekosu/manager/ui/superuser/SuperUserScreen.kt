package com.nekosu.manager.ui.superuser

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nekosu.manager.R
import com.nekosu.manager.data.AppGroup
import com.nekosu.manager.data.AppListAccess
import com.nekosu.manager.data.InstalledApp
import com.nekosu.manager.data.InstalledAppsRepository
import com.nekosu.manager.data.SuProfile
import com.nekosu.manager.data.SuProfileStore
import com.nekosu.manager.data.groupApps
import com.nekosu.manager.ui.mochi.AppIcon
import com.nekosu.manager.ui.mochi.KsuTileColors
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ui.feice.mochi.basic.MochiBarGroup
import ui.feice.mochi.basic.MochiBarGroupDropdownItem
import ui.feice.mochi.basic.MochiButton
import ui.feice.mochi.basic.MochiButtonStyle
import ui.feice.mochi.basic.MochiEmptyState
import ui.feice.mochi.basic.MochiItem
import ui.feice.mochi.basic.MochiSearchBar
import ui.feice.mochi.basic.Text
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.list.MochiListCard
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.MochiListSection
import ui.feice.mochi.list.MochiListStatusChip
import ui.feice.mochi.list.mochiListSection
import ui.feice.mochi.list.rows
import ui.feice.mochi.navigation.MochiLazyLargeTitleScaffold
import ui.feice.mochi.theme.MochiTheme

private val AppIconSize = 36.dp

private enum class SortType(
    val labelRes: Int,
) {
    NAME(R.string.sort_by_name),
    PACKAGE_NAME(R.string.sort_by_package_name),
    INSTALL_TIME(R.string.sort_by_install_time),
}

/**
 * 超级用户：列出设备上真实安装的应用，按 uid 归并后分成
 * 「已授权 / 自定义配置 / 其他应用」三段（对齐 PR 的分组语义）。
 *
 * 归并的原因是授权粒度：KernelSU 的 profile 与 su 白名单都按 uid 存，
 * 共享 uid 的多个包（`android.uid.system`、厂商套件等）只能作为一行出现，
 * 否则同组会出现多行互相矛盾的开关。组内代表应用由 [pickPrimary] 选出。
 *
 * 点击任意一行进入应用配置页，那里决定是否放行、以什么 uid/gid 运行、
 * 附加组、Capabilities、命名空间、SELinux 域和是否卸载模块。
 */
@Composable
fun SuperUserScreen(
    onOpenApp: (AppGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var reloadKey by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var sortType by rememberSaveable { mutableStateOf(SortType.NAME) }
    var reversed by rememberSaveable { mutableStateOf(false) }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    // 读列表串行化：下拉刷新与 reloadKey（权限回调、从设置页返回、重试）不会同时遍历 PackageManager
    val loadMutex = remember { Mutex() }

    /**
     * 加载应用列表。`showLoading` 只用于首次进入：刷新时保留旧列表，
     * 避免内容突然塌成一行 loading（那样列表高度骤变，下拉指示器会错位）。
     *
     * 读失败时保留已有数据 —— 异常会打断调用它的协程，
     * 导致后面的状态复位被跳过，界面就永远停在刷新态。
     */
    suspend fun loadApps(showLoading: Boolean) {
        if (showLoading) apps = null
        val loaded =
            try {
                loadMutex.withLock { InstalledAppsRepository.load(context) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                apps.orEmpty()
            }
        apps = loaded
    }

    LaunchedEffect(reloadKey) { loadApps(showLoading = apps == null) }
    LaunchedEffect(Unit) {
        SuProfileStore.init(context)
        SuProfileStore.refreshFromKernel(context)
    }
    val profiles by SuProfileStore.profiles.collectAsState()

    // 小米「获取应用列表」权限：HyperOS / MIUI 13+ 可以像普通运行时权限一样动态申请，
    // 首次进入弹一次系统授权框，拒绝后不再打扰，改由下面的引导卡跳设置页。
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            reloadKey++
        }
    LaunchedEffect(Unit) {
        if (AppListAccess.shouldAskOnce(context)) {
            permissionLauncher.launch(AppListAccess.PERMISSION)
        }
    }

    // 从系统权限页返回时重新读一次：HyperOS 的「读取应用列表」开关只能在系统设置里改
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var firstResume = true
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    if (firstResume) firstResume = false else reloadKey++
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val rootHeader = stringResource(R.string.mochi_su_section_root)
    val customHeader = stringResource(R.string.mochi_su_section_custom)
    val othersHeader = stringResource(R.string.mochi_su_section_others)
    val searching = query.isNotBlank()
    val sortLabels = SortType.entries.map { stringResource(it.labelRes) }
    val reverseLabel = stringResource(R.string.sort_reverse)
    val systemLabel = stringResource(R.string.show_system_apps)

    // 先按单个应用过滤（系统应用、搜索词），再按 uid 归并：这样搜索「微信」时，
    // 与它共享 uid 的其他包也会一并出现在同一行里，符合“授权对整组生效”的事实。
    val filtered: List<AppGroup> =
        remember(apps, query, sortType, reversed, showSystem, profiles) {
            val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
            val matched: (InstalledApp) -> Boolean = {
                it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
            val base =
                apps.orEmpty()
                    .filter { showSystem || !it.isSystem }
                    .filter { !searching || matched(it) }
            val rank: (InstalledApp) -> Int = { app ->
                val p = profiles[app.uid]
                when {
                    p?.allowSu == true -> 0
                    p?.isCustom == true -> 1
                    else -> 2
                }
            }
            val grouped = groupApps(base, rank)
            val sorted =
                when (sortType) {
                    SortType.NAME -> grouped.sortedWith { a, b -> collator.compare(a.title, b.title) }
                    SortType.PACKAGE_NAME -> grouped.sortedBy { it.primary.packageName }
                    SortType.INSTALL_TIME -> grouped.sortedByDescending { it.primary.installTime }
                }
            val oriented = if (reversed) sorted.reversed() else sorted
            if (searching) {
                oriented.map { group ->
                    group.copy(
                        matchedPackages =
                            group.apps
                                .filter(matched)
                                .map { it.packageName }
                                .toSet(),
                    )
                }
            } else {
                oriented
            }
        }

    MochiLazyLargeTitleScaffold(
        title = stringResource(R.string.superuser),
        modifier = modifier,
        actions = {
            MochiBarGroup(navId = "su.actions") {
                val items =
                    buildList {
                        SortType.entries.forEachIndexed { index, type ->
                            add(MochiItem(sortLabels[index], icon = checkIcon(sortType == type)))
                        }
                        add(MochiItem(reverseLabel, icon = checkIcon(reversed)))
                        add(MochiItem(systemLabel, icon = checkIcon(showSystem)))
                    }.toImmutableList()
                MochiBarGroupDropdownItem(
                    expanded = menu,
                    onExpandedChange = { menu = it },
                    items = items,
                    onItemClick = { index ->
                        when {
                            index < SortType.entries.size -> sortType = SortType.entries[index]
                            index == SortType.entries.size -> reversed = !reversed
                            else -> showSystem = !showSystem
                        }
                    },
                    navId = "su.more",
                    contentKey = "filter",
                ) { MochiIonicon("filter") }
            }
        },
        // 列表读不到（权限被限制）时搜索没有意义，直接不显示搜索框
        searchBar = if (apps != null && AppListAccess.looksRestricted(apps.orEmpty())) {
            null
        } else {
            {
                MochiSearchBar(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = stringResource(R.string.mochi_search_apps),
                    onCancel = { query = "" },
                )
            }
        },
        // 刷新必须在这里做完：onRefresh 返回后库才会把 isRefreshing 置回 false。
        // 不要把加载丢给 LaunchedEffect(reloadKey) 再等一个外部状态 —— 那条链路一旦
        // 漏掉一次 false（异常、协程重启），下拉就会永久卡住（菊花不消失、列表被顶下去）。
        onRefresh = { runRefresh { loadApps(showLoading = false) } },
    ) {
        if (apps == null) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                }
            }
        } else if (AppListAccess.looksRestricted(apps.orEmpty())) {
            item(key = "perm") {
                PermissionNotice(
                    onOpen = { AppListAccess.openPermissionSettings(context) },
                    onRetry = { reloadKey++ },
                )
            }
        } else {
            val rooted = filtered.filter { profiles[it.uid]?.allowSu == true }
            val custom =
                filtered.filter { group ->
                    profiles[group.uid]?.let { !it.allowSu && it.isCustom } == true
                }
            val rootedUids = rooted.mapTo(HashSet()) { it.uid }
            val customUids = custom.mapTo(HashSet()) { it.uid }
            val others = filtered.filter { it.uid !in rootedUids && it.uid !in customUids }
            when {
                filtered.isEmpty() -> item(key = "empty") {
                    MochiEmptyState(
                        stringResource(R.string.mochi_no_results),
                        icon = { MochiIonicon("search", size = 44.dp) },
                    )
                }

                searching -> mochiListSection(key = "results") {
                    rows(filtered, key = { it.uid }) { group ->
                        AppRow(group, profiles[group.uid], onClick = { onOpenApp(group) })
                    }
                }

                else -> {
                    if (rooted.isNotEmpty()) {
                        mochiListSection(key = "rooted", header = rootHeader) {
                            rows(rooted, key = { it.uid }) { group ->
                                AppRow(group, profiles[group.uid], showRoot = false, onClick = { onOpenApp(group) })
                            }
                        }
                    }
                    if (custom.isNotEmpty()) {
                        mochiListSection(key = "custom", header = customHeader) {
                            rows(custom, key = { it.uid }) { group ->
                                AppRow(group, profiles[group.uid], showCustom = false, onClick = { onOpenApp(group) })
                            }
                        }
                    }
                    if (others.isNotEmpty()) {
                        mochiListSection(key = "others", header = othersHeader) {
                            rows(others, key = { it.uid }) { group ->
                                AppRow(group, profiles[group.uid], showUmount = false, onClick = { onOpenApp(group) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppRow(
    group: AppGroup,
    profile: SuProfile?,
    showRoot: Boolean = true,
    showCustom: Boolean = true,
    showUmount: Boolean = true,
    onClick: () -> Unit,
) {
    val cs = MochiTheme.colorScheme
    val app = group.primary
    val root = showRoot && profile?.allowSu == true
    val umount = showUmount && profile?.umountModules == true
    val customTag = showCustom && profile?.isCustom == true
    val hasTags = root || umount || customTag || group.userId != 0
    val accent = if (profile?.allowSu == true) KsuTileColors.Green else KsuTileColors.Blue
    val sharedSummary = stringResource(R.string.group_contains_apps, group.apps.size)
    val subtitle =
        when {
            // 搜索命中：直接把命中的包名列出来，共享 uid 时尤其有用
            group.matchedPackages.isNotEmpty() -> group.matchedPackages.joinToString(", ")
            group.shared -> "$sharedSummary · UID ${group.uid}"
            else -> app.packageName
        }
    MochiListRow(
        group.title,
        subtitle = subtitle,
        leading = { AppIcon(app.packageName, app.label, accent, AppIconSize) },
        leadingSize = AppIconSize,
        trailing = if (hasTags) {
            {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    maxItemsInEachRow = 2,
                ) {
                    if (root) MochiListStatusChip("ROOT")
                    if (umount) {
                        MochiListStatusChip("UMOUNT", containerColor = cs.secondaryContainer, contentColor = cs.onSecondaryContainer)
                    }
                    if (customTag) {
                        MochiListStatusChip("CUSTOM", containerColor = cs.tertiaryContainer, contentColor = cs.onTertiaryContainer)
                    }
                    if (group.userId != 0) {
                        MochiListStatusChip("USER ${group.userId}", containerColor = cs.surfaceVariant, contentColor = cs.onSurfaceVariant)
                    }
                }
            }
        } else {
            null
        },
        chevron = true,
        onClick = onClick,
    )
}

private fun checkIcon(on: Boolean): (@Composable (androidx.compose.ui.graphics.Color) -> Unit)? =
    if (on) {
        { tint -> MochiIonicon("checkmark", tint = tint) }
    } else {
        null
    }

/**
 * 「读取应用列表」被系统限制时的引导卡。
 *
 * HyperOS / MIUI 关闭这个权限后 `getInstalledPackages` 只返回自身，列表会全空；
 * 这里直接给出去设置的入口，另外留一个「重新检查」用于返回后手动重试（部分 ROM 需要重进应用）。
 */
@Composable
private fun PermissionNotice(
    onOpen: () -> Unit,
    onRetry: () -> Unit,
) {
    val cs = MochiTheme.colorScheme
    MochiListSection(header = stringResource(R.string.su_perm_section)) {
        MochiListCard {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MochiIonicon("shield-half", tint = cs.primary, size = 20.dp)
                    Text(
                        stringResource(R.string.su_perm_title),
                        style = MochiTheme.textStyles.navTitle,
                        color = cs.onSurface,
                    )
                }
                Text(
                    stringResource(
                        if (AppListAccess.isMiuiFamily()) R.string.su_perm_summary else R.string.su_perm_summary_generic,
                    ),
                    style = MochiTheme.textStyles.footnote,
                    color = cs.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MochiButton(onClick = onOpen, style = MochiButtonStyle.Filled) {
                        Text(stringResource(R.string.su_perm_open))
                    }
                    MochiButton(onClick = onRetry, style = MochiButtonStyle.Tonal) {
                        Text(stringResource(R.string.su_perm_retry))
                    }
                }
            }
        }
    }
}
