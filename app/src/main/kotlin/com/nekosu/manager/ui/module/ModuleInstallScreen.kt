package com.nekosu.manager.ui.module

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nekosu.manager.R
import com.nekosu.manager.data.InstallState
import com.nekosu.manager.data.LogExport
import com.nekosu.manager.data.ModuleInstallSession
import kotlinx.coroutines.launch
import ui.feice.mochi.basic.MochiBarGroup
import ui.feice.mochi.basic.MochiBarGroupItem
import ui.feice.mochi.basic.MochiButtonStyle
import ui.feice.mochi.basic.MochiIconButton
import ui.feice.mochi.basic.Text
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.navigation.MochiBackButton
import ui.feice.mochi.navigation.MochiLazyCompactTitleScaffold
import ui.feice.mochi.theme.MochiTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 模块安装页，照 KernelSU PR 的刷写页（`FlashScreen`）来做。
 *
 * 结构就三件东西：
 * - 顶栏标题跟着状态走：正在安装 / 安装成功 / 安装失败，右侧保存日志
 * - 正文是一整块等宽终端输出，新行自动滚到底，不做状态卡、提示条、输入框
 * - 成功后右下角一个圆形按钮：需要重启就「重启」，热安装则「关闭」
 *
 * 安装中禁用返回（PR 里 `backEnabled = flashingStatus != FLASHING`），音量键脚本
 * 的提示直接来自脚本自己的 `ui_print`，用户按物理键继续。
 */
@Composable
fun ModuleInstallScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by ModuleInstallSession.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var saving by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val text = remember(state.lines) { state.text }

    // 交互式脚本要用户按键，屏幕灭了就按不到
    KeepScreenOn(state.running)

    // PR：刷写中不给退，避免脚本跑到一半被切走
    BackHandler(enabled = state.running) {}

    // 有新输出就跟到底，和 adb logcat 一样
    LaunchedEffect(state.lines.size) {
        if (text.isNotEmpty()) {
            withFrameNanosCompat()
            scroll.animateScrollTo(scroll.maxValue)
        }
    }

    fun leave() {
        if (state.running) return
        ModuleInstallSession.dismiss()
        onExit()
    }

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

    Box(modifier.fillMaxSize()) {
        MochiLazyCompactTitleScaffold(
            title = titleOf(state),
            modifier = Modifier.fillMaxSize(),
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
            item(key = "console") {
                Console(
                    text = if (text.isBlank()) "…" else text,
                    scroll = scroll,
                    modifier = Modifier.fillParentMaxHeight(),
                )
            }
        }

        // 只有成功才给悬浮操作，失败时按返回退出——和 PR 的 FAB 出现条件一致
        if (state.finished && !state.failed) {
            val reboot = state.needsReboot
            val actionLabel = stringResource(if (reboot) R.string.module_install_reboot else R.string.module_install_close)
            MochiIconButton(
                onClick = {
                    if (reboot) {
                        ModuleInstallSession.reboot()
                    } else {
                        leave()
                    }
                },
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 20.dp, bottom = 28.dp)
                        .size(56.dp)
                        .semantics { contentDescription = actionLabel },
                style = MochiButtonStyle.Filled,
            ) {
                MochiIonicon(
                    if (reboot) "power" else "close-circle",
                    size = 26.dp,
                )
            }
        }
    }
}

/** 终端输出：PR 里就是一整块 12sp 等宽文本，背景跟页面一致，不做卡片。 */
@Composable
private fun Console(
    text: String,
    scroll: ScrollState,
    modifier: Modifier = Modifier,
) {
    SelectionContainer {
        Text(
            text,
            style =
                MochiTheme.textStyles.footnote.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                ),
            color = MochiTheme.colorScheme.onSurface,
            modifier =
                modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun titleOf(state: InstallState): String =
    stringResource(
        when {
            state.running -> R.string.module_install_flashing
            state.failed -> R.string.module_install_failed_title
            state.started -> R.string.module_install_success_title
            else -> R.string.module_install_title
        },
    )

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
