package com.nekosu.manager.ui.module

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nekosu.manager.R
import com.nekosu.manager.data.ModuleInstallSession
import com.nekosu.manager.data.StagedModule
import com.nekosu.manager.data.registry.ModuleInstallService
import com.nekosu.manager.data.registry.ModuleRepoRepository
import com.nekosu.manager.data.registry.ModuleRepoSource
import com.nekosu.manager.data.registry.RegistryIntegrityException
import com.nekosu.manager.data.registry.RepoModule
import com.nekosu.manager.data.registry.RepoModuleDetail
import com.nekosu.manager.data.registry.RepoRelease
import com.nekosu.manager.ui.mochi.KsuTile
import com.nekosu.manager.ui.mochi.KsuTileColors
import com.nekosu.manager.ui.mochi.MochiFootnote
import com.nekosu.manager.ui.mochi.RepoIcon
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
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
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.MochiListSection
import ui.feice.mochi.list.MochiListStatusChip
import ui.feice.mochi.navigation.MochiBackButton
import ui.feice.mochi.navigation.MochiLazyCompactTitleScaffold
import ui.feice.mochi.theme.MochiTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 安装流程的当前阶段：下载在详情页内完成，安装本体统一交给安装输出页。 */
private sealed interface InstallPhase {
    data object Idle : InstallPhase

    data class Downloading(
        val progress: Float,
    ) : InstallPhase

    /** 下载（含完整性校验）失败；安装阶段的失败在安装输出页里展示。 */
    data class DownloadFailed(
        val message: String,
    ) : InstallPhase
}

/**
 * 模块详情：概览、安装、元信息与更新日志。
 *
 * 安装链路与本地 ZIP 导入完全一致：从 Release 记录的 GitHub 地址下载 ZIP（校验大小
 * 与 SHA-256）→ 交给 [ModuleInstallSession] → 跳转安装输出页。安装的实时输出、
 * 交互提示与成败结果只在输出页呈现，这里不再内联安装、不绕过输出页。
 * 脚本类条目（kind=script）不提供安装，只能到网页上运行。
 */
