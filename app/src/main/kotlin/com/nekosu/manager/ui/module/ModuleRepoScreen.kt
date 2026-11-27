package com.nekosu.manager.ui.module

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nekosu.manager.R
import com.nekosu.manager.data.registry.ModuleRepoRepository
import com.nekosu.manager.data.registry.ModuleRepoSource
import com.nekosu.manager.data.registry.RepoCatalog
import com.nekosu.manager.data.registry.RepoModule
import com.nekosu.manager.ui.mochi.MochiFootnote
import com.nekosu.manager.ui.mochi.RepoIcon
import com.nekosu.manager.ui.superuser.runRefresh
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import ui.feice.mochi.basic.MochiBarGroup
import ui.feice.mochi.basic.MochiBarGroupDropdownItem
import ui.feice.mochi.basic.MochiButton
import ui.feice.mochi.basic.MochiButtonStyle
import ui.feice.mochi.basic.MochiEmptyState
import ui.feice.mochi.basic.MochiItem
import ui.feice.mochi.basic.MochiSearchBar
import ui.feice.mochi.basic.Text
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.MochiListStatusChip
import ui.feice.mochi.list.mochiListSection
import ui.feice.mochi.list.rows
import ui.feice.mochi.navigation.MochiBackButton
import ui.feice.mochi.navigation.MochiLazyLargeTitleScaffold
import ui.feice.mochi.theme.MochiTheme
import java.text.Collator
import java.util.Locale

/** 仓库列表的排序方式，和 KernelSU 管理器的「更新时间 / 名称 / 星标数」一致。 */
enum class RepoSort(
    val labelRes: Int,
) {
    UPDATED(R.string.repo_sort_updated),
    NAME(R.string.repo_sort_name),
    STARS(R.string.repo_sort_stars),
    ;

    fun sort(modules: List<RepoModule>): List<RepoModule> =
        when (this) {
            UPDATED -> modules.sortedByDescending { it.releaseEpochMillis }
            NAME -> {
                val collator = Collator.getInstance(Locale.CHINA)
                modules.sortedWith { a, b -> collator.compare(a.name, b.name) }
            }

            // 星标未知的排在已知之后（含 0），同分按发布时间
            STARS ->
                modules.sortedWith(
                    compareByDescending<RepoModule> { it.stars ?: -1 }
                        .thenByDescending { it.releaseEpochMillis },
                )
        }
}

/** 仓库里一件模块的展示：搜索过滤 + 排序都放这里，页面只负责排版。 */
internal fun repoModules(
    catalog: RepoCatalog?,
    query: String,
    sort: RepoSort,
): List<RepoModule> {
    val all = catalog?.modules.orEmpty()
    val filtered = if (query.isBlank()) all else all.filter { it.matches(query.trim()) }
    return sort.sort(filtered)
}

/**
 * 模块仓库：KernelSU Modules 的在线索引。
 *
 * 数据来自 `modules.kernelsu.org/api/v1`：先取 index，再取 modules，两者 buildId 必须一致；
 * 网络失败时退回上一次完整副本并标记「可能不是最新」。列表支持搜索与三种排序，
 * 下拉刷新走和超级用户同一套 `runRefresh`（最短 350ms、最长 30s 必返回）。
 */
