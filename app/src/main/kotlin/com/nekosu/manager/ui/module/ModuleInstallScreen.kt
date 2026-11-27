package com.nekosu.manager.ui.module

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nekosu.manager.R
import com.nekosu.manager.data.InstallState
import com.nekosu.manager.data.LogExport
import com.nekosu.manager.data.ModuleInstallSession
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import ui.feice.mochi.basic.MochiBarGroup
import ui.feice.mochi.basic.MochiBarGroupItem
import ui.feice.mochi.basic.MochiButton
import ui.feice.mochi.basic.MochiButtonStyle
import ui.feice.mochi.basic.MochiDialog
import ui.feice.mochi.basic.MochiDialogAction
import ui.feice.mochi.basic.MochiDialogActionRole
import ui.feice.mochi.basic.Text
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.list.MochiListCard
import ui.feice.mochi.list.MochiListDefaults
import ui.feice.mochi.navigation.MochiBackButton
import ui.feice.mochi.navigation.MochiLazyCompactTitleScaffold
import ui.feice.mochi.theme.MochiTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 模块安装的实时输出页。
 *
 * 安装脚本不是「静默跑完再给结果」的：metamodule 之类会在中途 `ui_print` 一句
 * 「按音量上键继续」，然后用音量键分支；也有脚本用 `read` 等一行输入。原来把整个
 * `su -c ncore module install` 一次性执行、结束才看输出，等于把这些提示全吞掉，
 * 脚本就一直等在那儿，用户只看到「正在安装」转圈。
 *
 * 现在改成流式：ncore 的每行输出即时进终端区，检测到等待提示就展开输入框
 * （音量键脚本只需按物理键，`read` 脚本可以在输入框里回话）。
 */
@Composable
fun ModuleInstallScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by ModuleInstallSession.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MochiTheme.colorScheme

    var askCancel by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    val scroll = rememberScrollState()
    val text = remember(state.lines) { state.lines.joinToString("\n") }

    // 终端高度跟着行数走：日志少时不留一大片空白，日志多时封顶，下面的输入框和按钮
    // 必须留在首屏里——交互式安装等的是用户按键，按钮被挤出屏幕就等于没法操作。
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val consoleHeight =
        remember(state.lines.size, screenHeight) {
            ((state.lines.size * 16).dp + 26.dp).coerceIn(170.dp, screenHeight * 0.42f)
        }

    // 交互式安装要用户按键，屏幕灭了就按不到
    KeepScreenOn(state.running)

    // 有新输出就跟到底；用户上滑查看历史时下一次输出会把视野拉回底部，和 adb logcat 一致
    LaunchedEffect(state.lines.size) {
        if (state.lines.isNotEmpty()) {
            withFrameNanosCompat()
            scroll.animateScrollTo(scroll.maxValue)
        }
    }

    fun leave() {
        if (state.running) askCancel = true else onExit()
    }

    BackHandler(enabled = state.running) { askCancel = true }

    fun saveLog() {
        if (saving || text.isBlank()) return
        saving = true
        scope.launch {
            val name =
                "NekoSU_module_install_" +
                    SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault()).format(Date()) + ".log"
            val saved =
                runCatching {
                    val temp = File(context.cacheDir, name).apply { writeText(text) }
                    LogExport.save(context, temp, name, "text/plain")
                }
            saving = false
            val message =
                saved.fold(
                    onSuccess = { context.getString(R.string.module_install_saved, it) },
                    onFailure = { context.getString(R.string.module_install_save_failed, it.message ?: "未知错误") },
                )
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    MochiLazyCompactTitleScaffold(
        title = stringResource(R.string.module_install_title),
        subtitle = subtitleOf(state),
        modifier = modifier,
        navigationIcon = { MochiBackButton(onClick = { leave() }) },
        actions = {
            MochiBarGroup(navId = "module.install.actions") {
                MochiBarGroupItem(
                    onClick = { saveLog() },
                    navId = "module.install.save",
                    contentKey = "download",
                ) { MochiIonicon("download-outline") }
            }
        },
    ) {
        item(key = "status") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                MochiListCard {
                    Column(Modifier.fillMaxWidth().padding(MochiListDefaults.RowPadding)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (state.running) {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .background(cs.primary, RoundedCornerShape(4.dp)),
                                )
                                Spacer(Modifier.width(10.dp))
                            }
                            Text(
                                statusText(state),
                                style = MochiTheme.textStyles.body.copy(fontWeight = FontWeight.Medium),
                                color = cs.onSurface,
                            )
                        }
                        if (state.failures.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            state.failures.forEach { failure ->
                                Text(
                                    failure,
                                    style = MochiTheme.textStyles.footnote,
                                    color = cs.error,
                                )
                            }
                        }
                        if (state.running) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.module_install_keep_screen),
                                style = MochiTheme.textStyles.footnote,
                                color = cs.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (state.running && state.awaitingInput) {
                    Spacer(Modifier.height(10.dp))
                    WaitingBar(stringResource(R.string.module_install_waiting))
                }
            }
        }

        item(key = "console") {
            MochiListCard(
                Modifier
                    .fillMaxWidth()
                    .height(consoleHeight)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Console(
                    text = if (text.isBlank()) "…" else text,
                    scroll = scroll,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (state.running && state.awaitingInput) {
            item(key = "input") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true,
                        placeholder = {
                            Text(
                                stringResource(R.string.module_install_input_hint),
                                style = MochiTheme.textStyles.footnote,
                                color = cs.onSurfaceVariant,
                            )
                        },
                        colors =
                            OutlinedTextFieldDefaults.colors(
                                focusedTextColor = cs.onSurface,
                                unfocusedTextColor = cs.onSurface,
                                cursorColor = cs.primary,
                                focusedBorderColor = cs.primary,
                                unfocusedBorderColor = cs.outlineVariant,
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                        modifier = Modifier.weight(1f),
                    )
                    MochiButton(
                        onClick = {
                            ModuleInstallSession.sendInput(input)
                            input = ""
                        },
                        enabled = input.isNotBlank(),
                        style = MochiButtonStyle.Filled,
                    ) { Text(stringResource(R.string.module_install_send)) }
                }
            }
        }

        item(key = "actions") {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                if (state.running) {
                    MochiButton(
                        onClick = { askCancel = true },
                        style = MochiButtonStyle.Filled,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.module_install_cancel)) }
                } else {
                    MochiButton(
                        onClick = {
                            ModuleInstallSession.dismiss()
                            onExit()
                        },
                        style = MochiButtonStyle.Filled,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.module_install_finish)) }
                }
            }
        }
    }

    if (askCancel) {
        MochiDialog(
            visible = true,
            onDismissRequest = { askCancel = false },
            title = stringResource(R.string.module_install_cancel_title),
            message = stringResource(R.string.module_install_cancel_message),
            icon = { MochiIonicon("alert-circle", size = 44.dp) },
            actions =
                persistentListOf(
                    MochiDialogAction(stringResource(R.string.module_install_close), MochiDialogActionRole.Cancel),
                    MochiDialogAction(
                        stringResource(R.string.module_install_cancel),
                        MochiDialogActionRole.Destructive,
                        onClick = {
                            askCancel = false
                            ModuleInstallSession.cancel()
                            ModuleInstallSession.dismiss()
                            onExit()
                        },
                    ),
                ),
        )
    }
}

