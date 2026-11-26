package com.nekosu.manager.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 设备上真实安装的一个应用。
 *
 * [userId] 取自 uid，HyperOS / MIUI 的「应用双开」（手机分身）跑在 user 999，
 * 这类实例不会出现在当前用户的查询结果里，只有接 root 后 `pm list packages --user 999` 才能拿到。
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val uid: Int,
    val userId: Int,
    val isSystem: Boolean,
    val hasLauncher: Boolean,
    val installTime: Long,
    val enabled: Boolean,
    /**
     * 清单里的 `android:sharedUserId`。声明了它的应用会和别的包共用 uid，
     * 授权按 uid 生效，所以列表要按它归组（见 [AppGroup]）。
     */
    val sharedUserId: String? = null,
    /** `sharedUserLabel` 解析出的名字，HyperOS 的「小米服务框架」这类套件会用到。 */
    val sharedLabel: String? = null,
)

/**
 * 基于 PackageManager 的应用列表读取，针对 HyperOS / MIUI 做了这些处理：
 *
 * 1. Android 11+ 的包可见性：清单里声明 `QUERY_ALL_PACKAGES`，否则只能看到自己；
 * 2. 系统应用判定把 `FLAG_UPDATED_SYSTEM_APP` 一起算进去 —— HyperOS 上「应用商店」
 *    「安全中心」「传送门」等几乎都是可更新的系统应用，只判断 `FLAG_SYSTEM` 会把它们漏掉；
 * 3. Android 15+ 的「应用压缩」（archived）会返回 `isArchived`，这类应用点开要先解压，
 *    列表里直接过滤，避免点了没反应；
 * 4. 已停用（disabled）的应用也过滤，HyperOS 的「应用冻结」会留下这类条目；
 * 5. packageInfo 的 label 在少数预装应用上会抛异常或返回空串，统一回退成包名；
 * 6. 一次 `getInstalledPackages` 同时拿到 firstInstallTime，避免按应用逐个 IPC 造成卡顿。
 */
object InstalledAppsRepository {
    suspend fun load(context: Context): List<InstalledApp> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val self = context.packageName
            val launcherPackages = launcherPackages(pm)
            // 中文应用名按拼音排（Collator），直接比较 String 会按 Unicode 码点，顺序很怪
            val collator = Collator.getInstance(Locale.CHINA)
            installedPackages(pm)
                .asSequence()
                .mapNotNull { info -> info.applicationInfo?.let { info to it } }
                .filter { (_, ai) -> ai.packageName != self }
                .filter { (_, ai) -> ai.enabled && !isArchived(ai) }
                .map { (info, ai) ->
                    InstalledApp(
                        packageName = ai.packageName,
                        label = labelOf(pm, ai),
                        uid = ai.uid,
                        userId = userIdOf(ai.uid),
                        isSystem = (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0,
                        hasLauncher = ai.packageName in launcherPackages,
                        installTime = info.firstInstallTime,
                        enabled = ai.enabled,
                        sharedUserId = info.sharedUserId,
                        sharedLabel = sharedLabelOf(pm, info, ai),
                    )
                }.toList()
                .sortedWith { a, b -> collator.compare(a.label, b.label) }
        }

    private fun installedPackages(pm: PackageManager): List<PackageInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0)
        }

    private fun launcherPackages(pm: PackageManager): Set<String> =
        runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(intent, 0)
                }
            resolved.mapNotNull { it.activityInfo?.packageName }.toSet()
        }.getOrDefault(emptySet())

    private fun labelOf(
        pm: PackageManager,
        ai: ApplicationInfo,
    ): String =
        runCatching { pm.getApplicationLabel(ai).toString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: ai.packageName

    /** PER_USER_RANGE = 100000，uid / 100000 即 userId；HyperOS 的分身 profile 是 999。 */
    private fun userIdOf(uid: Int): Int = uid / 100_000

    /**
     * `sharedUserLabel` 是一个字符串资源 id，需要借应用自己的资源表解析（多语言下才会正确）。
     * 解析失败就返回 null，界面会退回 sharedUserId / 主应用名。
     */
    private fun sharedLabelOf(
        pm: PackageManager,
        info: PackageInfo,
        ai: ApplicationInfo,
    ): String? {
        val resId = info.sharedUserLabel
        if (resId == 0) return null
        return runCatching { pm.getText(ai.packageName, resId, ai)?.toString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    /** API 35 起才有「应用压缩」，低版本走不到这个方法体。 */
    @Suppress("NewApi")
    private fun isArchived(ai: ApplicationInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 35) runCatching { ai.isArchived }.getOrDefault(false) else false
}

/**
 * 应用图标加载：`loadIcon` 是 IPC，放在 IO 线程做，并用 LruCache 兜住滚动时的重复解码。
 *
 * 不用 `Drawable.toBitmap()`：HyperOS 预装应用大量是 AdaptiveIconDrawable，
 * 它的 intrinsicWidth 可能返回 -1，toBitmap 会直接抛异常。这里显式按目标尺寸画。
 */
object AppIcons {
    private const val ICON_PX = 144

    private val cache =
        object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
            override fun sizeOf(
                key: String,
                value: ImageBitmap,
            ): Int = value.width * value.height * 4
        }

    fun cached(packageName: String): ImageBitmap? = cache.get(packageName)

    suspend fun load(
        context: Context,
        packageName: String,
    ): ImageBitmap? {
        cache.get(packageName)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName).toImageBitmap(ICON_PX)
            }.getOrNull()?.also { cache.put(packageName, it) }
        }
    }

    private fun Drawable.toImageBitmap(sizePx: Int): ImageBitmap {
        val original = bounds
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        setBounds(0, 0, sizePx, sizePx)
        draw(Canvas(bitmap))
        bounds = original
        return bitmap.asImageBitmap()
    }
}