@Composable
fun ModuleRepoScreen(
    onBack: () -> Unit,
    onOpenModule: (RepoModule) -> Unit,
    modifier: Modifier = Modifier,
    source: ModuleRepoSource = ModuleRepoRepository.get(LocalContext.current),
) {
    var catalog by remember { mutableStateOf<RepoCatalog?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reloadKey by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(RepoSort.UPDATED) }
    var menu by remember { mutableStateOf(false) }

    suspend fun load() {
        loading = true
        try {
            catalog = source.catalog()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = e
        } finally {
            loading = false
        }
    }

    LaunchedEffect(reloadKey) { load() }

    val modules = remember(catalog, query, sort) { repoModules(catalog, query, sort) }
    val searching = query.isNotBlank()
    val context = LocalContext.current
    val sortLabels = RepoSort.entries.map { context.getString(it.labelRes) }
    val iconDir = remember(context) { ModuleRepoRepository.get(context).iconDir }

    MochiLazyLargeTitleScaffold(
        title = stringResource(R.string.repo_title),
        subtitle = stringResource(R.string.repo_subtitle),
        modifier = modifier,
        navigationIcon = { MochiBackButton(onClick = onBack) },
        actions = {
            MochiBarGroup(navId = "repo.actions") {
                MochiBarGroupDropdownItem(
                    expanded = menu,
                    onExpandedChange = { menu = it },
                    items =
                        RepoSort.entries
                            .mapIndexed { index, item ->
                                MochiItem(sortLabels[index], icon = checkIcon(sort == item))
                            }.toImmutableList(),
                    onItemClick = { sort = RepoSort.entries[it] },
                    navId = "repo.sort",
                    contentKey = "filter",
                ) { MochiIonicon("filter") }
            }
        },
        searchBar = {
            MochiSearchBar(
                query = query,
                onQueryChange = { query = it },
                placeholder = stringResource(R.string.repo_search),
                onCancel = { query = "" },
            )
        },
        // onRefresh 必须自己完成加载再返回，否则指示器会一直转（库的刷新态由它复位）
        onRefresh = { runRefresh(timeoutMs = 30_000) { load() } },
    ) {
        val current = catalog
        when {
            current == null && loading -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                }
            }

            current == null -> item(key = "failed") {
                // 缺 INTERNET 权限时重试没意义（不重新装包不会好），把原因直接写出来
                val noPermission = error is SecurityException
                MochiEmptyState(
                    title = stringResource(R.string.repo_load_failed),
                    message =
                        stringResource(
                            if (noPermission) {
                                R.string.repo_load_failed_permission
                            } else {
                                R.string.repo_load_failed_hint
                            },
                        ),
                    icon = { MochiIonicon("cloud-offline", size = 44.dp) },
                    action = {
                        if (!noPermission) {
                            MochiButton(
                                onClick = { reloadKey++ },
                                style = MochiButtonStyle.Tonal,
                            ) {
                                Text(stringResource(R.string.repo_retry))
                            }
                        }
                    },
                )
            }

            else -> {
                if (current.stale) {
                    item(key = "stale") {
                        MochiFootnote(
                            stringResource(R.string.repo_stale),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        )
                    }
                }
                if (modules.isEmpty()) {
                    item(key = "empty") {
                        MochiEmptyState(
                            stringResource(if (searching) R.string.repo_no_results else R.string.repo_empty),
                            icon = { MochiIonicon(if (searching) "search" else "storefront", size = 44.dp) },
                        )
                    }
                } else {
                    mochiListSection(key = "modules") {
                        rows(modules, key = { it.id }) { module ->
                            RepoRow(module = module, iconDir = iconDir, onClick = { onOpenModule(module) })
                        }
                    }
                }
                item(key = "footer") {
                    MochiFootnote(
                        stringResource(R.string.repo_footer),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** 一行模块：图标 + 名称 + 摘要，右侧星标 + 缩略版本号。 */
@Composable
private fun RepoRow(
    module: RepoModule,
    iconDir: java.io.File,
    onClick: () -> Unit,
) {
    val cs = MochiTheme.colorScheme
    MochiListRow(
        module.name,
        subtitle = module.summary.ifBlank { module.authorText },
        leading = { RepoIcon(module.iconUrl, module.name, iconDir, size = 40.dp) },
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                module.stars?.let { stars ->
                    MochiListStatusChip(
                        "$stars",
                        containerColor = cs.onSurface.copy(alpha = 0.06f),
                        contentColor = cs.onSurfaceVariant,
                    )
                }
                if (module.version.isNotBlank()) {
                    // 完整版本号（带 hash、构建号）会把标题挤成「Z…」，右侧只放缩略版
                    Text(
                        shortVersion(module.version),
                        style = MochiTheme.textStyles.footnote,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        },
        chevron = true,
        onClick = onClick,
    )
}

/**
 * 版本号缩略：去掉括号里的 hash / 构建信息与 `+` 后的元数据，只留版本本体。
 * `v2.22.01 (458-952f973)` → `v2.22.01`；仍超长时按省略号截断。
 */
internal fun shortVersion(version: String): String {
    val body =
        version
            .substringBefore('(')
            .substringBefore('+')
            .trim()
    return if (body.length > 16) body.take(15) + "…" else body.ifBlank { version }
}

private fun checkIcon(on: Boolean): (@Composable (androidx.compose.ui.graphics.Color) -> Unit)? =
    if (on) {
        { tint -> MochiIonicon("checkmark", tint = tint) }
    } else {
        null
    }
