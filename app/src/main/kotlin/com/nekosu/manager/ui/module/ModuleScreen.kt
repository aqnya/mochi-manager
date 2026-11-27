package com.nekosu.manager.ui.module

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nekosu.manager.R
import com.nekosu.manager.data.LocalModuleImport
import com.nekosu.manager.data.ModuleEntry
import com.nekosu.manager.data.ModuleInstallSession
import com.nekosu.manager.data.ModuleStore
import com.nekosu.manager.data.ModuleUpdate
import com.nekosu.manager.data.ModuleUpdateInfo
import com.nekosu.manager.data.StagedModule
import com.nekosu.manager.ui.webui.webUiIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import ui.feice.mochi.basic.MochiBarGroup
import ui.feice.mochi.basic.MochiBarGroupDropdownItem
import ui.feice.mochi.basic.MochiBarGroupItem
import ui.feice.mochi.basic.MochiButton
import ui.feice.mochi.basic.MochiButtonDefaults
import ui.feice.mochi.basic.MochiButtonStyle
import ui.feice.mochi.basic.MochiDialog
import ui.feice.mochi.basic.MochiDialogAction
import ui.feice.mochi.basic.MochiDialogActionRole
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
 *
 * 列表来自 ncore 的 `module list --json`（真实的 /data/adb/modules），启停/卸载全部
 * 写回磁盘，不在本地维护一份会跟真实状态脱节的状态；右上角的商店按钮进入模块仓库。
 */
