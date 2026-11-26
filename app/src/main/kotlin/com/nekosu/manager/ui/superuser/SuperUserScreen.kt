package com.nekosu.manager.ui.superuser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nekosu.manager.R
import com.nekosu.manager.data.MockData
import com.nekosu.manager.data.SuApp
import com.nekosu.manager.ui.mochi.AppIconBadge
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
 * 超级用户：已授权 / 自定义配置 / 其他应用三段分组，支持搜索、排序和「显示系统应用」等筛选。
 * 这里的数据是 [MockData.superUserApps]，交互（搜索、排序）是真实的。
 */
@Composable
fun SuperUserScreen(modifier: Modifier = Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    var sortType by rememberSaveable { mutableStateOf(SortType.NAME) }
    var reversed by rememberSaveable { mutableStateOf(false) }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val rootHeader = stringResource(R.string.mochi_su_section_root)
    val customHeader = stringResource(R.string.mochi_su_section_custom)
    val othersHeader = stringResource(R.string.mochi_su_section_others)

    val searching = query.isNotBlank()
    val sortLabels = SortType.entries.map { stringResource(it.labelRes) }
    val reverseLabel = stringResource(R.string.sort_reverse)
    val systemLabel = stringResource(R.string.show_system_apps)
    val more = stringResource(R.string.mochi_more)

    val apps =
        remember(query, sortType, reversed, showSystem) {
            val base =
                MockData.superUserApps
                    .filter { showSystem || !it.packageName.startsWith("com.android.") }
                    .filter {
                        !searching ||
                            it.label.contains(query, ignoreCase = true) ||
                            it.packageName.contains(query, ignoreCase = true)
                    }
            val sorted =
                when (sortType) {
                    SortType.NAME -> base.sortedBy { it.label }
                    SortType.PACKAGE_NAME -> base.sortedBy { it.packageName }
                    SortType.INSTALL_TIME -> base.sortedByDescending { it.uid }
                }
            if (reversed) sorted.reversed() else sorted
        }

    MochiLazyLargeTitleScaffold(
        title = stringResource(R.string.superuser),
        modifier = modifier,
        actions = {
            MochiBarGroup(navId = "su.actions") {
                MochiBarGroupItem(onClick = {}, navId = "su.log", contentKey = "reader") {
                    MochiIonicon("reader")
                }
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
        searchBar = {
            MochiSearchBar(
                query = query,
                onQueryChange = { query = it },
                placeholder = stringResource(R.string.mochi_search_apps),
                onCancel = { query = "" },
            )
        },
    ) {
        val rooted = apps.filter { it.allowSu }
        val custom = apps.filter { !it.allowSu && it.custom }
        val others = apps.filter { !it.allowSu && !it.custom }
        when {
            apps.isEmpty() -> item(key = "empty") {
                MochiEmptyState(
                    stringResource(R.string.mochi_no_results),
                    icon = { MochiIonicon("search", size = 44.dp) },
                )
            }

            searching -> mochiListSection(key = "results") {
                rows(apps, key = { it.uid }) { app -> SuAppRow(app) }
            }

            else -> {
                if (rooted.isNotEmpty()) {
                    mochiListSection(key = "rooted", header = rootHeader) {
                        rows(rooted, key = { it.uid }) { app -> SuAppRow(app, showRoot = false) }
                    }
                }
                if (custom.isNotEmpty()) {
                    mochiListSection(key = "custom", header = customHeader) {
                        rows(custom, key = { it.uid }) { app -> SuAppRow(app, showCustom = false) }
                    }
                }
                if (others.isNotEmpty()) {
                    mochiListSection(key = "others", header = othersHeader) {
                        rows(others, key = { it.uid }) { app -> SuAppRow(app, showUmount = false) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuAppRow(
    app: SuApp,
    showRoot: Boolean = true,
    showCustom: Boolean = true,
    showUmount: Boolean = true,
) {
    val cs = MochiTheme.colorScheme
    val root = showRoot && app.allowSu
    val umount = showUmount && app.umount
    val customTag = showCustom && app.custom
    val hasTags = root || umount || customTag || app.userId != 0
    MochiListRow(
        app.label,
        subtitle = app.packageName,
        leading = { AppIconBadge(app.label, app.accent, AppIconSize) },
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
        onClick = {},
    )
}

private fun checkIcon(on: Boolean): (@Composable (androidx.compose.ui.graphics.Color) -> Unit)? =
    if (on) {
        { tint -> MochiIonicon("checkmark", tint = tint) }
    } else {
        null
    }
