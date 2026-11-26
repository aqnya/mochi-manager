package com.nekosu.manager.ui.module

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nekosu.manager.R
import com.nekosu.manager.data.MockData
import com.nekosu.manager.data.ModuleEntry
import kotlinx.collections.immutable.toImmutableList
import ui.feice.mochi.basic.MochiBarGroup
import ui.feice.mochi.basic.MochiBarGroupDropdownItem
import ui.feice.mochi.basic.MochiBarGroupItem
import ui.feice.mochi.basic.MochiButton
import ui.feice.mochi.basic.MochiButtonDefaults
import ui.feice.mochi.basic.MochiButtonStyle
import ui.feice.mochi.basic.MochiEmptyState
import ui.feice.mochi.basic.MochiItem
import ui.feice.mochi.basic.MochiSearchBar
import ui.feice.mochi.basic.MochiSwitch
import ui.feice.mochi.basic.Text
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.list.MochiListCard
import ui.feice.mochi.list.MochiListDefaults
import ui.feice.mochi.list.MochiListStatusChip
import ui.feice.mochi.navigation.MochiLazyLargeTitleScaffold
import ui.feice.mochi.theme.MochiTheme

/**
 * 模块：卡片列表，支持搜索、排序、启停、执行动作 / 打开 WebUI、卸载与撤销。
 * 数据来自 [MockData.modules]，启停和卸载状态在本地生效。
 */
