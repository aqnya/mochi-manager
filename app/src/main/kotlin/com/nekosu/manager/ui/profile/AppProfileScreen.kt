package com.nekosu.manager.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nekosu.manager.R
import com.nekosu.manager.data.AppGroup
import com.nekosu.manager.data.SuCapabilities
import com.nekosu.manager.data.SuCapabilityCategories
import com.nekosu.manager.data.SuGroups
import com.nekosu.manager.data.SuProfile
import com.nekosu.manager.data.SuProfileStore
import com.nekosu.manager.ui.mochi.AppIcon
import com.nekosu.manager.ui.mochi.KsuTile
import com.nekosu.manager.ui.mochi.KsuTileColors
import com.nekosu.manager.ui.mochi.MochiFootnote
import kotlinx.collections.immutable.persistentListOf
import ui.feice.mochi.basic.MochiButton
import ui.feice.mochi.basic.MochiButtonStyle
import ui.feice.mochi.basic.MochiDialog
import ui.feice.mochi.basic.MochiDialogAction
import ui.feice.mochi.basic.MochiDialogActionRole
import ui.feice.mochi.basic.MochiDialogScrollContent
import ui.feice.mochi.basic.MochiSwitch
import ui.feice.mochi.basic.MochiTextField
import ui.feice.mochi.basic.Text
import ui.feice.mochi.list.MochiListCard
import ui.feice.mochi.list.MochiListPickerRow
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.MochiListSection
import ui.feice.mochi.navigation.MochiBackButton
import ui.feice.mochi.navigation.MochiLazyCompactTitleScaffold
import ui.feice.mochi.theme.MochiTheme

/**
 * 应用配置页：对齐 KernelSU 的 AppProfile —— 先决定是否放行，再配置它拿到的是
 * 什么身份（uid/gid/附加组）、哪些能力位、命名空间和 SELinux 域，最后是卸载模块。
 *
 * 这里的 [group] 是「一个 uid」而不是一个包：共享 uid 时配置会同时作用于组内所有应用，
 * 所以页面上要明确列出组员，避免用户以为只改了当前这一个。
 *
 * 所有改动即时写入 [SuProfileStore]，没有「保存」按钮，符合 iOS 设置页的习惯。
 */
