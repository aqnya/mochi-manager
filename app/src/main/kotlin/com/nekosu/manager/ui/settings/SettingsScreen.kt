package com.nekosu.manager.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.nekosu.manager.R
import com.nekosu.manager.ui.mochi.KsuSwitchRow
import com.nekosu.manager.ui.mochi.KsuTile
import com.nekosu.manager.ui.mochi.KsuTileColors
import com.nekosu.manager.ui.mochi.MochiCredit
import kotlinx.collections.immutable.persistentListOf
import ui.feice.mochi.list.MochiListPickerRow
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.MochiListSection
import ui.feice.mochi.navigation.MochiLazyLargeTitleScaffold

/**
 * 设置：更新、外观、功能开关、高级选项和关于。所有开关都是本地状态，
 * 结构对齐 KernelSU Manager 的 Mochi 设置页。
 *
 * 「主题」是唯一真实生效的入口，点进 [ThemeScreen]；其余条目仍是移植中的占位。
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
                    leading = { KsuTile("save") },
                    onClick = {},
                )
                MochiListRow(
                    stringResource(R.string.send_log),
                    leading = { KsuTile("share") },
                    onClick = {},
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
}
