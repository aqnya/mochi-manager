package com.nekosu.manager.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.nekosu.manager.R
import com.nekosu.manager.data.Bugreport
import com.nekosu.manager.data.BugreportException
import com.nekosu.manager.data.LogExport
import com.nekosu.manager.data.RootUnavailableException
import com.nekosu.manager.data.ShellTimeoutException
import com.nekosu.manager.ui.mochi.KsuSwitchRow
import com.nekosu.manager.ui.mochi.KsuTile
import com.nekosu.manager.ui.mochi.KsuTileColors
import com.nekosu.manager.ui.mochi.MochiCredit
import java.io.File
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ui.feice.mochi.basic.MochiDialog
import ui.feice.mochi.basic.MochiDialogAction
import ui.feice.mochi.basic.MochiDialogActionRole
import ui.feice.mochi.list.MochiListPickerRow
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.MochiListSection
import ui.feice.mochi.navigation.MochiLazyLargeTitleScaffold

/**
 * 设置：更新、外观、功能开关、高级选项和关于。所有开关都是本地状态，
 * 结构对齐 KernelSU Manager 的 Mochi 设置页。
 *
 * 已接真实能力：「主题」进入 [ThemeScreen]，「保存日志 / 发送日志」生成 bugreport（[Bugreport]）
 * 并导出或分享。其余开关仍是移植中的占位。
 */
