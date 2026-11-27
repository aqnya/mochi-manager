package com.nekosu.manager.data

import com.nekosu.manager.data.registry.InstallOutcome
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 安装会话的状态机。
 *
 * 这里不碰真机：`installer` 与 `rebootRequired` 都换成假的，只验证
 * 「输出即时可见、结果按包记账、清屏序列生效、是否重启判断正确、取消能复位」。
 */
class ModuleInstallSessionTest {
    private val originalInstaller = ModuleInstallSession.installer
    private val originalReboot = ModuleInstallSession.rebootRequired

    @Before
    fun setUp() {
        // 默认按「需要重启」处理，避免单测真的去探测 /data/adb
        ModuleInstallSession.rebootRequired = { true }
    }

    @After
    fun tearDown() {
        ModuleInstallSession.installer = originalInstaller
        ModuleInstallSession.rebootRequired = originalReboot
        ModuleInstallSession.dismiss()
    }

    private fun module(name: String): StagedModule =
        StagedModule(File.createTempFile("neko-", ".zip").apply { deleteOnExit() }, name, 32L)

    @Test
    fun `每行输出实时进入 state 并累计成功数`() = runBlocking {
        ModuleInstallSession.installer = { _, _, onLine ->
            onLine("- Installing to /data/adb/modules_update/demo")
            onLine("- 按音量上键继续")
            InstallOutcome.Success("ok")
        }

        ModuleInstallSession.start(listOf(module("demo.zip")))

        val state = withTimeout(5_000) { ModuleInstallSession.state.first { it.finished } }
        assertEquals(1, state.success)
        assertTrue(state.failures.isEmpty())
        assertTrue(state.lines.any { it.contains("Installing to") })
        // 交互提示只是普通一行输出，页面不再为它做输入框
        assertTrue(state.lines.any { it.contains("按音量上键") })
        assertTrue(state.needsReboot)
        assertFalse(state.failed)
    }

    @Test
    fun `热安装成功后不要求重启`() = runBlocking {
        ModuleInstallSession.rebootRequired = { false }
        ModuleInstallSession.installer = { _, _, onLine ->
            onLine("- Done")
            InstallOutcome.Success("ok")
        }

        ModuleInstallSession.start(listOf(module("hot.zip")))

        val state = withTimeout(5_000) { ModuleInstallSession.state.first { it.finished } }
        assertEquals(1, state.success)
        assertFalse(state.needsReboot)
    }

    @Test
    fun `失败会按文件名记账且不影响后续包`() = runBlocking {
        ModuleInstallSession.installer = { file, _, onLine ->
            if (file.name.startsWith("neko-")) onLine("[失败] 演示")
            InstallOutcome.Failure("exit 1")
        }

        ModuleInstallSession.start(listOf(module("a.zip"), module("b.zip")))

        val state = withTimeout(5_000) { ModuleInstallSession.state.first { it.finished } }
        assertEquals(0, state.success)
        assertEquals(2, state.failures.size)
        assertTrue(state.failures.first().startsWith("a.zip："))
        assertFalse(state.running)
        assertTrue(state.failed)
    }

    @Test
    fun `取消后 running 复位并标记 cancelled`() = runBlocking {
        ModuleInstallSession.installer = { _, _, onLine ->
            onLine("- working")
            // 模拟交互式脚本一直等按键
            while (true) delay(50)
            error("取消后不会执行到这里")
        }

        ModuleInstallSession.start(listOf(module("slow.zip")))
        withTimeout(2_000) { ModuleInstallSession.state.first { it.lines.isNotEmpty() } }

        ModuleInstallSession.cancel()

        val state = withTimeout(5_000) { ModuleInstallSession.state.first { !it.running } }
        assertTrue(state.cancelled)
        assertTrue(state.failed)
        assertTrue(state.lines.any { it.contains("已取消") })
    }

    @Test
    fun `清屏序列会先清掉已有输出`() = runBlocking {
        ModuleInstallSession.installer = { _, _, onLine ->
            onLine("旧的一屏内容")
            onLine("\u001b[H\u001b[J新的进度")
            InstallOutcome.Success("ok")
        }

        ModuleInstallSession.start(listOf(module("clear.zip")))

        val state = withTimeout(5_000) { ModuleInstallSession.state.first { it.finished } }
        // 清屏只保留新内容，不会把旧输出一直堆在上面
        assertFalse(state.lines.any { it.contains("旧的一屏内容") })
        assertTrue(state.lines.any { it.contains("新的进度") })
    }
}
