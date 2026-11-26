package com.nekosu.manager.ui.superuser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
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
import com.nekosu.manager.R
import com.nekosu.manager.data.InstalledApp
import com.nekosu.manager.data.InstalledAppsRepository
import com.nekosu.manager.data.SuProfile
import com.nekosu.manager.data.SuProfileStore
import com.nekosu.manager.ui.mochi.AppIcon
import com.nekosu.manager.ui.mochi.KsuTileColors
import kotlinx.collections.immutable.toImmutableList
import ui.feice.mochi.basic.MochiBarGroup
import ui.feice.mochi.basic.MochiBarGroupDropdownItem
import ui.feice.mochi.basic.MochiBarGroupItem
import ui.feice.mochi.basic.MochiEmptyState
import ui.feice.mochi.basic.MochiItem
import ui.feice.mochi.basic.MochiSearchBar
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.list.MochiListRow
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
 * 超级用户：列出设备上真实安装的应用，按配置分成
 * 「已授权 / 自定义配置 / 其他应用」三段（对齐 PR 的分组语义）。
 *
 * 点击任意一行进入应用配置页，那里决定是否放行、以什么 uid/gid 运行、
 * 附加组、Capabilities、命名空间、SELinux 域和是否卸载模块。
 */
@Composable
fun SuperUserScreen(
    onOpenApp: (InstalledApp) -> Unit,
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

    LaunchedEffect(reloadKey) {
        apps = null
        apps = InstalledAppsRepository.load(context)
    }
    LaunchedEffect(Unit) { SuProfileStore.init(context) }
    val profiles by SuProfileStore.profiles.collectAsState()

    val rootHeader = stringResource(R.string.mochi_su_section_root)
    val customHeader = stringResource(R.string.mochi_su_section_custom)
    val othersHeader = stringResource(R.string.mochi_su_section_others)
    val searching = query.isNotBlank()
    val sortLabels = SortType.entries.map { stringResource(it.labelRes) }
    val reverseLabel = stringResource(R.string.sort_reverse)
    val systemLabel = stringResource(R.string.show_system_apps)
    val refreshLabel = stringResource(R.string.su_refresh)

    val filtered =
        remember(apps, query, sortType, reversed, showSystem) {
            val base =
                apps.orEmpty()
                    .filter { showSystem || !it.isSystem }
                    .filter {
                        !searching ||
                            it.label.contains(query, ignoreCase = true) ||
                            it.packageName.contains(query, ignoreCase = true)
                    }
            val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
            val sorted =
                when (sortType) {
                    SortType.NAME -> base.sortedWith { a, b -> collator.compare(a.label, b.label) }
                    SortType.PACKAGE_NAME -> base.sortedBy { it.packageName }
                    SortType.INSTALL_TIME -> base.sortedByDescending { it.installTime }
                }
            if (reversed) sorted.reversed() else sorted
        }

    MochiLazyLargeTitleScaffold(
        title = stringResource(R.string.superuser),
        modifier = modifier,
        actions = {
            MochiBarGroup(navId = "su.actions") {
                MochiBarGroupItem(onClick = { reloadKey++ }, navId = "su.reload", contentKey = "refresh") {
                    MochiIonicon("refresh")
                }
                val items =
                    buildList {
                        SortType.entries.forEachIndexed { index, type ->
                            add(MochiItem(sortLabels[index], icon = checkIcon(sortType == type)))
                        }
                        add(MochiItem(reverseLabel, icon = checkIcon(reversed)))
                        add(MochiItem(systemLabel, icon = checkIcon(showSystem)))
                        add(MochiItem(refreshLabel, icon = { tint -> MochiIonicon("refresh", tint = tint) }))
                    }.toImmutableList()
                MochiBarGroupDropdownItem(
                    expanded = menu,
                    onExpandedChange = { menu = it },
                    items = items,
                    onItemClick = { index ->
                        when {
                            index < SortType.entries.size -> sortType = SortType.entries[index]
                            index == SortType.entries.size -> reversed = !reversed
                            index == SortType.entries.size + 1 -> showSystem = !showSystem
                            else -> reloadKey++
                        }
                    },
                    navId = "su.more",
                    contentKey = "filter",
                ) { MochiIonicon("filter") }
            }
        },
        searchBar = {
            MochiSearchBar(
                query = query,
                onQueryChange = { query = it },
                placeholder = stringResource(R.string.mochi_search_apps),
                onCancel = { query = "" },
            )
        },
    ) {
        if (apps == null) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                }
            }
        } else {
            val rooted = filtered.filter { profiles[it.packageName]?.allowSu == true }
            val custom =
                filtered.filter { app ->
                    profiles[app.packageName]?.let { !it.allowSu && it.isCustom } == true
                }
            val others = filtered.filter { it !in rooted && it !in custom }
            when {
                filtered.isEmpty() -> item(key = "empty") {
                    MochiEmptyState(
                        stringResource(R.string.mochi_no_results),
                        icon = { MochiIonicon("search", size = 44.dp) },
                    )
                }

                searching -> mochiListSection(key = "results") {
                    rows(filtered, key = { it.packageName }) { app ->
                        AppRow(app, profiles[app.packageName], onClick = { onOpenApp(app) })
                    }
                }

                else -> {
                    if (rooted.isNotEmpty()) {
                        mochiListSection(key = "rooted", header = rootHeader) {
                            rows(rooted, key = { it.packageName }) { app ->
                                AppRow(app, profiles[app.packageName], showRoot = false, onClick = { onOpenApp(app) })
                            }
                        }
                    }
                    if (custom.isNotEmpty()) {
                        mochiListSection(key = "custom", header = customHeader) {
                            rows(custom, key = { it.packageName }) { app ->
                                AppRow(app, profiles[app.packageName], showCustom = false, onClick = { onOpenApp(app) })
                            }
                        }
                    }
                    if (others.isNotEmpty()) {
                        mochiListSection(key = "others", header = othersHeader) {
                            rows(others, key = { it.packageName }) { app ->
                                AppRow(app, profiles[app.packageName], showUmount = false, onClick = { onOpenApp(app) })
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
    app: InstalledApp,
    profile: SuProfile?,
    showRoot: Boolean = true,
    showCustom: Boolean = true,
    showUmount: Boolean = true,
    onClick: () -> Unit,
) {
    val cs = MochiTheme.colorScheme
    val root = showRoot && profile?.allowSu == true
    val umount = showUmount && profile?.umountModules == true
    val customTag = showCustom && profile?.isCustom == true
    val hasTags = root || umount || customTag || app.userId != 0
    val accent = if (profile?.allowSu == true) KsuTileColors.Green else KsuTileColors.Blue
    MochiListRow(
        app.label,
        subtitle = app.packageName,
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
                    if (app.userId != 0) {
                        MochiListStatusChip("USER ${app.userId}", containerColor = cs.surfaceVariant, contentColor = cs.onSurfaceVariant)
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