@Composable
private fun WaitingBar(message: String) {
    val cs = MochiTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .background(cs.primary.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MochiIonicon("alert-circle", size = 18.dp, tint = cs.primary)
        Spacer(Modifier.width(8.dp))
        Text(
            message,
            style = MochiTheme.textStyles.footnote,
            color = cs.primary,
        )
    }
}

@Composable
private fun Console(
    text: String,
    scroll: androidx.compose.foundation.ScrollState,
    modifier: Modifier = Modifier,
) {
    val cs = MochiTheme.colorScheme
    Box(
        modifier
            .background(cs.surfaceContainerLowest.copy(alpha = 0.65f), RoundedCornerShape(10.dp))
            .verticalScroll(scroll)
            .padding(12.dp),
    ) {
        SelectionContainer {
            Text(
                text,
                style =
                    MochiTheme.textStyles.footnote.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    ),
                color = cs.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun statusText(state: InstallState): String {
    val current = state.current
    return when {
        state.running && current != null ->
            stringResource(R.string.module_install_running, current.displayName, state.index + 1, state.items.size)

        state.cancelled -> stringResource(R.string.module_install_cancelled)
        state.failures.isNotEmpty() ->
            listOf(
                if (state.success > 0) stringResource(R.string.module_install_success, state.success) else "",
                stringResource(R.string.module_install_failed_count, state.failures.size),
            ).filter { it.isNotBlank() }.joinToString("，")

        else -> stringResource(R.string.module_install_success, state.success)
    }
}

@Composable
private fun subtitleOf(state: InstallState): String {
    val current = state.current
    return when {
        state.running && current != null -> current.displayName
        state.cancelled -> stringResource(R.string.module_install_cancelled)
        state.started -> stringResource(R.string.module_install_done)
        else -> stringResource(R.string.module_install_empty)
    }
}

/** 安装期间保持屏幕常亮，退出页面立刻还原，不影响其它界面。 */
@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

/** 等一帧，保证 scrollState.maxValue 已经是新内容的高度。 */
private suspend fun withFrameNanosCompat() {
    androidx.compose.runtime.withFrameNanos { }
}
