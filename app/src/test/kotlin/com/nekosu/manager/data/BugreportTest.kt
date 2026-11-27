package com.nekosu.manager.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.Date
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
@Config(sdk = [30])
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
}
