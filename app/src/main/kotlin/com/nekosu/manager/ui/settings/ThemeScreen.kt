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
import com.nekosu.manager.data.GlassStyle
import com.nekosu.manager.ui.mochi.KsuTile
import com.nekosu.manager.ui.mochi.KsuTileColors
import com.nekosu.manager.ui.mochi.MochiFootnote
import kotlinx.collections.immutable.persistentListOf
import ui.feice.mochi.basic.MochiDialog
import ui.feice.mochi.basic.MochiDialogAction
import ui.feice.mochi.basic.MochiDialogActionRole
import ui.feice.mochi.basic.MochiSwitch
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.list.MochiListPickerRow
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.MochiListSection
import ui.feice.mochi.navigation.MochiBackButton
import ui.feice.mochi.navigation.MochiLazyCompactTitleScaffold
import ui.feice.mochi.theme.MochiTheme

/**
 * 主题设置：材质分组。
 *
 * 「玻璃材质」是总开关，开启后 tab bar、顶栏按钮、菜单、弹窗换成 Mochi 的玻璃材质，
 * 关闭回到不透明材质；开启前弹一次确认，因为玻璃要每帧采样背后页面（GPU / 耗电）。
 *
 * 开启后多出「玻璃样式」，按 mochi demo 的做法把材质再分档：标准（更透）和厚实（磨砂更实）。
 * 改动直接写 [AppearanceSettings]，主题根节点读的是同一份状态，所以选完立刻生效，
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
    val styles = GlassStyle.entries

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
                // 样式只在开着玻璃时有意义，关掉就收起，避免看起来像能设却看不到效果
                if (appearance.glass) {
                    MochiListPickerRow(
                        stringResource(R.string.theme_glass_style),
                        options = persistentListOf(*styles.map { stringResource(it.label) }.toTypedArray()),
                        selectedIndex = styles.indexOf(appearance.glassStyle),
                        onSelect = { appearance.updateGlassStyle(styles[it]) },
                        leading = { KsuTile("layers", KsuTileColors.Indigo) },
                    )
                }
            }
        }
        item(key = "state") {
            // 明确写出当前材质和样式，避免开关、选项和实际观感不一致时看不出所以然
            MochiFootnote(
                if (appearance.glass) {
                    stringResource(
                        R.string.theme_glass_state_on,
                        stringResource(appearance.glassStyle.label),
                        stringResource(appearance.glassStyle.description),
                    )
                } else {
                    stringResource(R.string.theme_glass_state_off)
                },
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

/** 样式的展示名，取值对齐 mochi demo 的「玻璃样式」页。 */
internal val GlassStyle.label: Int
    get() = when (this) {
        GlassStyle.Regular -> R.string.theme_glass_style_regular
        GlassStyle.Thick -> R.string.theme_glass_style_thick
    }

/** 样式的观感说明，写在当前状态那一行。 */
internal val GlassStyle.description: Int
    get() = when (this) {
        GlassStyle.Regular -> R.string.theme_glass_style_regular_desc
        GlassStyle.Thick -> R.string.theme_glass_style_thick_desc
    }
