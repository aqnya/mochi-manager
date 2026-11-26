package com.nekosu.manager.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 共享 uid 的处理：
 *
 * - 列表按 uid 归并，而不是按包名逐行铺开（授权粒度是 uid）；
 * - 有 sharedUserLabel / android.uid.* 时用属主名做标题；
 * - 配置按 uid 存取，组内任一个包进来看到的都是同一份。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class SharedUidTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun app(
        pkg: String,
        label: String,
        uid: Int,
        sharedUserId: String? = null,
        sharedLabel: String? = null,
    ) = InstalledApp(
        packageName = pkg,
        label = label,
        uid = uid,
        userId = uid / 100_000,
        isSystem = false,
        hasLauncher = true,
        installTime = 0L,
        enabled = true,
        sharedUserId = sharedUserId,
        sharedLabel = sharedLabel,
    )

    @Test
    fun `apps with same uid are merged into one group`() {
        val groups =
            groupApps(
                listOf(
                    app("com.xiaomi.xmsf", "小米服务框架", 10_600),
                    app("com.miui.cloudservice", "小米云服务", 10_600),
                    app("com.tencent.mm", "微信", 10_123),
                ),
            )

        assertEquals(2, groups.size)
        val shared = groups.first { it.uid == 10_600 }
        assertEquals(2, shared.apps.size)
        assertTrue(shared.shared)
        assertEquals("小米服务框架", shared.title)
        // 没有 sharedUserLabel 时取包名最短的做代表
        assertEquals("com.xiaomi.xmsf", shared.primary.packageName)
        assertFalse(groups.first { it.uid == 10_123 }.shared)
    }

    @Test
    fun `android uid groups use the shared user id as title and preferred package`() {
        val group =
            groupApps(
                listOf(
                    // 名称排序会把 systemui 排在前面，选代表仍应按包名长度挑 settings
                    app("com.android.settings", "系统设置", 1000, "android.uid.system"),
                    app("com.android.systemui", "界面", 1000, "android.uid.system"),
                ),
            ).single()

        assertEquals("android.uid.system", group.title)
        assertEquals("android.uid.system", group.ownerName)
        // 两个包都不在 PREFERRED_PKG_BY_SUID 的名单里，按包名长度取最短的
        assertEquals("com.android.settings", group.primary.packageName)
    }

    @Test
    fun `shared user label wins over shared user id`() {
        val group =
            groupApps(
                listOf(
                    app("com.miui.a", "A", 10_700, "com.miui.shared", "小米云服务"),
                    app("com.miui.b", "B", 10_700, "com.miui.shared", "小米云服务"),
                ),
            ).single()

        assertEquals("小米云服务", group.title)
    }

    @Test
    fun `single app group keeps its own name`() {
        val group = groupApps(listOf(app("com.tencent.mm", "微信", 10_123))).single()
        assertFalse(group.shared)
        assertNull(group.ownerName)
        assertEquals("微信", group.title)
    }

    @Test
    fun `profile is stored per uid and shared by the whole group`() {
        val uid = 10_900
        SuProfileStore.save(context, SuProfile("com.demo.one", uid, allowSu = true))

        val saved = SuProfileStore.get(context, uid)
        assertEquals(true, saved?.allowSu)
        assertEquals(uid, saved?.appUid)

        // 组内另一个包（同 uid）读到的是同一份，改一处即整组生效
        SuProfileStore.save(context, saved!!.copy(umountModules = true))
        assertTrue(SuProfileStore.get(context, uid)!!.umountModules)

        SuProfileStore.reset(context, uid)
        assertNull(SuProfileStore.get(context, uid))
    }
}
