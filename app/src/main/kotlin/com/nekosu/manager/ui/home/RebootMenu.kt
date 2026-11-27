package com.nekosu.manager.ui.home

import android.content.Context
import android.os.PowerManager
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.nekosu.manager.data.Reboot
import com.nekosu.manager.data.RebootTarget
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import ui.feice.mochi.basic.MochiBarIconDropdown
import ui.feice.mochi.basic.MochiItem
import ui.feice.mochi.icons.MochiIonicon

/**
 * 首页右上角的电源按钮：mochi 的顶栏图标下拉菜单（融球、果冻、按住滑选）。
 *
 * 参照上游 KernelSU 的 RebootListPopup——按设备能力列出用户空间重启；
 * recovery 菜单项在执行前会先按一次电源键消掉厂商 recovery 的
 * 「清除数据」提示（在 [Reboot] 里）。
 */
@Composable
fun RebootMenu() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    // PowerManager 能力是系统常量，进程内不会变，菜单项一次性算好
    @Suppress("DEPRECATION")
    val pm = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager? }
    val targets = remember { Reboot.targets(pm?.isRebootingUserspaceSupported == true) }
    val items =
        remember(targets) {
            targets.map { MochiItem(context.getString(it.labelRes)) }.toImmutableList()
        }
    MochiBarIconDropdown(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        items = items,
        onItemClick = { index ->
            expanded = false
            scope.launch { Reboot.perform(targets[index]) }
        },
        navId = "home.reboot",
        contentKey = "power",
    ) {
        Box(
            Modifier.semantics { contentDescription = "重启" },
        ) {
            MochiIonicon("power")
        }
    }
}
