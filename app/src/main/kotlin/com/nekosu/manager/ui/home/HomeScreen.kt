package com.nekosu.manager.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nekosu.manager.R
import com.nekosu.manager.data.DeviceInfo
import com.nekosu.manager.data.DeviceInfoRepository
import com.nekosu.manager.data.MockData
import com.nekosu.manager.data.SuProfileStore
import com.nekosu.manager.ui.mochi.KsuTileColors
import com.nekosu.manager.ui.mochi.MochiCredit
import com.nekosu.manager.ui.mochi.rememberKsuTone
import com.nekosu.manager.ui.mochi.shortKernelVersion
import ui.feice.mochi.basic.MochiBarIconButton
import ui.feice.mochi.basic.Text
import ui.feice.mochi.effect.content.MochiCounterText
import ui.feice.mochi.effect.elastic.mochiPressDeform
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.list.MochiListCard
import ui.feice.mochi.list.MochiListDefaults
import ui.feice.mochi.list.MochiListLeadingStyle
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.MochiListSectionHeader
import ui.feice.mochi.list.MochiListStatusChip
import ui.feice.mochi.navigation.MochiLazyLargeTitleScaffold
import ui.feice.mochi.shape.MochiRoundedRectangle
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.theme.MochiTheme

private val HeroShape = MochiRoundedRectangle(MochiListDefaults.SectionCorner)

/**
 * 首页：状态卡、提示、统计、系统信息、支持入口。
 *
 * 「设备信息」来自 [DeviceInfoRepository]（Build / proc / selinuxfs / PackageManager），
 * 其余仍为 [MockData]：模块列表与 KernelSU 运行状态还没有数据层。
 */
