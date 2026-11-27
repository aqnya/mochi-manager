package com.nekosu.manager.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nekosu.manager.R
import com.nekosu.manager.data.AppearanceSettings
import com.nekosu.manager.ui.mochi.KsuTile
import com.nekosu.manager.ui.mochi.KsuTileColors
import com.nekosu.manager.ui.mochi.MochiFootnote
import kotlinx.collections.immutable.persistentListOf
import ui.feice.mochi.basic.MochiDialog
import ui.feice.mochi.basic.MochiDialogAction
import ui.feice.mochi.basic.MochiDialogActionRole
import ui.feice.mochi.basic.MochiSwitch
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.MochiListSection
import ui.feice.mochi.navigation.MochiBackButton
import ui.feice.mochi.navigation.MochiLazyCompactTitleScaffold
import ui.feice.mochi.theme.MochiTheme

/**
 * 主题设置。
 *
 * 目前只有「玻璃材质」一项：开启后 tab bar、顶栏按钮、菜单、弹窗换成 Mochi 的玻璃材质，
 * 关闭回到不透明材质。开启前弹一次确认，因为玻璃要每帧采样背后页面（GPU / 耗电）。
 *
 * 开关直接写 [AppearanceSettings]，主题根节点读的是同一个状态，所以拨动后整棵界面立刻换材质，
 * 返回列表也保持。
 */
@Composable
fun ThemeScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appearance = AppearanceSettings.get(context)
    // 确认对话框打开期间开关保持「开」，取消则回到关闭
    var confirmGlass by rememberSaveable { mutableStateOf(false) }
    val cs = MochiTheme.colorScheme

    MochiLazyCompactTitleScaffold(
        title = stringResource(R.string.settings_theme),
        subtitle = stringResource(R.string.settings_theme_summary),
        modifier = modifier,
        navigationIcon = { MochiBackButton(onClick = onBack) },
    ) {
        item(key = "materials") {
            MochiListSection(
                header = stringResource(R.string.theme_section_materials),
                footer = stringResource(R.string.theme_glass_footer),
            ) {
                MochiListRow(
                    stringResource(R.string.theme_glass),
                    subtitle = stringResource(R.string.theme_glass_summary),
                    leading = { KsuTile("water", KsuTileColors.Blue) },
                    trailing = {
                        MochiSwitch(
                            appearance.glass || confirmGlass,
                            { on ->
                                if (on) {
                                    confirmGlass = true
                                } else {
                                    appearance.updateGlass(false)
                                }
                            },
                        )
                    },
                    onClick = {
                        if (appearance.glass) appearance.updateGlass(false) else confirmGlass = true
                    },
                )
            }
        }
        item(key = "state") {
            // 明确写出当前材质，避免开关和实际观感不一致时看不出所以然
            MochiFootnote(
                stringResource(
                    if (appearance.glass) R.string.theme_glass_state_on else R.string.theme_glass_state_off,
                ),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    MochiDialog(
        visible = confirmGlass,
        onDismissRequest = { confirmGlass = false },
        title = stringResource(R.string.theme_glass_warning_title),
        message = stringResource(R.string.theme_glass_warning_message),
        icon = { MochiIonicon("warning", tint = cs.error, size = 44.dp) },
        actions =
            persistentListOf(
                MochiDialogAction(stringResource(android.R.string.cancel), MochiDialogActionRole.Cancel),
                MochiDialogAction(
                    stringResource(R.string.theme_glass_enable),
                    MochiDialogActionRole.Primary,
                    onClick = {
                        appearance.updateGlass(true)
                        confirmGlass = false
                    },
                ),
            ),
    )
}