@Composable
fun AppProfileScreen(
    group: AppGroup,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // 返回手势交给外层的 MochiNavHost（PredictiveBackHandler 跟手转场），这里不再单独拦截
    LaunchedEffect(Unit) { SuProfileStore.init(context) }
    val store by SuProfileStore.profiles.collectAsState()
    val default = SuProfile.default(group.primary.packageName, group.uid)
    val profile = store[group.uid] ?: default

    fun update(next: SuProfile) = SuProfileStore.save(context, next)

    MochiLazyCompactTitleScaffold(
        title = group.title,
        subtitle = if (group.shared) stringResource(R.string.group_shared_uid, group.uid) else group.primary.packageName,
        modifier = modifier,
        navigationIcon = { MochiBackButton(onClick = onBack) },
    ) {
        item(key = "header") { ProfileHeader(group) }

        if (group.shared) {
            item(key = "shared") {
                MochiListSection(header = stringResource(R.string.profile_section_shared)) {
                    group.apps.forEach { member ->
                        MochiListRow(
                            member.label,
                            subtitle = member.packageName,
                            leading = { AppIcon(member.packageName, member.label, KsuTileColors.Blue, 28.dp) },
                            leadingSize = 28.dp,
                        )
                    }
                }
            }
            item(key = "shared-hint") {
                MochiFootnote(
                    stringResource(R.string.profile_shared_hint, group.uid),
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }

        item(key = "access") {
            MochiListSection(header = stringResource(R.string.profile_section_access)) {
                MochiListRow(
                    stringResource(R.string.profile_allow_su),
                    subtitle = stringResource(R.string.profile_allow_su_summary),
                    trailing = {
                        MochiSwitch(profile.allowSu, { update(profile.copy(allowSu = it)) })
                    },
                )
            }
        }

        if (profile.allowSu) {
            item(key = "identity") {
                MochiListSection(header = stringResource(R.string.profile_section_identity)) {
                    NumberRow(stringResource(R.string.profile_uid), profile.runAsUid) {
                        update(profile.copy(runAsUid = it))
                    }
                    NumberRow(stringResource(R.string.profile_gid), profile.runAsGid) {
                        update(profile.copy(runAsGid = it))
                    }
                    MultiSelectRow(
                        title = stringResource(R.string.profile_groups),
                        summary =
                            profile.groups
                                .mapNotNull { gid -> SuGroups.firstOrNull { it.gid == gid }?.name }
                                .sorted()
                                .joinToString(", "),
                        options = SuGroups,
                        selected = profile.groups.mapNotNull { gid -> SuGroups.firstOrNull { it.gid == gid } }.toSet(),
                        label = { it.name },
                        description = { "${it.gid} · ${it.desc}" },
                    ) { picked -> update(profile.copy(groups = picked.map { it.gid }.toSet())) }
                    MochiListPickerRow(
                        stringResource(R.string.profile_namespace),
                        options =
                            persistentListOf(
                                stringResource(R.string.profile_namespace_inherit),
                                stringResource(R.string.profile_namespace_global),
                                stringResource(R.string.profile_namespace_individual),
                            ),
                        selectedIndex = profile.namespace.coerceIn(0, 2),
                        onSelect = { update(profile.copy(namespace = it)) },
                    )
                    TextRow(stringResource(R.string.profile_selinux), profile.context) {
                        update(profile.copy(context = it))
                    }
                }
            }

            item(key = "caps") {
                MochiListSection(header = stringResource(R.string.profile_capabilities)) {
                    CapabilitySelectRow(
                        title = stringResource(R.string.profile_capabilities_row),
                        summary =
                            if (profile.capabilities.isEmpty()) {
                                stringResource(R.string.profile_none)
                            } else {
                                profile.capabilities.sorted().joinToString(", ")
                            },
                        selected = profile.capabilities,
                    ) { picked -> update(profile.copy(capabilities = picked)) }
                }
            }
        }

        item(key = "module") {
            MochiListSection(header = stringResource(R.string.profile_section_module)) {
                MochiListRow(
                    stringResource(R.string.profile_umount_modules),
                    subtitle = stringResource(R.string.profile_umount_modules_summary),
                    trailing = {
                        MochiSwitch(profile.umountModules, { update(profile.copy(umountModules = it)) })
                    },
                )
            }
        }

        if (profile != default) {
            item(key = "reset") {
                MochiListSection {
                    MochiListRow(
                        stringResource(R.string.profile_reset),
                        leading = { KsuTile("refresh", KsuTileColors.Red) },
                        destructive = true,
                        onClick = { SuProfileStore.reset(context, group.uid) },
                    )
                }
            }
        }

        item(key = "footer") {
            MochiFootnote(
                stringResource(R.string.profile_footer),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun ProfileHeader(group: AppGroup) {
    val app = group.primary
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AppIcon(app.packageName, app.label, KsuTileColors.Blue, 64.dp)
        Text(
            group.title,
            style = MochiTheme.textStyles.navTitle.copy(textAlign = TextAlign.Center),
            color = MochiTheme.colorScheme.onSurface,
        )
        val meta =
            buildList {
                add("UID ${group.uid}")
                if (group.userId != 0) add("USER ${group.userId}")
                if (group.shared) add(stringResource(R.string.group_contains_apps, group.apps.size))
            }.joinToString(" · ")
        Text(
            meta,
            style = MochiTheme.textStyles.footnote.copy(textAlign = TextAlign.Center),
            color = MochiTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 整数编辑行：点开对话框输入，确认后回调。 */
@Composable
private fun NumberRow(
    title: String,
    value: Int,
    onChange: (Int) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(value) { mutableStateOf(value.toString()) }
    val parsed = draft.toIntOrNull()
    MochiListRow(title, value = value.toString(), chevron = true, onClick = { editing = true })
    MochiDialog(
        visible = editing,
        onDismissRequest = {
            editing = false
            draft = value.toString()
        },
        title = title,
        actions =
            persistentListOf(
                MochiDialogAction(stringResource(android.R.string.cancel), MochiDialogActionRole.Cancel) {
                    editing = false
                    draft = value.toString()
                },
                MochiDialogAction(
                    stringResource(android.R.string.ok),
                    MochiDialogActionRole.Primary,
                    enabled = parsed != null && parsed >= 0,
                    onClick = {
                        parsed?.let(onChange)
                        editing = false
                    },
                ),
            ),
    ) {
        MochiTextField(
            value = draft,
            onValueChange = { draft = it.filter(Char::isDigit) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 文本编辑行，带 SELinux 域格式校验。 */
@Composable
private fun TextRow(
    title: String,
    value: String,
    onChange: (String) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(value) { mutableStateOf(value) }
    val valid = remember(draft) { draft.matches(Regex("^[a-z_]+:[a-z0-9_]+:[a-z0-9_]+(:[a-z0-9_,]+)?$")) }
    MochiListRow(title, value = value, chevron = true, onClick = { editing = true })
    MochiDialog(
        visible = editing,
        onDismissRequest = {
            editing = false
            draft = value
        },
        title = title,
        message = stringResource(R.string.profile_selinux_hint),
        actions =
            persistentListOf(
                MochiDialogAction(stringResource(android.R.string.cancel), MochiDialogActionRole.Cancel) {
                    editing = false
                    draft = value
                },
                MochiDialogAction(
                    stringResource(android.R.string.ok),
                    MochiDialogActionRole.Primary,
                    enabled = valid,
                    onClick = {
                        onChange(draft)
                        editing = false
                    },
                ),
            ),
    ) {
        MochiTextField(
            value = draft,
            onValueChange = { draft = it.trim() },
            error = if (valid) null else "",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 多选行：弹窗里勾选，确认后整体回调。 */
@Composable
private fun <T> MultiSelectRow(
    title: String,
    summary: String,
    options: List<T>,
    selected: Set<T>,
    label: (T) -> String,
    description: (T) -> String,
    onConfirm: (Set<T>) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var draft by remember(selected) { mutableStateOf(selected) }
    MochiListRow(title, subtitle = summary, chevron = true, onClick = { open = true })
    MochiDialog(
        visible = open,
        onDismissRequest = {
            open = false
            draft = selected
        },
        title = title,
        actions =
            persistentListOf(
                MochiDialogAction(stringResource(android.R.string.cancel), MochiDialogActionRole.Cancel) {
                    open = false
                    draft = selected
                },
                MochiDialogAction(stringResource(android.R.string.ok), MochiDialogActionRole.Primary) {
                    onConfirm(draft)
                    open = false
                },
            ),
    ) {
        MochiDialogScrollContent(maxHeight = 360.dp) {
            MochiListCard {
                options.forEach { option ->
                    val on = option in draft
                    MochiListRow(
                        label(option),
                        subtitle = description(option),
                        checked = on,
                        onClick = { draft = if (on) draft - option else draft + option },
                    )
                }
            }
        }
    }
}

/**
 * 能力位选择行：全部 41 个 Linux capability，弹窗内搜索过滤、按类分组展示。
 *
 * 相比通用 [MultiSelectRow] 的增强：平铺 41 项难以定位，这里加了搜索框
 * （按名字或描述过滤，名字带不带 CAP_ 前缀都能搜到）、按类别的段头、
 * 「默认/全选/清空」快捷键与已选计数。
 */
@Composable
private fun CapabilitySelectRow(
    title: String,
    summary: String,
    selected: Set<String>,
    onConfirm: (Set<String>) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var draft by remember(selected) { mutableStateOf(selected) }
    var query by remember { mutableStateOf("") }
    MochiListRow(title, subtitle = summary, chevron = true, onClick = { open = true })
    MochiDialog(
        visible = open,
        onDismissRequest = {
            open = false
            draft = selected
            query = ""
        },
        title = title,
        actions =
            persistentListOf(
                MochiDialogAction(stringResource(android.R.string.cancel), MochiDialogActionRole.Cancel) {
                    open = false
                    draft = selected
                    query = ""
                },
                MochiDialogAction(stringResource(android.R.string.ok), MochiDialogActionRole.Primary) {
                    onConfirm(draft)
                    open = false
                    query = ""
                },
            ),
    ) {
        MochiTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.profile_capabilities_search),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MochiButton(style = MochiButtonStyle.Tonal, onClick = { draft = SuProfile.DEFAULT_CAPABILITIES }) {
                Text(stringResource(R.string.capabilities_default), style = MochiTheme.textStyles.footnote)
            }
            MochiButton(style = MochiButtonStyle.Tonal, onClick = { draft = SuCapabilities.map { it.name }.toSet() }) {
                Text(stringResource(R.string.select_all), style = MochiTheme.textStyles.footnote)
            }
            MochiButton(style = MochiButtonStyle.Tonal, onClick = { draft = emptySet() }) {
                Text(stringResource(R.string.clear_all), style = MochiTheme.textStyles.footnote)
            }
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.profile_capabilities_selected, draft.size, SuCapabilities.size),
                style = MochiTheme.textStyles.footnote,
                color = MochiTheme.colorScheme.onSurfaceVariant,
            )
        }
        MochiDialogScrollContent(maxHeight = 420.dp) {
            val q = query.trim()
            val matches =
                remember(q) {
                    if (q.isEmpty()) {
                        SuCapabilities
                    } else {
                        SuCapabilities.filter {
                            it.name.contains(q, ignoreCase = true) ||
                                it.name.removePrefix("CAP_").contains(q, ignoreCase = true) ||
                                it.desc.contains(q, ignoreCase = true)
                        }
                    }
                }
            if (matches.isEmpty()) {
                Text(
                    stringResource(R.string.mochi_no_results),
                    style = MochiTheme.textStyles.footnote.copy(textAlign = TextAlign.Center),
                    color = MochiTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                )
            }
            SuCapabilityCategories.forEach { category ->
                val group = matches.filter { it.category == category }
                if (group.isEmpty()) return@forEach
                Text(
                    category,
                    style = MochiTheme.textStyles.footnote,
                    color = MochiTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
                )
                MochiListCard {
                    group.forEach { cap ->
                        val on = cap.name in draft
                        MochiListRow(
                            cap.name.removePrefix("CAP_"),
                            subtitle = cap.desc,
                            checked = on,
                            onClick = {
                                draft = if (on) draft - cap.name else draft + cap.name
                            },
                        )
                    }
                }
            }
        }
    }
}