@Composable
fun SettingsScreen(
    onOpenTheme: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var checkUpdate by rememberSaveable { mutableStateOf(true) }
    var checkModuleUpdate by rememberSaveable { mutableStateOf(true) }
    var uiMode by rememberSaveable { mutableIntStateOf(0) }
    var kernelUmount by rememberSaveable { mutableStateOf(true) }
    var selinuxHide by rememberSaveable { mutableStateOf(true) }
    var sulog by rememberSaveable { mutableStateOf(true) }
    var adbRoot by rememberSaveable { mutableStateOf(false) }
    var softReboot by rememberSaveable { mutableStateOf(false) }
    var defaultUmount by rememberSaveable { mutableStateOf(false) }
    var webDebug by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var collecting by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Pair<String, String>?>(null) }
    // 没有 root 时先收集，再问一句要不要继续导出——不直接失败
    var partial by remember { mutableStateOf<PartialLog?>(null) }

    val failTitle = stringResource(R.string.log_failed)
    val needRoot = stringResource(R.string.log_need_root)
    val exportFailed = stringResource(R.string.log_export_failed)
    val savedTitle = stringResource(R.string.log_saved)
    val sendLabel = stringResource(R.string.send_log)

    fun errorText(e: Throwable): String =
        when (e) {
            is RootUnavailableException -> needRoot
            is ShellTimeoutException -> e.message ?: failTitle
            is BugreportException -> e.message ?: failTitle
            else -> e.message ?: e.javaClass.simpleName
        }

    fun deliver(
        action: LogAction,
        file: File,
        noRoot: Boolean = false,
    ) {
        when (action) {
            LogAction.Save -> {
                scope.launch {
                    // 直接写公共下载目录；不再走 ACTION_CREATE_DOCUMENT（HyperOS 上会立刻返回空结果）
                    val path =
                        runCatching { LogExport.save(context, file, file.name) }
                            .onFailure { notice = failTitle to exportFailed }
                            .getOrNull()
                    file.delete()
                    if (path != null) {
                        notice =
                            savedTitle to
                                context.getString(
                                    if (noRoot) R.string.log_saved_no_root else R.string.log_saved_message,
                                    path,
                                )
                    }
                }
            }

            LogAction.Share -> {
                runCatching {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val send =
                        Intent(Intent.ACTION_SEND).apply {
                            type = "application/gzip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    context.startActivity(Intent.createChooser(send, sendLabel))
                }.onFailure { notice = failTitle to errorText(it) }
            }
        }
    }

    fun collect(action: LogAction) {
        if (collecting) return
        scope.launch {
            collecting = true
            try {
                val result = Bugreport.build(context)
                if (result.rooted) {
                    deliver(action, result.file)
                } else {
                    // 没 root 也能拿到设备信息与自身日志；先让用户知道缺了什么再决定是否继续
                    partial = PartialLog(result.file, action, result.missing.size)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                notice = failTitle to errorText(e)
            } finally {
                collecting = false
            }
        }
    }

    MochiLazyLargeTitleScaffold(
        title = stringResource(R.string.settings),
        modifier = modifier,
    ) {
        item(key = "update") {
            MochiListSection {
                KsuSwitchRow(
                    stringResource(R.string.settings_check_update),
                    checkUpdate,
                    { checkUpdate = it },
                    icon = "cloud-download",
                    color = KsuTileColors.Blue,
                    subtitle = stringResource(R.string.settings_check_update_summary),
                )
                KsuSwitchRow(
                    stringResource(R.string.settings_module_check_update),
                    checkModuleUpdate,
                    { checkModuleUpdate = it },
                    icon = "sync",
                    color = KsuTileColors.Blue,
                    subtitle = stringResource(R.string.settings_check_update_summary),
                )
            }
        }
        item(key = "ui") {
            MochiListSection {
                MochiListPickerRow(
                    stringResource(R.string.settings_ui_mode),
                    options = persistentListOf("Mochi", "iOS", "Material"),
                    selectedIndex = uiMode,
                    onSelect = { uiMode = it },
                    leading = { KsuTile("browsers", KsuTileColors.Indigo) },
                )
                MochiListRow(
                    stringResource(R.string.settings_theme),
                    subtitle = stringResource(R.string.settings_theme_summary),
                    leading = { KsuTile("color-palette", KsuTileColors.Indigo) },
                    chevron = true,
                    onClick = onOpenTheme,
                )
            }
        }
        item(key = "features") {
            MochiListSection {
                MochiListPickerRow(
                    stringResource(R.string.settings_sucompat),
                    options =
                        persistentListOf(
                            stringResource(R.string.settings_mode_enable_by_default),
                            stringResource(R.string.settings_mode_disable_until_reboot),
                            stringResource(R.string.settings_mode_disable_always),
                        ),
                    selectedIndex = 0,
                    onSelect = {},
                    leading = { KsuTile("shield", KsuTileColors.Green) },
                )
                KsuSwitchRow(
                    stringResource(R.string.settings_kernel_umount),
                    kernelUmount,
                    { kernelUmount = it },
                    icon = "layers",
                    color = KsuTileColors.Green,
                    subtitle = stringResource(R.string.settings_kernel_umount_summary),
                )
                KsuSwitchRow(
                    stringResource(R.string.settings_selinux_hide),
                    selinuxHide,
                    { selinuxHide = it },
                    icon = "eye-off",
                    color = KsuTileColors.Green,
                    subtitle = stringResource(R.string.settings_selinux_hide_summary),
                )
                KsuSwitchRow(
                    stringResource(R.string.settings_sulog),
                    sulog,
                    { sulog = it },
                    icon = "reader",
                    color = KsuTileColors.Green,
                    subtitle = stringResource(R.string.settings_sulog_summary),
                )
                KsuSwitchRow(
                    stringResource(R.string.settings_adb_root),
                    adbRoot,
                    { adbRoot = it },
                    icon = "terminal",
                    color = KsuTileColors.Green,
                    subtitle = stringResource(R.string.settings_adb_root_summary),
                )
                KsuSwitchRow(
                    stringResource(R.string.settings_soft_reboot),
                    softReboot,
                    { softReboot = it },
                    icon = "reload",
                    color = KsuTileColors.Green,
                    subtitle = stringResource(R.string.settings_soft_reboot_summary),
                )
            }
        }
        item(key = "advanced") {
            MochiListSection {
                KsuSwitchRow(
                    stringResource(R.string.settings_umount_modules_default),
                    defaultUmount,
                    { defaultUmount = it },
                    icon = "git-compare",
                    subtitle = stringResource(R.string.settings_umount_modules_default_summary),
                )
                KsuSwitchRow(
                    stringResource(R.string.enable_web_debugging),
                    webDebug,
                    { webDebug = it },
                    icon = "code-slash",
                    subtitle = stringResource(R.string.enable_web_debugging_summary),
                )
            }
        }
        item(key = "about") {
            MochiListSection {
                MochiListRow(
                    stringResource(R.string.save_log),
                    subtitle = stringResource(R.string.save_log_summary),
                    leading = { KsuTile("save") },
                    onClick = { collect(LogAction.Save) },
                )
                MochiListRow(
                    stringResource(R.string.send_log),
                    subtitle = stringResource(R.string.send_log_summary),
                    leading = { KsuTile("share") },
                    onClick = { collect(LogAction.Share) },
                )
                MochiListRow(
                    stringResource(R.string.about),
                    leading = { KsuTile("information-circle", KsuTileColors.Blue) },
                    chevron = true,
                    onClick = {},
                )
            }
        }
        item(key = "credit") { MochiCredit(stringResource(R.string.mochi_credit)) }
    }

    MochiDialog(
        visible = collecting,
        onDismissRequest = {},
        title = stringResource(R.string.log_collecting_title),
        message = stringResource(R.string.log_collecting_message),
        icon = { CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(28.dp)) },
        actions = persistentListOf(),
    )
    partial?.let { log ->
        MochiDialog(
            visible = true,
            onDismissRequest = {
                log.file.delete()
                partial = null
            },
            title = stringResource(R.string.log_no_root_title),
            message = stringResource(R.string.log_no_root_message, log.missing),
            icon = { KsuTile("shield", KsuTileColors.Gray) },
            actions =
                persistentListOf(
                    MochiDialogAction(
                        stringResource(android.R.string.cancel),
                        MochiDialogActionRole.Cancel,
                        onClick = {
                            log.file.delete()
                            partial = null
                        },
                    ),
                    MochiDialogAction(
                        stringResource(R.string.log_continue),
                        MochiDialogActionRole.Primary,
                        onClick = {
                            partial = null
                            deliver(log.action, log.file, noRoot = true)
                        },
                    ),
                ),
        )
    }
    notice?.let { (title, message) ->
        MochiDialog(
            visible = true,
            onDismissRequest = { notice = null },
            title = title,
            message = message,
            actions =
                persistentListOf(
                    MochiDialogAction(
                        stringResource(android.R.string.ok),
                        MochiDialogActionRole.Primary,
                        onClick = { notice = null },
                    ),
                ),
        )
    }
}

/** 日志条目的两种去向。 */
private enum class LogAction { Save, Share }

/** 无 root 时收集好的报告，等用户确认后再导出/分享。 */
private class PartialLog(
    val file: File,
    val action: LogAction,
    val missing: Int,
)
