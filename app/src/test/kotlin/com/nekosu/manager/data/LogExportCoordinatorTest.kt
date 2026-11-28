package com.nekosu.manager.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 无 root 确认框的状态迁移。
 *
 * 真机上「分享正常、直接导出失败」的根因是：点「继续导出」后状态仍停在 [LogExportState.ConfirmPartial]，
 * 对话框随后补发的关闭回调调用了会删文件的 dismiss，把正在写入的源文件删掉，save 读到 ENOENT。
 * 这里锁住两点：确认后必须离开 ConfirmPartial；dismiss / cancelPartial 不得删除仍在使用的报告。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31])
class LogExportCoordinatorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun reset() {
        LogExportCoordinator.state = LogExportState.Idle
    }

    @Test
    fun `dismiss never deletes a pending report`() {
        val file = File(context.cacheDir, "report-dismiss.tar.gz").apply { writeText("x") }

        LogExportCoordinator.state = LogExportState.ConfirmPartial(file, LogAction.Save, 0)
        LogExportCoordinator.dismiss()

        // 新实现的 dismiss 只管结果提示，不碰 ConfirmPartial，更不删文件
        assertTrue("dismiss 不应删除待导出的报告", file.isFile)
        assertTrue(LogExportCoordinator.state is LogExportState.ConfirmPartial)
        file.delete()
    }

    @Test
    fun `cancel from the confirm step keeps cache file`() {
        val file = File(context.cacheDir, "report-cancel.tar.gz").apply { writeText("x") }

        LogExportCoordinator.state = LogExportState.ConfirmPartial(file, LogAction.Save, 3)
        LogExportCoordinator.cancelPartial()

        // 取消回到 Idle；文件留给下一次收集统一清理，避免与保存流程竞态
        assertEquals(LogExportState.Idle, LogExportCoordinator.state)
        assertTrue("取消不应立即删除缓存报告", file.isFile)
        file.delete()
    }

    @Test
    fun `confirm moves state out of confirm step before saving`() {
        val file = File(context.cacheDir, "report-confirm.tar.gz").apply { writeText("payload") }

        LogExportCoordinator.state = LogExportState.ConfirmPartial(file, LogAction.Save, 0)
        LogExportCoordinator.confirm(context)

        // 关键断言：确认后立刻离开 ConfirmPartial，后续任何关闭回调都不会再删这个文件。
        // 这里不断言文件是否还在——保存成功本来就会删掉源包。
        assertFalse(LogExportCoordinator.state is LogExportState.ConfirmPartial)
        file.delete()
    }
}
