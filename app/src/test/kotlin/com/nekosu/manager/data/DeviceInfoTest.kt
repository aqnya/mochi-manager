package com.nekosu.manager.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 设备信息采集：字段必须来自真实来源，不能是占位符或写死的机型。
 *
 * Robolectric 下 Build 字段是运行时生成的，/proc 与 selinuxfs 取宿主机的值，
 * 这里只断言「取值成功且落在允许集合里」，具体机型不做假设。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class DeviceInfoTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val info by lazy { runBlocking { DeviceInfoRepository.load(context) } }

    @Test
    fun `model comes from the running build`() {
        assertFalse(info.model.isBlank())
        assertFalse(info.model == "—")
        // 没有市场名时至少包含 Build.MODEL 或厂商名
        assertTrue(
            info.model.contains(android.os.Build.MODEL, ignoreCase = true) ||
                info.model.contains(android.os.Build.MANUFACTURER, ignoreCase = true),
        )
    }

    @Test
    fun `manager version is read from package manager`() {
        assertTrue(
            "意外的管理器版本：${info.managerVersion}",
            info.managerVersion.matches(Regex(""".+ \(\d+\)""")),
        )
        assertTrue(info.managerVersion.startsWith("1.0"))
    }

    @Test
    fun `system string carries android version`() {
        assertTrue(info.system.contains("Android ${android.os.Build.VERSION.RELEASE}"))
    }

    @Test
    fun `selinux and seccomp are recognised values`() {
        assertTrue(info.selinux, info.selinux in setOf("Enforcing", "Permissive", "Disabled", "未知"))
        assertTrue(info.seccomp, info.seccomp in setOf("Filter", "Strict", "Disabled", "未知"))
    }

    @Test
    fun `kernel and fingerprint are non empty`() {
        assertFalse(info.kernel.isBlank())
        assertFalse(info.fingerprint.isBlank())
        assertFalse(info.fingerprint == "unknown")
    }

    @Test
    fun `empty placeholder differs from loaded info`() {
        assertEquals("—", DeviceInfo.Empty.model)
        assertFalse(info.model == DeviceInfo.Empty.model)
    }
}
