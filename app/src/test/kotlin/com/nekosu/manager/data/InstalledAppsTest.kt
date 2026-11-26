package com.nekosu.manager.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 校验 PackageManager 读取链路：用户应用 / 系统应用（含 HyperOS 常见的
 * 「可更新系统应用」标记）都能读到，停用应用会被过滤，自身包名被排除。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class InstalledAppsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val pm: PackageManager = context.packageManager

    private fun addPackage(
        pkg: String,
        label: String,
        uid: Int,
        flags: Int = 0,
        enabled: Boolean = true,
        installTime: Long = 0L,
    ) {
        val info =
            PackageInfo().apply {
                packageName = pkg
                firstInstallTime = installTime
                applicationInfo =
                    ApplicationInfo().apply {
                        packageName = pkg
                        nonLocalizedLabel = label
                        this.uid = uid
                        this.flags = flags
                        this.enabled = enabled
                    }
            }
        shadowOf(pm).installPackage(info)
    }

    @Before
    fun setUp() {
        addPackage("com.test.alpha", "阿尔法", uid = 10_123, installTime = 1_000L)
        addPackage(
            "com.test.system",
            "系统设置",
            uid = 1_000,
            flags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP,
        )
        addPackage("com.test.disabled", "已冻结", uid = 10_456, enabled = false)
    }

    @Test
    fun loadsInstalledApps() =
        runBlocking {
            val apps = InstalledAppsRepository.load(context)
            val packages = apps.map { it.packageName }

            assertTrue("应当读到用户应用", "com.test.alpha" in packages)
            assertTrue("可更新的系统应用也要算系统应用", "com.test.system" in packages)
            assertFalse("停用的应用必须过滤", "com.test.disabled" in packages)
            assertFalse("必须过滤自身", context.packageName in packages)

            val alpha = apps.first { it.packageName == "com.test.alpha" }
            assertEquals("阿尔法", alpha.label)
            assertEquals(0, alpha.userId)
            assertFalse(alpha.isSystem)
            assertEquals(1_000L, alpha.installTime)

            val system = apps.first { it.packageName == "com.test.system" }
            assertTrue("FLAG_UPDATED_SYSTEM_APP 要归为系统应用", system.isSystem)
        }
}