@Composable
fun HomeScreen(
    onOpenTab: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        SuProfileStore.init(context)
        SuProfileStore.refreshFromKernel(context)
    }
    val profiles by SuProfileStore.profiles.collectAsState()
    // 启动时已预热；缓存缺失（进程被回收后重建）就同步取一次，保证首帧完整
    val device = remember { DeviceInfoRepository.current() ?: DeviceInfoRepository.loadNow(context) }
    MochiLazyLargeTitleScaffold(
        title = stringResource(R.string.app_name),
        modifier = modifier,
        actions = {
            MochiBarIconButton(onClick = {}, navId = "home.reboot", contentKey = "power") {
                MochiIonicon("power")
            }
        },
    ) {
        item(key = "status") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ActiveCard()
                Notice(stringResource(R.string.home_new_version), error = false)
                StatsRow(onOpenTab, grantedCount = profiles.values.count { it.allowSu })
            }
        }
        item(key = "info") { SystemInfoSection(device) }
        item(key = "support") { SupportSection() }
        item(key = "credit") { MochiCredit(stringResource(R.string.mochi_credit)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveCard() {
    val scheme = MochiTheme.colorScheme
    val ok = rememberKsuTone(KsuTileColors.Green).content
    val neutralBg = scheme.onSurface.copy(alpha = 0.06f)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(HeroShape)
            .background(MochiDefaults.groupedContainer())
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(ok.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            MochiIonicon("checkmark", tint = ok, size = 26.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.home_working),
                style = MochiTheme.textStyles.body.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
            )
            Text(
                stringResource(
                    R.string.home_working_version,
                    "${MockData.ksuVersion}-${MockData.kernelUapiVersion}",
                ),
                style = MochiTheme.textStyles.footnote,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            FlowRow(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MochiListStatusChip(MockData.lkmMode, containerColor = neutralBg, contentColor = scheme.onSurfaceVariant)
                MochiListStatusChip("LKM", containerColor = neutralBg, contentColor = scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Notice(
    message: String,
    error: Boolean,
    onClick: (() -> Unit)? = null,
) {
    val scheme = MochiTheme.colorScheme
    MochiListCard {
        MochiListRow(
            message,
            leading = {
                MochiIonicon(
                    if (error) "alert-circle" else "notifications",
                    tint = if (error) scheme.error else scheme.onSurfaceVariant,
                )
            },
            leadingStyle = MochiListLeadingStyle.Symbol,
            chevron = onClick != null,
            onClick = onClick,
        )
    }
}

@Composable
private fun StatsRow(
    onOpenTab: (Int) -> Unit,
    grantedCount: Int,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile(
            title = stringResource(R.string.superuser),
            icon = "shield-checkmark",
            count = grantedCount,
            modifier = Modifier.weight(1f),
        ) { onOpenTab(1) }
        StatTile(
            title = stringResource(R.string.module),
            icon = "extension-puzzle",
            count = MockData.modules.size,
            modifier = Modifier.weight(1f),
        ) { onOpenTab(2) }
    }
}

@Composable
private fun StatTile(
    title: String,
    icon: String,
    count: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = MochiTheme.colorScheme
    Column(
        modifier
            .mochiPressDeform()
            .clip(HeroShape)
            .background(MochiDefaults.groupedContainer())
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MochiIonicon(icon, tint = scheme.onSurfaceVariant, size = 16.dp)
            Text(title, style = MochiTheme.textStyles.subhead, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        MochiCounterText(
            count.toLong(),
            style = MochiTheme.textStyles.body.copy(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
            color = scheme.onSurface,
        )
    }
}

private class InfoCell(
    val icon: String,
    val label: String,
    val value: String,
)

@Composable
private fun SystemInfoSection(info: DeviceInfo) {
    // 原始值在数据层，翻译放在 UI：PR 的 SystemInfo 也是 raw 字符串 + int 状态码
    val selinux = when (info.selinuxStatus) {
        "Enforcing" -> stringResource(R.string.selinux_status_enforcing)
        "Permissive" -> stringResource(R.string.selinux_status_permissive)
        "Disabled" -> stringResource(R.string.selinux_status_disabled)
        else -> stringResource(R.string.selinux_status_unknown)
    }
    val seccomp = when (info.seccompStatus) {
        -1 -> stringResource(R.string.seccomp_status_not_supported)
        0 -> stringResource(R.string.seccomp_status_disabled)
        1 -> stringResource(R.string.seccomp_status_strict)
        2 -> stringResource(R.string.seccomp_status_filter)
        else -> stringResource(R.string.seccomp_status_unknown)
    }
    Column {
        MochiListSectionHeader(stringResource(R.string.mochi_home_details))
        val line = MochiTheme.colorScheme.onSurface.copy(alpha = 0.10f)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(HeroShape)
                .background(MochiDefaults.groupedContainer())
                .padding(vertical = 6.dp),
        ) {
            InfoPair(
                InfoCell("phone-portrait", stringResource(R.string.home_device_model), info.model),
                InfoCell("apps", stringResource(R.string.home_system_version), info.system),
                line,
            )
            InfoDivider(line)
            InfoPair(
                InfoCell("hardware-chip", stringResource(R.string.home_kernel), shortKernelVersion(info.kernel)),
                InfoCell("shield-half", stringResource(R.string.home_selinux_status), selinux),
                line,
            )
            InfoDivider(line)
            InfoPair(
                InfoCell("lock-closed", stringResource(R.string.home_seccomp_status), seccomp),
                InfoCell("extension-puzzle", stringResource(R.string.home_manager_version), info.managerVersion),
                line,
            )
            InfoDivider(line)
            InfoCellView(InfoCell("finger-print", stringResource(R.string.home_fingerprint), info.fingerprint))
        }
    }
}

@Composable
private fun InfoPair(
    left: InfoCell,
    right: InfoCell,
    line: Color,
) {
    Row(Modifier.height(IntrinsicSize.Min)) {
        InfoCellView(left, Modifier.weight(1f))
        Box(Modifier.padding(vertical = 10.dp).width(0.5.dp).fillMaxHeight().background(line))
        InfoCellView(right, Modifier.weight(1f))
    }
}

@Composable
private fun InfoDivider(line: Color) {
    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(0.5.dp).background(line))
}

@Composable
private fun InfoCellView(
    cell: InfoCell,
    modifier: Modifier = Modifier,
) {
    val scheme = MochiTheme.colorScheme
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            MochiIonicon(cell.icon, tint = scheme.onSurfaceVariant, size = 14.dp)
            Text(cell.label, style = MochiTheme.textStyles.footnote, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(cell.value, style = MochiTheme.textStyles.body, color = scheme.onSurface)
    }
}

@Composable
private fun SupportSection() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f)) {
            SupportTile(
                icon = "heart",
                title = stringResource(R.string.home_support_title),
                summary = stringResource(R.string.mochi_home_support_short),
            )
        }
        Box(Modifier.weight(1f)) {
            SupportTile(
                icon = "book",
                title = stringResource(R.string.home_learn_kernelsu),
                summary = stringResource(R.string.mochi_home_learn_short),
            )
        }
    }
}

@Composable
private fun SupportTile(
    icon: String,
    title: String,
    summary: String,
) {
    val scheme = MochiTheme.colorScheme
    Column(
        Modifier
            .mochiPressDeform()
            .fillMaxWidth()
            .clip(HeroShape)
            .background(MochiDefaults.groupedContainer())
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MochiIonicon(icon, tint = scheme.onSurfaceVariant, size = 20.dp)
        Text(
            title,
            Modifier.padding(top = 4.dp),
            style = MochiTheme.textStyles.body.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(summary, style = MochiTheme.textStyles.footnote, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
