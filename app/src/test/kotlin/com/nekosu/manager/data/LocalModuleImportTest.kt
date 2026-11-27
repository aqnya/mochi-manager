package com.nekosu.manager.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 本地 ZIP 导入的前置校验。
 *
 * SAF 的 `content://` 不能直接交给 root shell，复制之后先按魔数挡掉明显选错的文件：
 * 一个文本文件塞进 ncore 只会得到一行难以理解的解包错误。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class LocalModuleImportTest {
    private fun temp(bytes: ByteArray): File =
        File.createTempFile("module", ".zip").apply {
            writeBytes(bytes)
            deleteOnExit()
        }

    @Test
    fun `ZIP 本地文件头能识别`() {
        assertTrue(LocalModuleImport.looksLikeZip(temp(byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0x00, 0x00))))
    }

    @Test
    fun `空 ZIP 的中央目录标记也能识别`() {
        assertTrue(LocalModuleImport.looksLikeZip(temp(byteArrayOf(0x50, 0x4b, 0x05, 0x06, 0x00, 0x00))))
    }

    @Test
    fun `文本文件与空文件都不是 ZIP`() {
        assertFalse(LocalModuleImport.looksLikeZip(temp("module.prop\nid=x\n".toByteArray())))
        assertFalse(LocalModuleImport.looksLikeZip(temp(ByteArray(0))))
        assertFalse(LocalModuleImport.looksLikeZip(temp(byteArrayOf(0x50))))
    }
}
