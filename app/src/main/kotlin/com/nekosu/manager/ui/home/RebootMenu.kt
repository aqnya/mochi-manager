package com.nekosu.manager.ui.home

import android.content.Context
import android.os.PowerManager
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.nekosu.manager.R
import com.nekosu.manager.data.Reboot
import com.nekosu.manager.data.RebootTarget
import kotlinx.coroutines.launch
import ui.feice.mochi.basic.MochiBarIconButton
import ui.feice.mochi.icons.MochiIonicon

/**
 * 首页右上角的电源按钮：按下弹「重启」菜单，参照上游 KernelSU 的
 * RebootListPopup——按设备能力列出用户空间重启，recovery 菜单项在执行前
 * 会先按一次电源键消掉厂商 recovery 的「清除数据」提示（在 [Reboot] 里）。
 */
@Composable
fun RebootMenu() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    // PowerManager 能力是系统常量，进程内不会变，菜单项一次性算好
    val pm = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager? }
    val targets = remember { Reboot.targets(pm?.isRebootingUserspaceSupported == true) }
    Box {
        MochiBarIconButton(
            onClick = { expanded = true },
            navId = "home.reboot",
            contentKey = "power",
        ) {
            Box(
                Modifier.semantics { contentDescription = "重启" },
            ) {
                MochiIonicon("power")
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            targets.forEach { target ->
                DropdownMenuItem(
                    text = { Text(stringResource(target.labelRes)) },
                    onClick = {
                        expanded = false
                        scope.launch { Reboot.perform(target) }
                    },
                )
            }
        }
    }
}
