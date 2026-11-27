package com.nekosu.manager.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 安装状态的判定逻辑。
 *
 * 这里锁的是「不许猜」：探测没跑成时必须是 Unknown / Checking，绝不能因为
 * 曾经部署过就报成已安装——那正是之前首页显示假数据的原因。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class NekoStatusTest {
    @Test
    fun `test 输出 present 判为存在`() {
        assertEquals(NcoreImage.Present, NekoStatusRepository.classifyNcore("present\n"))
    }

    @Test
    fun `test 输出 missing 判为不存在`() {
        assertEquals(NcoreImage.Missing, NekoStatusRepository.classifyNcore("missing\n"))
    }

    @Test
    fun `没有输出时不猜结论`() {
        assertEquals(NcoreImage.Unknown, NekoStatusRepository.classifyNcore(""))
    }

    @Test
    fun `还没探测完是 Checking`() {
        assertEquals(NekoStatus.Phase.Checking, NekoStatus().phase)
    }

    @Test
    fun `拿到内核版本即视为通道可用`() {
        val status = NekoStatus(kernelVersion = "1.0.8", ncore = NcoreImage.Present, checked = true)
        assertEquals(NekoStatus.Phase.Active, status.phase)
    }

    @Test
    fun `ncore 在但内核通道不通是 Detached`() {
        val status = NekoStatus(kernelVersion = null, ncore = NcoreImage.Present, checked = true)
        assertEquals(NekoStatus.Phase.Detached, status.phase)
    }

    @Test
    fun `两样都没有才是未安装`() {
        val status = NekoStatus(kernelVersion = null, ncore = NcoreImage.Missing, checked = true)
        assertEquals(NekoStatus.Phase.Absent, status.phase)
    }

    @Test
    fun `探测失败也按未安装处理而不是已安装`() {
        val status = NekoStatus(kernelVersion = null, ncore = NcoreImage.Unknown, checked = true)
        assertEquals(NekoStatus.Phase.Absent, status.phase)
    }
}
