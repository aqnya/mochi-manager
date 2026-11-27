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
import org.junit.Test

/**
 * 安装会话的状态机。
 *
 * 这里不碰真机：`installer` 换成假的，只验证「输出即时可见、结果按包记账、
 * 交互提示能识别、取消能复位」这几件事——它们正是原来一次性执行做不到的部分。
 */
class ModuleInstallSessionTest {
    private val original = ModuleInstallSession.installer

    @After
    fun tearDown() {
        ModuleInstallSession.installer = original
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
        assertTrue(state.lines.any { it.contains("按音量上键") })
        // 提示出现后，界面把输入区展开
        assertTrue(state.awaitingInput)
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
        assertTrue(state.lines.any { it.contains("已取消") })
    }

    @Test
    fun `交互提示的识别`() {
        listOf(
            "- 按音量上键继续，按音量下键跳过",
            "Press volume up to continue",
            "Volume key: vol+ = yes",
            "请输入 y 继续",
            "[y/n]",
            "Waiting for input...",
        ).forEach { assertTrue("应识别：$it", ModuleInstallSession.looksLikePrompt(it)) }

        listOf(
            "- Installing to /data/adb/modules_update/demo",
            "- Module size: 1.2 MiB",
            "[完成] demo.zip",
            "Setting permissions",
        ).forEach { assertFalse("不该识别：$it", ModuleInstallSession.looksLikePrompt(it)) }
    }
}
