// SPDX-License-Identifier: GPL-3.0-or-later
package com.nekosu.manager.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.nekosu.manager.R
import com.nekosu.manager.data.KernelFeature
import com.nekosu.manager.ui.mochi.KsuSwitchRow
import com.nekosu.manager.ui.mochi.KsuTileColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 内核 feature 开关（对齐上游 `FeatureSection`）：列表与状态都由内核给出
 * （`IOC_FEATURE_LIST/SET`），管理器只负责展示、切换；JNI 会把开关同步到内核
 * 与开机标志文件。
 *
 * 放到设置页「功能开关」段里，替代原来写死的「内核级卸载 / 隐藏 SELinux」两个
 * 占位开关：内核在线时按内核上报的 feature 动态渲染，离线时（单测、未加载模块）
 * 退回原来的本地开关，界面不出现空段。
 */
@Composable
fun KernelFeatureSection() {
    val scope = rememberCoroutineScope()
    var features by remember { mutableStateOf<List<KernelFeature.Info>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    // 内核离线时的本地占位状态，保持与移植前一致
    var fallbackUmount by remember { mutableStateOf(true) }
    var fallbackHide by remember { mutableStateOf(true) }

    suspend fun reload() {
        features = withContext(Dispatchers.IO) { KernelFeature.list() }
    }
    LaunchedEffect(Unit) { reload() }

    if (features.isEmpty()) {
        KsuSwitchRow(
            stringResource(R.string.settings_kernel_umount),
            fallbackUmount,
            { fallbackUmount = it },
            icon = "layers",
            color = KsuTileColors.Green,
            subtitle = stringResource(R.string.settings_kernel_umount_summary),
        )
        KsuSwitchRow(
            stringResource(R.string.settings_selinux_hide),
            fallbackHide,
            { fallbackHide = it },
            icon = "eye-off",
            color = KsuTileColors.Green,
            subtitle = stringResource(R.string.settings_selinux_hide_summary),
        )
        return
    }

    features.forEach { feature ->
        KsuSwitchRow(
            feature.label(),
            feature.enabled,
            onCheckedChange = { checked ->
                scope.launch {
                    busy = true
                    val ok = withContext(Dispatchers.IO) { KernelFeature.set(feature.id, checked) }
                    busy = false
                    if (ok) reload()
                }
            },
            icon = feature.icon(),
            color = KsuTileColors.Green,
            subtitle = feature.summary(),
            enabled = !busy,
        )
    }
}

@Composable
private fun KernelFeature.Info.label(): String =
    when (id) {
        KernelFeature.SELINUX_HIDE -> stringResource(R.string.settings_selinux_hide)
        KernelFeature.UMOUNT -> stringResource(R.string.settings_kernel_umount)
        // 内核新增、管理器还没加翻译的 feature 直接显示内核给的名字
        else -> name
    }

@Composable
private fun KernelFeature.Info.summary(): String? =
    when (id) {
        KernelFeature.SELINUX_HIDE -> stringResource(R.string.settings_selinux_hide_summary)
        KernelFeature.UMOUNT -> stringResource(R.string.settings_kernel_umount_summary)
        else -> null
    }

private fun KernelFeature.Info.icon(): String =
    when (id) {
        KernelFeature.SELINUX_HIDE -> "eye-off"
        KernelFeature.UMOUNT -> "layers"
        else -> "tune"
    }
