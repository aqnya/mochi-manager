package com.nekosu.manager.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 「读取应用列表」受限判定与跳转目标。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class AppListAccessTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun installedApp(
        pkg: String,
        system: Boolean,
    ) = InstalledApp(
        packageName = pkg,
        label = pkg,
        uid = 10_000,
        userId = 0,
        isSystem = system,
        hasLauncher = true,
        installTime = 0L,
        enabled = true,
    )

    @Test
    fun restrictedWhenNoUserAppVisible() {
        // 空列表：HyperOS 关掉「读取应用列表」后就是这个状态
        assertTrue(AppListAccess.looksRestricted(emptyList()))
        // 只剩系统应用也算受限
        assertTrue(AppListAccess.looksRestricted(listOf(installedApp("com.android.settings", system = true))))
        // 能看到任意一个第三方应用就不算
        assertFalse(AppListAccess.looksRestricted(listOf(installedApp("com.android.settings", system = true), installedApp("com.tencent.mm", system = false))))
    }

    @Test
    fun opensMiuiPermissionEditorFirst() {
        assertTrue(AppListAccess.openPermissionSettings(app))

        val started = shadowOf(app).nextStartedActivity
        assertEquals("miui.intent.action.APP_PERM_EDITOR", started.action)
        assertEquals("com.miui.securitycenter", started.component?.packageName)
        // 权限页靠 extra_pkgname 定位到具体应用
        assertEquals(app.packageName, started.getStringExtra("extra_pkgname"))
    }

    @Test
    fun runtimeRequestOnlyWhenMiuiProvidesPermission() {
        // Robolectric 里没有 com.lbe.security.miui 提供的这个权限 → 不支持动态申请
        assertFalse(AppListAccess.supportsRuntimeRequest(app))
        assertFalse(AppListAccess.isGranted(app))
        // 不支持时不应弹系统框，也不该污染「已问过」标记
        assertFalse(AppListAccess.shouldAskOnce(app))
    }
}