@Composable
fun ModuleScreen(modifier: Modifier = Modifier) {
    val modules = remember { mutableStateListOf<ModuleEntry>().apply { addAll(MockData.modules) } }
    var query by rememberSaveable { mutableStateOf("") }
    var actionFirst by rememberSaveable { mutableStateOf(false) }
    var enabledFirst by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    val searching = query.isNotBlank()
    val more = stringResource(R.string.mochi_more)
    val visible =
        remember(query, actionFirst, enabledFirst) {
            modules
                .filter {
                    !searching ||
                        it.name.contains(query, ignoreCase = true) ||
                        it.description.contains(query, ignoreCase = true)
                }
                .sortedWith(
                    when {
                        actionFirst -> compareByDescending<ModuleEntry> { it.hasAction }.thenByDescending { it.id }
                        enabledFirst -> compareByDescending<ModuleEntry> { it.enabled && !it.remove }
                        else -> compareBy { it.id }
                    },
                )
        }

    MochiLazyLargeTitleScaffold(
        title = stringResource(R.string.module),
        modifier = modifier,
        actions = {
            MochiBarGroup(navId = "module.actions") {
                MochiBarGroupItem(onClick = {}, navId = "module.repo", contentKey = "storefront") {
                    MochiIonicon("storefront")
                }
                MochiBarGroupItem(onClick = {}, navId = "module.install", contentKey = "add") {
                    MochiIonicon("add")
                }
                MochiBarGroupDropdownItem(
                    expanded = menu,
                    onExpandedChange = { menu = it },
                    items =
                        listOf(
                            MochiItem(stringResource(R.string.module_sort_action_first), icon = checkIcon(actionFirst)),
                            MochiItem(stringResource(R.string.module_sort_enabled_first), icon = checkIcon(enabledFirst)),
                        ).toImmutableList(),
                    onItemClick = { index ->
                        if (index == 0) actionFirst = !actionFirst else enabledFirst = !enabledFirst
                    },
                    navId = "module.more",
                    contentKey = "filter",
                ) { MochiIonicon("filter") }
            }
        },
        searchBar = {
            MochiSearchBar(
                query = query,
                onQueryChange = { query = it },
                placeholder = stringResource(R.string.mochi_search_modules),
                onCancel = { query = "" },
            )
        },
    ) {
        when {
            visible.isEmpty() -> item(key = "empty") {
                MochiEmptyState(
                    stringResource(if (searching) R.string.mochi_no_results else R.string.module_empty),
                    icon = { MochiIonicon(if (searching) "search" else "extension-puzzle", size = 44.dp) },
                )
            }

            else -> visible.forEach { module ->
                item(key = module.id) {
                    ModuleCard(
                        module = module,
                        onToggle = {
                            val index = modules.indexOfFirst { it.id == module.id }
                            if (index >= 0) modules[index] = module.copy(enabled = !module.enabled)
                        },
                        onUninstall = {
                            val index = modules.indexOfFirst { it.id == module.id }
                            if (index >= 0) modules[index] = module.copy(remove = true, enabled = false)
                        },
                        onUndoUninstall = {
                            val index = modules.indexOfFirst { it.id == module.id }
                            if (index >= 0) modules[index] = module.copy(remove = false)
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModuleCard(
    module: ModuleEntry,
    onToggle: () -> Unit,
    onUninstall: () -> Unit,
    onUndoUninstall: () -> Unit,
) {
    val cs = MochiTheme.colorScheme
    var expanded by rememberSaveable(module.id) { mutableStateOf(false) }
    val actionLabel = stringResource(R.string.action)
    val openLabel = stringResource(R.string.open)

    MochiListCard {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = MochiListDefaults.RowPadding, end = MochiListDefaults.RowPadding, top = 14.dp, bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    module.name,
                    style = MochiTheme.textStyles.body.copy(fontWeight = FontWeight.SemiBold),
                    color = if (module.remove) cs.onSurfaceVariant else cs.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                MochiSwitch(
                    module.enabled,
                    { if (it != module.enabled) onToggle() },
                    enabled = !module.remove,
                    modifier = Modifier.offset(y = (-3).dp),
                )
            }
            Row(
                Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    module.remove -> {
                        MochiListStatusChip(
                            stringResource(R.string.uninstall),
                            containerColor = cs.error.copy(alpha = 0.12f),
                            contentColor = cs.error,
                        )
                    }

                    module.metaModule -> {
                        MochiListStatusChip("META", containerColor = cs.tertiary.copy(alpha = 0.12f), contentColor = cs.tertiary)
                    }
                }
                if (module.version.isNotBlank()) {
                    MochiListStatusChip(
                        module.version,
                        containerColor = cs.onSurface.copy(alpha = 0.06f),
                        contentColor = cs.onSurfaceVariant,
                    )
                }
                if (module.author.isNotBlank()) {
                    Text(
                        module.author,
                        style = MochiTheme.textStyles.footnote,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (module.description.isNotBlank()) {
                Text(
                    module.description,
                    modifier =
                        Modifier
                            .padding(top = 10.dp)
                            .clickable(remember { MutableInteractionSource() }, indication = null) { expanded = !expanded },
                    style = MochiTheme.textStyles.subhead.copy(textDecoration = if (module.remove) TextDecoration.LineThrough else null),
                    color = cs.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                Modifier.padding(top = 12.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlowRow(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    val usable = module.enabled && !module.remove
                    if (usable && module.hasAction) CardButton("play", actionLabel, {})
                    if (usable && module.hasWebUi) CardButton("globe", openLabel, {})
                    if (module.hasUpdate && !module.remove) CardButton("cloud-download", stringResource(R.string.module_update), {})
                    if (module.remove) CardButton("arrow-undo", stringResource(R.string.undo), onUndoUninstall)
                }
                if (!module.remove) {
                    val trashLabel = stringResource(R.string.uninstall)
                    MochiButton(
                        onClick = onUninstall,
                        style = MochiButtonStyle.Tonal,
                        colors =
                            MochiButtonDefaults.colors(MochiButtonStyle.Tonal).copy(
                                container = cs.onSurface.copy(alpha = 0.06f),
                                content = cs.error,
                                pressedContainer = cs.error.copy(alpha = 0.16f),
                                glow = cs.error,
                            ),
                        minHeight = CardButtonHeight,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.semantics { contentDescription = trashLabel },
                    ) {
                        MochiIonicon("trash", size = 16.dp, tint = cs.error)
                    }
                }
            }
        }
    }
}

private val CardButtonHeight = 32.dp

@Composable
private fun CardButton(
    icon: String,
    label: String,
    onClick: () -> Unit,
) {
    val colors = MochiButtonDefaults.colors(MochiButtonStyle.Tonal)
    MochiButton(
        onClick = onClick,
        style = MochiButtonStyle.Tonal,
        colors = colors,
        minHeight = CardButtonHeight,
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        MochiIonicon(icon, size = 16.dp, tint = colors.content)
        Text(label, style = MochiTheme.textStyles.subhead, color = colors.content)
    }
}

private fun checkIcon(on: Boolean): (@Composable (Color) -> Unit)? =
    if (on) {
        { tint -> MochiIonicon("checkmark", tint = tint) }
    } else {
        null
    }
