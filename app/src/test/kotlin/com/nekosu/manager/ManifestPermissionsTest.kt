package com.nekosu.manager

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 模块仓库要走网络，清单里必须声明 INTERNET。
 *
 * 这条测试是回归保护：漏掉它时 `HttpURLConnection` 会抛 SecurityException，
 * 真机上只看到「加载模块仓库失败」，很难一眼看出是权限而不是网络的问题。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class ManifestPermissionsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `internet permission is declared`() {
        val info =
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_PERMISSIONS,
            )
        val declared = info.requestedPermissions.orEmpty().toList()
        assertTrue("manifest 缺少 INTERNET：$declared", Manifest.permission.INTERNET in declared)
    }
}
