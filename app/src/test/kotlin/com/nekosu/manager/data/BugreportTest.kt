package com.nekosu.manager.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.Date
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 日志收集的静态部分：脚本内容、文件名、基础信息。
 *
 * 真机上跑 `su` 的那一段没法在单测里覆盖，但脚本本身至少要是合法 sh，
 * 且关键命令不能被改掉（dmesg / logcat / getenforce / /data/adb）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class BugreportTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `script covers the important sources`() {
        val script = Bugreport.collectScript(File("/tmp/bugreport"))
        listOf(
            "getenforce",
            "getprop",
            "dmesg",
            "logcat -b all",
            "/proc/1/mountinfo",
            "/proc/kallsyms",
            "/data/tombstones",
            "/data/system/dropbox",
            "/sys/fs/pstore",
            "/data/adb/ksu/log",
            "/data/adb/ksu/.allowlist",
            "/data/adb/modules",
            "ksud --version",
            "/proc/version",
            "/proc/self/status",
        ).forEach { needle ->
            assertTrue("脚本缺少 $needle", script.contains(needle))
        }
        // 输出目录用单引号包住，避免路径里的空格把命令拆开
        assertTrue(script.contains("DIR='/tmp/bugreport'"))
        assertTrue(script.trimEnd().endsWith("exit 0"))
    }

    @Test
    fun `script is valid sh`() {
        val script = File(context.cacheDir, "collect-check.sh").apply {
            writeText(Bugreport.collectScript(File(context.cacheDir, "bugreport")))
        }
        val process = ProcessBuilder("sh", "-n", script.absolutePath).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals("sh -n 报错：$output", 0, process.waitFor())
    }

    @Test
    fun `file name carries timestamp`() {
        val name = Bugreport.bugreportName(Date(1_794_280_035_000L))
        // 时间按本地时区格式化，这里只校验前缀、扩展名与形状
        assertTrue(name.startsWith("NekoSU_bugreport_"))
        assertTrue(name.endsWith(".tar.gz"))
        assertTrue(Regex("""NekoSU_bugreport_\d{4}-\d{2}-\d{2}_\d{2}_\d{2}\.tar\.gz""").matches(name))
    }

    @Test
    fun `basic report has device fields`() {
        val text = Bugreport.basicReport(context)
        listOf("NekoSU Manager", "Android:", "BRAND:", "MODEL:", "FINGERPRINT:", "SELinux:", "Kernel:").forEach {
            assertTrue("基础信息缺少 $it", text.contains(it))
        }
    }

    @Test
    fun `basic report records whether root was used`() {
        assertTrue(Bugreport.basicReport(context, rooted = true).contains("Root: yes"))
        val local = Bugreport.basicReport(context, rooted = false)
        assertTrue(local.contains("Root: no"))
        assertTrue(local.contains("Collected as: app"))
    }

    @Test
    fun `empty outputs are pruned and listed`() {
        val dir = File(context.cacheDir, "bugreport-prune").apply {
            deleteRecursively()
            mkdirs()
        }
        File(dir, "collect.sh").writeText("#!/system/bin/sh\n")
        File(dir, "dmesg.txt").writeText("")
        File(dir, "logcat.txt").writeText("")
        File(dir, "getprop.txt").writeText("[ro.build.version.sdk]: [30]\n")

        val missing = Bugreport.pruneEmpty(dir)

        // 空的删掉、有内容的留下，脚本本身不算缺失
        assertEquals(listOf("dmesg.txt", "logcat.txt"), missing)
        assertTrue(!File(dir, "dmesg.txt").exists())
        assertTrue(File(dir, "getprop.txt").isFile)
        assertTrue(File(dir, "collect.sh").isFile)
        val note = File(dir, "missing.txt").readText()
        assertTrue(note.contains("dmesg.txt"))
        assertTrue(note.contains("无 root 时"))
    }

    @Test
    fun `nothing missing writes no list`() {
        val dir = File(context.cacheDir, "bugreport-clean").apply {
            deleteRecursively()
            mkdirs()
        }
        File(dir, "getprop.txt").writeText("ok\n")
        assertTrue(Bugreport.pruneEmpty(dir).isEmpty())
        assertTrue(!File(dir, "missing.txt").exists())
    }

    @Test
    fun `app shell works without su`() =
        runBlocking {
            val result = RootShell.execAsUser("echo hello")
            assertEquals(0, result.exitCode)
            assertEquals("hello", result.output)
        }

    @Test
    fun `root probe never throws`() {
        // 沙箱里可能没有 su，也可能就是 root；两种都只返回布尔值，不能抛出去
        val rooted = runBlocking { RootShell.isRootAvailable(timeoutSeconds = 5) }
        assertTrue(rooted || !rooted)
    }
}
