package com.nekosu.manager.ui.settings

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nekosu.manager.R
import com.nekosu.manager.data.LogAction
import com.nekosu.manager.data.LogExportCoordinator
import com.nekosu.manager.data.LogExportState
import com.nekosu.manager.ui.mochi.KsuSwitchRow
import com.nekosu.manager.ui.mochi.KsuTile
import com.nekosu.manager.ui.mochi.KsuTileColors
import com.nekosu.manager.ui.mochi.MochiCredit
import kotlinx.collections.immutable.persistentListOf
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
 * 已接真实能力：「主题」进入 [ThemeScreen]，「保存日志 / 发送日志」生成 bugreport（[LogExportCoordinator]）
 * 并导出或分享。其余开关仍是移植中的占位。
 *
 * 导出状态不在本 Composable 里 remember：Activity 在收集过程中被销毁重建时，
 * 本地协程会连同状态一起被清掉，表现就是「点了没反应、下载目录里没有文件」。
 * 状态机放在应用级单例里，退出应用也照样把文件写完。
 */
@Composable
fun SettingsScreen(
    onOpenTheme: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var checkUpdate by rememberSaveable { mutableStateOf(true) }
    var checkModuleUpdate by rememberSaveable { mutableStateOf(true) }
    var uiMode by rememberSaveable { mutableIntStateOf(0) }
    var sulog by rememberSaveable { mutableStateOf(true) }
    var adbRoot by rememberSaveable { mutableStateOf(false) }
    var softReboot by rememberSaveable { mutableStateOf(false) }
    var defaultUmount by rememberSaveable { mutableStateOf(false) }
    var webDebug by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current
    val exportState = LogExportCoordinator.state

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
                // 内核 feature 开关（SELinux 隐藏 / 模块挂载隐藏）由内核上报的状态驱动，
                // 切换时 JNI 同步内核 + 落盘；内核不在线时回退到本地占位开关。
                KernelFeatureSection()
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
                    onClick = { LogExportCoordinator.start(context, LogAction.Save) },
                )
                MochiListRow(
                    stringResource(R.string.send_log),
                    subtitle = stringResource(R.string.send_log_summary),
                    leading = { KsuTile("share") },
                    onClick = { LogExportCoordinator.start(context, LogAction.Share) },
                )
                MochiListRow(
                    stringResource(R.string.about),
                    subtitle = stringResource(R.string.settings_about_summary),
                    leading = { KsuTile("information-circle", KsuTileColors.Blue) },
                    chevron = true,
                    onClick = onOpenAbout,
                )
            }
        }
        item(key = "credit") { MochiCredit(stringResource(R.string.mochi_credit)) }
    }

    MochiDialog(
        visible = exportState is LogExportState.Collecting || exportState is LogExportState.Saving,
        onDismissRequest = {},
        title =
            stringResource(
                if (exportState is LogExportState.Saving) R.string.log_saving_title else R.string.log_collecting_title,
            ),
        message =
            stringResource(
                if (exportState is LogExportState.Saving) R.string.log_saving_message else R.string.log_collecting_message,
            ),
        icon = { CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(28.dp)) },
        actions = persistentListOf(),
    )
    (exportState as? LogExportState.ConfirmPartial)?.let { log ->
        MochiDialog(
            visible = true,
            // 关闭确认框等于放弃这份报告，走 cancelPartial（才允许删缓存文件）
            onDismissRequest = { LogExportCoordinator.cancelPartial() },
            title = stringResource(R.string.log_no_root_title),
            message = stringResource(R.string.log_no_root_message, log.missing),
            icon = { KsuTile("shield", KsuTileColors.Gray) },
            actions =
                persistentListOf(
                    MochiDialogAction(
                        stringResource(android.R.string.cancel),
                        MochiDialogActionRole.Cancel,
                        onClick = { LogExportCoordinator.cancelPartial() },
                    ),
                    MochiDialogAction(
                        stringResource(R.string.log_continue),
                        MochiDialogActionRole.Primary,
                        onClick = { LogExportCoordinator.confirm(context) },
                    ),
                ),
        )
    }
    (exportState as? LogExportState.Notice)?.let { notice ->
        MochiDialog(
            visible = true,
            onDismissRequest = { LogExportCoordinator.dismiss() },
            title = notice.title,
            message = notice.message,
            actions =
                persistentListOf(
                    MochiDialogAction(
                        stringResource(android.R.string.ok),
                        MochiDialogActionRole.Primary,
                        onClick = { LogExportCoordinator.dismiss() },
                    ),
                ),
        )
    }
}