@Composable
fun ModuleRepoDetailScreen(
    module: RepoModule,
    onBack: () -> Unit,
    onOpenInstall: () -> Unit = {},
    modifier: Modifier = Modifier,
    source: ModuleRepoSource = ModuleRepoRepository.get(LocalContext.current),
    installer: ModuleInstallService = ModuleRepoRepository.get(LocalContext.current),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val iconDir = remember { ModuleRepoRepository.get(context).iconDir }
    var detail by remember(module.id) { mutableStateOf<RepoModuleDetail?>(null) }
    var error by remember(module.id) { mutableStateOf<Throwable?>(null) }
    var phase by remember(module.id) { mutableStateOf<InstallPhase>(InstallPhase.Idle) }
    var confirm by rememberSaveable(module.id) { mutableStateOf(false) }

    LaunchedEffect(module.id) {
        try {
            detail = source.detail(module)
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = e
        }
    }

    fun open(url: String?) {
        if (url.isNullOrBlank()) return
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    fun install(release: RepoRelease) {
        if (phase is InstallPhase.Downloading) return
        confirm = false
        scope.launch {
            phase = InstallPhase.Downloading(0f)
            val downloaded =
                try {
                    installer.download(release) { progress ->
                        phase = InstallPhase.Downloading(progress)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    phase =
                        InstallPhase.DownloadFailed(
                            if (e is RegistryIntegrityException) e.message.orEmpty() else e.message ?: "下载失败",
                        )
                    return@launch
                }
            // 与本地 ZIP 导入同一条链路：包交给应用级安装会话，实时输出、
            // 交互提示与成败展示全部在安装输出页，不在详情页内联安装。
            phase = InstallPhase.Idle
            ModuleInstallSession.start(
                listOf(StagedModule(downloaded.file, module.name, downloaded.file.length())),
            )
            onOpenInstall()
        }
    }

    val current = detail
    val release = current?.stableRelease

    MochiLazyCompactTitleScaffold(
        title = module.name,
        subtitle = if (module.version.isBlank()) module.authorText else "v${module.version} · ${module.authorText}",
        modifier = modifier,
        navigationIcon = { MochiBackButton(onClick = onBack) },
        actions = {
            MochiBarGroup(navId = "repo.detail.actions") {
                MochiBarGroupItem(
                    onClick = { open(current?.webUrl) },
                    navId = "repo.web",
                    contentKey = "open",
                ) { MochiIonicon("open-outline") }
            }
        },
    ) {
        item(key = "overview") {
            MochiListCard {
                Column(Modifier.fillMaxWidth().padding(MochiListDefaults.RowPadding)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RepoIcon(module.iconUrl, module.name, iconDir, size = 52.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                module.name,
                                style = MochiTheme.textStyles.body.copy(fontWeight = FontWeight.SemiBold),
                                color = MochiTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (module.authorText.isNotBlank()) {
                                Text(
                                    stringResource(R.string.repo_author_value, module.authorText),
                                    style = MochiTheme.textStyles.footnote,
                                    color = MochiTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    if (module.summary.isNotBlank()) {
                        Text(
                            module.summary,
                            modifier = Modifier.padding(top = 12.dp),
                            style = MochiTheme.textStyles.subhead,
                            color = MochiTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (module.tags.isNotEmpty() || module.metamodule || module.isScript) {
                        Row(
                            Modifier.padding(top = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (module.metamodule) {
                                MochiListStatusChip(
                                    "META",
                                    containerColor = MochiTheme.colorScheme.tertiary.copy(alpha = 0.12f),
                                    contentColor = MochiTheme.colorScheme.tertiary,
                                )
                            }
                            if (module.isScript) {
                                MochiListStatusChip(
                                    stringResource(R.string.repo_script),
                                    containerColor = MochiTheme.colorScheme.tertiary.copy(alpha = 0.12f),
                                    contentColor = MochiTheme.colorScheme.tertiary,
                                )
                            }
                            module.tags.take(3).forEach { tag ->
                                MochiListStatusChip(
                                    tag,
                                    containerColor = MochiTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                                    contentColor = MochiTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "install") {
            MochiListSection(header = stringResource(R.string.repo_section_install)) {
                InstallBlock(
                    module = module,
                    release = release,
                    phase = phase,
                    onInstall = { confirm = true },
                    onOpenWeb = { open(current?.webUrl) },
                )
            }
        }

        if (current != null) {
            item(key = "info") {
                MochiListSection(header = stringResource(R.string.repo_section_info)) {
                    InfoRow(stringResource(R.string.repo_version), "v${module.version} (${module.versionCode})", icon = "pricetag")
                    InfoRow(stringResource(R.string.repo_published), formatDate(module.releaseEpochMillis), icon = "calendar")
                    module.stars?.let { stars ->
                        InfoRow(stringResource(R.string.repo_stars), stars.toString(), icon = "star")
                    }
                    current.license?.let { InfoRow(stringResource(R.string.repo_license), it, icon = "document-text") }
                    current.homepage?.let { url ->
                        MochiListRow(
                            stringResource(R.string.repo_homepage),
                            subtitle = hostOf(url),
                            leading = { KsuTile("link", KsuTileColors.Indigo) },
                            chevron = true,
                            onClick = { open(url) },
                        )
                    }
                    current.sourceUrl?.let { url ->
                        MochiListRow(
                            stringResource(R.string.repo_source),
                            subtitle = current.sourceName ?: hostOf(url),
                            leading = { KsuTile("code-slash", KsuTileColors.Gray) },
                            chevron = true,
                            onClick = { open(url) },
                        )
                    }
                    current.support?.let { url ->
                        MochiListRow(
                            stringResource(R.string.repo_support),
                            subtitle = hostOf(url),
                            leading = { KsuTile("help-buoy", KsuTileColors.Green) },
                            chevron = true,
                            onClick = { open(url) },
                        )
                    }
                }
            }

            item(key = "release") {
                MochiListSection(
                    header = stringResource(R.string.repo_section_release),
                    footer = release?.let { stringResource(R.string.repo_release_footer, formatSize(it.zipSize)) },
                ) {
                    if (release == null) {
                        MochiListRow(stringResource(R.string.repo_no_changelog), enabled = false)
                    } else {
                        Column(Modifier.fillMaxWidth().padding(MochiListDefaults.RowPadding)) {
                            Text(
                                release.name.ifBlank { release.tag },
                                style = MochiTheme.textStyles.subhead.copy(fontWeight = FontWeight.SemiBold),
                                color = MochiTheme.colorScheme.onSurface,
                            )
                            Text(
                                stringResource(R.string.repo_release_meta, release.version, formatDate(module.releaseEpochMillis)),
                                modifier = Modifier.padding(top = 2.dp),
                                style = MochiTheme.textStyles.footnote,
                                color = MochiTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                release.changelog?.takeIf { it.isNotBlank() } ?: stringResource(R.string.repo_no_changelog),
                                modifier = Modifier.padding(top = 10.dp),
                                style = MochiTheme.textStyles.subhead,
                                color = MochiTheme.colorScheme.onSurfaceVariant,
                                maxLines = 16,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        if (error != null && current == null) {
            item(key = "error") {
                MochiFootnote(
                    stringResource(R.string.repo_detail_load_failed),
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }

        item(key = "integrity") {
            MochiFootnote(
                stringResource(R.string.repo_integrity),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }

    val target = release
    MochiDialog(
        visible = confirm && target != null,
        onDismissRequest = { confirm = false },
        title = stringResource(R.string.repo_install_confirm_title, module.name),
        message =
            target?.let {
                stringResource(
                    R.string.repo_install_confirm_message,
                    it.zipName,
                    formatSize(it.zipSize),
                )
            } ?: "",
        icon = { MochiIonicon("extension-puzzle", size = 44.dp) },
        actions =
            persistentListOf(
                MochiDialogAction(stringResource(android.R.string.cancel), MochiDialogActionRole.Cancel),
                MochiDialogAction(
                    stringResource(R.string.repo_install_now),
                    MochiDialogActionRole.Primary,
                    onClick = { target?.let(::install) },
                ),
            ),
    )
}

/** 安装区：按钮 + 当前阶段说明；脚本类条目换成「在网页运行」。 */
@Composable
private fun InstallBlock(
    module: RepoModule,
    release: RepoRelease?,
    phase: InstallPhase,
    onInstall: () -> Unit,
    onOpenWeb: () -> Unit,
) {
    val cs = MochiTheme.colorScheme
    val busy = phase is InstallPhase.Downloading
    Column(Modifier.fillMaxWidth().padding(MochiListDefaults.RowPadding)) {
        when {
            module.isScript -> {
                Text(
                    stringResource(R.string.repo_script_hint),
                    style = MochiTheme.textStyles.subhead,
                    color = cs.onSurfaceVariant,
                )
                MochiButton(
                    onClick = onOpenWeb,
                    style = MochiButtonStyle.Filled,
                    modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
                ) { Text(stringResource(R.string.repo_open_web)) }
            }

            release == null -> {
                Text(
                    stringResource(R.string.repo_no_release),
                    style = MochiTheme.textStyles.subhead,
                    color = cs.onSurfaceVariant,
                )
            }

            else -> {
                MochiButton(
                    onClick = onInstall,
                    style = MochiButtonStyle.Filled,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    MochiIonicon("cloud-download", size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (phase) {
                            is InstallPhase.Downloading ->
                                stringResource(R.string.repo_downloading, (phase.progress * 100).toInt())
                            else -> stringResource(R.string.repo_install_now)
                        },
                    )
                }
                Text(
                    stringResource(R.string.repo_install_subtitle, formatSize(release.zipSize)),
                    modifier = Modifier.padding(top = 8.dp),
                    style = MochiTheme.textStyles.footnote,
                    color = cs.onSurfaceVariant,
                )
                // 下载失败留在详情页展示；安装成败在安装输出页，这里不再重复
                if (phase is InstallPhase.DownloadFailed) {
                    Row(
                        Modifier.padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MochiIonicon(
                            "alert-circle",
                            size = 18.dp,
                            tint = cs.error,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            phase.message,
                            style = MochiTheme.textStyles.footnote,
                            color = cs.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(
    title: String,
    value: String,
    icon: String,
) {
    MochiListRow(
        title,
        subtitle = value,
        leading = { KsuTile(icon, KsuTileColors.Gray) },
    )
}

/** 只用来展示链接的主机名，避免长 URL 撑爆一行。 */
private fun hostOf(url: String): String =
    runCatching { Uri.parse(url).host ?: url }.getOrDefault(url)

/** 字节数 → 大小；ZIP 大多数在几百 KB 到几 MB。 */
internal fun formatSize(bytes: Long): String =
    when {
        bytes <= 0 -> "—"
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1024L -> String.format(Locale.US, "%d KB", bytes / 1024L)
        else -> "$bytes B"
    }

/** epoch → 本地日期；解析失败的时间戳显示「—」。 */
internal fun formatDate(epochMillis: Long): String {
    if (epochMillis <= 0) return "—"
    val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return format.format(Date(epochMillis))
}