@Composable
fun ModuleScreen(
    onOpenRepo: () -> Unit = {},
    onOpenInstall: () -> Unit = {},
    reloadKey: Int = 0,
    modifier: Modifier = Modifier,
    loader: suspend () -> List<ModuleEntry>? = { ModuleStore.load() },
) {
    val scope = rememberCoroutineScope()
    val modules = remember { mutableStateListOf<ModuleEntry>() }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var actionFirst by rememberSaveable { mutableStateOf(false) }
    var enabledFirst by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    // 首次进入和下拉刷新共用同一条挂起链路：onRefresh 要等它返回，指示器才会复位。
    suspend fun loadModules() {
        loading = true
        val loaded = loader()
        failed = loaded == null
        modules.clear()
        // ncore 在 module.prop 缺 id 时用目录名兜底，理论上可能撞车；
        // 重复 key 会让 LazyColumn 直接抛异常，这里先按 id 去重。
        modules.addAll(loaded.orEmpty().distinctBy { it.id })
        loading = false
    }

    // 模块列表来自 ncore（真实的 /data/adb/modules），不再有本地假数据
    LaunchedEffect(refreshKey, reloadKey) { loadModules() }

    val context = LocalContext.current
    var importPhase by remember { mutableStateOf<ImportPhase>(ImportPhase.Idle) }

    // 安装交给输出页：脚本的进度、交互提示都要看得见，页面在，任务也在
    fun beginInstall(items: List<StagedModule>) {
        ModuleInstallSession.start(items)
        importPhase = ImportPhase.Idle
        onOpenInstall()
    }

    // 和 KernelSU 的入口一致：ACTION_GET_CONTENT + application/zip + 多选
    val pickZip =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
            val data = result.data ?: return@rememberLauncherForActivityResult
            val uris =
                buildList {
                    val clip = data.clipData
                    if (clip != null) {
                        for (i in 0 until clip.itemCount) clip.getItemAt(i)?.uri?.let(::add)
                    } else {
                        data.data?.let(::add)
                    }
                }
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            scope.launch {
                val staged =
                    try {
                        LocalModuleImport.stageAll(context, uris)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        importPhase = ImportPhase.Failed(e.message ?: "读不到所选文件")
                        return@launch
                    }
                // 单个包直接装；多个先列出来确认，和 PR 里的交互一致
                if (staged.size == 1) beginInstall(staged) else importPhase = ImportPhase.Confirm(staged)
            }
        }

    // —— 模块更新：检查 updateJson → 确认 → 下载 → 交给安装输出页 ——
    var updatePhase by remember { mutableStateOf<UpdatePhase>(UpdatePhase.Idle) }
    var updateJob by remember { mutableStateOf<Job?>(null) }

    fun beginUpdateCheck(module: ModuleEntry) {
        if (updateJob?.isActive == true) return
        updatePhase = UpdatePhase.Checking
        updateJob =
            scope.launch {
                updatePhase =
                    when (val result = ModuleUpdate.check(module)) {
                        is ModuleUpdate.CheckResult.Available -> UpdatePhase.Confirm(module, result.info)
                        is ModuleUpdate.CheckResult.UpToDate -> UpdatePhase.UpToDate(result.version)
                        ModuleUpdate.CheckResult.NoUpdateUrl -> UpdatePhase.Failed(context.getString(R.string.module_update_no_url))
                        is ModuleUpdate.CheckResult.Failed ->
                            UpdatePhase.Failed(context.getString(R.string.module_update_check_failed, result.message))
                    }
            }
    }

    fun beginUpdateDownload(
        module: ModuleEntry,
        info: ModuleUpdateInfo,
    ) {
        updatePhase = UpdatePhase.Downloading(module, 0f)
        updateJob =
            scope.launch {
                try {
                    val staged =
                        ModuleUpdate.download(context, module, info) { progress ->
                            updatePhase = UpdatePhase.Downloading(module, progress)
                        }
                    updatePhase = UpdatePhase.Idle
                    // 下载好的包走和本地安装完全相同的输出页
                    ModuleInstallSession.start(listOf(staged))
                    onOpenInstall()
                } catch (e: CancellationException) {
                    updatePhase = UpdatePhase.Idle
                    throw e
                } catch (e: Throwable) {
                    updatePhase = UpdatePhase.Failed(context.getString(R.string.module_update_download_failed, e.message ?: "网络错误"))
                }
            }
    }

    fun cancelUpdate() {
        updateJob?.cancel()
        updateJob = null
        updatePhase = UpdatePhase.Idle
    }

    val searching = query.isNotBlank()
    val installLabel = stringResource(R.string.module_install)
    // 这里不能用 remember(query, …) 包住：modules 是 SnapshotStateList，不在 key 里，
    // 首次组合时列表还是空的，缓存会一直返回那份空结果，加载完成后界面仍然是空态。
    val visible =
        modules
            .filter {
                !searching ||
                    it.name.contains(query, ignoreCase = true) ||
                    it.id.contains(query, ignoreCase = true) ||
                    it.description.contains(query, ignoreCase = true)
            }
            .sortedWith(
                when {
                    actionFirst -> compareByDescending<ModuleEntry> { it.hasAction }.thenByDescending { it.id }
                    enabledFirst -> compareByDescending<ModuleEntry> { it.enabled && !it.remove }
                    else -> compareBy { it.id }
                },
            )

    MochiLazyLargeTitleScaffold(
        title = stringResource(R.string.module),
        modifier = modifier,
        actions = {
            MochiBarGroup(navId = "module.actions") {
                MochiBarGroupItem(onClick = onOpenRepo, navId = "module.repo", contentKey = "storefront") {
                    MochiIonicon("storefront")
                }
                MochiBarGroupItem(
                    onClick = {
                        pickZip.launch(
                            Intent(Intent.ACTION_GET_CONTENT).apply {
                                type = "application/zip"
                                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                            },
                        )
                    },
                    modifier = Modifier.semantics { contentDescription = installLabel },
                    navId = "module.install",
                    contentKey = "add",
                ) { MochiIonicon("add") }
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
        // 加载要在 onRefresh 里跑完再返回，库才会复位刷新指示器
        onRefresh = { loadModules() },
    ) {
        when {
            loading && modules.isEmpty() -> item(key = "loading") {
                Column(
                    Modifier.fillMaxWidth().padding(top = 60.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    androidx.compose.material3.CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.width(28.dp))
                }
            }

            visible.isEmpty() -> item(key = "empty") {
                MochiEmptyState(
                    stringResource(
                        when {
                            searching -> R.string.mochi_no_results
                            failed -> R.string.module_load_failed
                            else -> R.string.module_empty
                        },
                    ),
                    icon = {
                        MochiIonicon(
                            when {
                                searching -> "search"
                                failed -> "alert-circle"
                                else -> "extension-puzzle"
                            },
                            size = 44.dp,
                        )
                    },
                )
            }

            else -> visible.forEach { module ->
                item(key = module.id) {
                    ModuleCard(
                        module = module,
                        onToggle = {
                            scope.launch {
                                if (ModuleStore.setEnabled(module.id, !module.enabled)) refreshKey++
                            }
                        },
                        onOpenWebUi = { context.startActivity(webUiIntent(context, module.id)) },
                        onUpdate = { beginUpdateCheck(module) },
                        onUninstall = {
                            scope.launch {
                                if (ModuleStore.uninstall(module.id)) refreshKey++
                            }
                        },
                        onUndoUninstall = {
                            scope.launch {
                                if (ModuleStore.undoUninstall(module.id)) refreshKey++
                            }
                        },
                    )
                }
            }
        }
    }

    ImportDialogs(
        phase = importPhase,
        onDismiss = { importPhase = ImportPhase.Idle },
        onConfirm = { items -> beginInstall(items) },
    )

    UpdateDialogs(
        phase = updatePhase,
        onDismiss = { updatePhase = UpdatePhase.Idle },
        onConfirm = { module, info -> beginUpdateDownload(module, info) },
        onCancelBusy = ::cancelUpdate,
    )
}

/** 更新链路的界面状态。Checking / Downloading 是长任务，取消要走 [cancelUpdate]。 */
private sealed interface UpdatePhase {
    data object Idle : UpdatePhase

    data object Checking : UpdatePhase

    data class Confirm(
        val module: ModuleEntry,
        val info: ModuleUpdateInfo,
    ) : UpdatePhase

    data class Downloading(
        val module: ModuleEntry,
        val progress: Float,
    ) : UpdatePhase

    data class UpToDate(
        val version: String,
    ) : UpdatePhase

    data class Failed(
        val message: String,
    ) : UpdatePhase
}

/** 更新过程中的各状态弹窗；Checking/Downloading 的关闭就是取消任务。 */
@Composable
private fun UpdateDialogs(
    phase: UpdatePhase,
    onDismiss: () -> Unit,
    onConfirm: (ModuleEntry, ModuleUpdateInfo) -> Unit,
    onCancelBusy: () -> Unit,
) {
    val icon: @Composable () -> Unit = { MochiIonicon("cloud-download", size = 44.dp) }
    when (phase) {
        UpdatePhase.Idle -> Unit

        UpdatePhase.Checking ->
            MochiDialog(
                visible = true,
                onDismissRequest = onCancelBusy,
                title = stringResource(R.string.module_update_checking),
                icon = icon,
                actions = persistentListOf(),
            )

        is UpdatePhase.Confirm ->
            MochiDialog(
                visible = true,
                onDismissRequest = onDismiss,
                title = stringResource(R.string.module_update_confirm_title, phase.module.name),
                message = stringResource(R.string.module_update_confirm_message, phase.info.version),
                icon = icon,
                actions =
                    persistentListOf(
                        MochiDialogAction(stringResource(android.R.string.cancel), MochiDialogActionRole.Cancel),
                        MochiDialogAction(
                            stringResource(R.string.module_update_download),
                            MochiDialogActionRole.Primary,
                            onClick = { onConfirm(phase.module, phase.info) },
                        ),
                    ),
            )

        is UpdatePhase.Downloading ->
            MochiDialog(
                visible = true,
                onDismissRequest = onCancelBusy,
                title = stringResource(R.string.repo_downloading, (phase.progress * 100).toInt()),
                message = phase.module.name,
                icon = icon,
                actions = persistentListOf(),
            )

        is UpdatePhase.UpToDate ->
            MochiDialog(
                visible = true,
                onDismissRequest = onDismiss,
                title = stringResource(R.string.module_update_up_to_date_title),
                message = stringResource(R.string.module_update_up_to_date, phase.version),
                icon = icon,
                actions =
                    persistentListOf(
                        MochiDialogAction(stringResource(android.R.string.ok), MochiDialogActionRole.Primary, onClick = onDismiss),
                    ),
            )

        is UpdatePhase.Failed ->
            MochiDialog(
                visible = true,
                onDismissRequest = onDismiss,
                title = stringResource(R.string.module_update),
                message = phase.message,
                icon = { MochiIonicon("alert-circle", size = 44.dp) },
                actions =
                    persistentListOf(
                        MochiDialogAction(stringResource(android.R.string.ok), MochiDialogActionRole.Primary, onClick = onDismiss),
                    ),
            )
    }
}

/** 本地 ZIP 入口的两种对话框：多选确认、选包失败。进度与结果都在安装输出页里。 */
@Composable
private fun ImportDialogs(
    phase: ImportPhase,
    onDismiss: () -> Unit,
    onConfirm: (List<StagedModule>) -> Unit,
) {
    val icon: @Composable () -> Unit = { MochiIonicon("extension-puzzle", size = 44.dp) }
    when (phase) {
        ImportPhase.Idle -> Unit

        is ImportPhase.Confirm ->
            MochiDialog(
                visible = true,
                onDismissRequest = onDismiss,
                title = stringResource(R.string.module_install_title),
                message =
                    stringResource(
                        R.string.module_install_prompt_with_name,
                        phase.items.mapIndexed { i, item -> "\n${i + 1}. ${item.displayName}" }.joinToString(""),
                    ),
                icon = icon,
                actions =
                    persistentListOf(
                        MochiDialogAction(stringResource(android.R.string.cancel), MochiDialogActionRole.Cancel),
                        MochiDialogAction(
                            stringResource(R.string.module_install_action),
                            MochiDialogActionRole.Primary,
                            onClick = { onConfirm(phase.items) },
                        ),
                    ),
            )

        is ImportPhase.Failed ->
            MochiDialog(
                visible = true,
                onDismissRequest = onDismiss,
                title = stringResource(R.string.module_install_result),
                message = phase.message,
                icon = { MochiIonicon("alert-circle", size = 44.dp) },
                actions =
                    persistentListOf(
                        MochiDialogAction(stringResource(android.R.string.ok), MochiDialogActionRole.Primary, onClick = onDismiss),
                    ),
            )
    }
}

/** 加号入口的状态机。 */
private sealed interface ImportPhase {
    data object Idle : ImportPhase

    /** 多选时先列文件确认；单个包跳过这一步。 */
    data class Confirm(
        val items: List<StagedModule>,
    ) : ImportPhase

    /** 选的不是能读的 ZIP，或复制到缓存失败。 */
    data class Failed(
        val message: String,
    ) : ImportPhase
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModuleCard(
    module: ModuleEntry,
    onToggle: () -> Unit,
    onOpenWebUi: () -> Unit,
    onUpdate: () -> Unit,
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
                    if (usable && module.hasWebUi) CardButton("globe", openLabel, onOpenWebUi)
                    if (module.hasUpdate && !module.remove) CardButton("cloud-download", stringResource(R.string.module_update), onUpdate)
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
