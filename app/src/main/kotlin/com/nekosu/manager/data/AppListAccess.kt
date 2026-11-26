package com.nekosu.manager.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 小米系 ROM（MIUI / HyperOS）的「读取应用列表」受限处理。
 *
 * HyperOS / MIUI 在「权限管理 → 特殊权限」里对第三方应用读取已安装应用列表有独立开关，
 * 关掉之后 `getInstalledPackages` 只返回应用自身（系统侧裁剪了可见性），
 * 界面上表现为列表一片空白，用户会误以为应用坏了。这里负责三件事：
 *
 * 1. 判断当前读到的列表是否「像被限制了」；
 * 2. 把用户送到小米的权限编辑页（失败时逐级退到应用详情页、设置首页）；
 * 3. 返回应用后由页面自己重新加载（见 SuperUserScreen 的 ON_RESUME 监听）。
 *
 * 注意：`QUERY_ALL_PACKAGES` 是普通权限，声明即生效，没有运行时申请这一说；
 * 国内的「读取应用列表」开关属于厂商自加的能力，只能跳设置手动开。
 */
object AppListAccess {
    /**
     * 小米自加的「获取应用列表」权限（HyperOS / MIUI 13+）。
     * 按小米开放平台文档，它在 QUERY_ALL_PACKAGES 之上额外管控 `getInstalledPackages` 系列接口，
     * 由 `com.lbe.security.miui` 提供，可以像普通运行时权限一样动态申请。
     */
    const val PERMISSION = "com.android.permission.GET_INSTALLED_APPS"

    private const val PERMISSION_PROVIDER = "com.lbe.security.miui"
    private const val PREFS = "app_list_access"
    private const val KEY_ASKED = "asked_runtime_permission"

    /** 系统是否支持动态申请（只有小米安全中心的权限才带这个 provider 包名）。 */
    fun supportsRuntimeRequest(context: Context): Boolean =
        runCatching {
            val info = context.packageManager.getPermissionInfo(PERMISSION, 0)
            info != null && info.packageName == PERMISSION_PROVIDER
        }.getOrDefault(false)

    /** 当前是否已授予「获取应用列表」。 */
    fun isGranted(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * 是否应该自动弹一次系统授权框。
     *
     * 只在用户还没被问过时返回 true：拒绝之后不再每次进页面都弹（系统也不会再弹），
     * 改由界面上的引导卡把用户送到权限管理页。
     */
    fun shouldAskOnce(context: Context): Boolean {
        if (!supportsRuntimeRequest(context) || isGranted(context)) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED, false)) return false
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
        return true
    }

    /**
     * 读到的第三方应用为 0：几乎只能是被系统限制了列表读取。
     * 自身包名在仓库里已被排除，正常情况下至少能看到用户自己装的几个应用。
     */
    fun looksRestricted(apps: List<InstalledApp>): Boolean = apps.none { !it.isSystem }

    /** 小米 / 红米 / POCO（含 HyperOS、MIUI）。 */
    fun isMiuiFamily(): Boolean {
        val brand = "${Build.BRAND} ${Build.MANUFACTURER}".lowercase()
        return listOf("xiaomi", "redmi", "poco").any { brand.contains(it) }
    }

    /**
     * 打开「读取应用列表」的授权入口，返回是否成功拉起某个页面。
     *
     * 依次尝试（都是小米安全中心的私有组件，不同 ROM 版本只有其中之一存在）：
     * `PermissionsEditorActivity` → `PkgAppPermissionsEditorActivity`
     * → 应用详情页 → 系统设置首页。
     */
    fun openPermissionSettings(context: Context): Boolean {
        val pkg = context.packageName
        val candidates =
            buildList {
                add(miuiPermissionIntent("com.miui.permcenter.permissions.PermissionsEditorActivity", pkg))
                add(miuiPermissionIntent("com.miui.permcenter.permissions.PkgAppPermissionsEditorActivity", pkg))
                add(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", pkg, null)),
                )
                add(Intent(Settings.ACTION_SETTINGS))
            }

        for (intent in candidates) {
            val started =
                runCatching {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    true
                }.getOrDefault(false)
            if (started) return true
        }
        return false
    }

    private fun miuiPermissionIntent(
        activity: String,
        pkg: String,
    ): Intent =
        Intent("miui.intent.action.APP_PERM_EDITOR").apply {
            component = ComponentName("com.miui.securitycenter", activity)
            putExtra("extra_pkgname", pkg)
        }
}
