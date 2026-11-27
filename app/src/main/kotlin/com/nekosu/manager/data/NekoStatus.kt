package com.nekosu.manager.data

import android.content.Context
import android.util.Log
import com.nekosu.manager.neko.NekoKernel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** `/data/adb/ncore` 的真实存在情况。 */
enum class NcoreImage {
    /** `test -x` 通过：文件在，且可执行。 */
    Present,

    /** `test -x` 明确返回不存在。 */
    Missing,

    /** 探测本身没跑成（su 不可用、超时），不能据此下结论。 */
    Unknown,
}

/**
 * 一次安装状态探测的结果。
 *
 * 三个字段都是「查出来的」，没有默认的成功值：[kernelVersion] 来自内核
 * `IOC_GET_VERSION`，[ncore] 来自对 `/data/adb/ncore` 的实际 `test -x`，
 * [moduleCount] 来自 `ncore module list`。任何一项查不到就是 null / Unknown，
 * 不会退回到编译期常量。
 */
data class NekoStatus(
    val kernelVersion: String? = null,
    val ncore: NcoreImage = NcoreImage.Unknown,
    val moduleCount: Int? = null,
    val checked: Boolean = false,
) {
    enum class Phase {
        /** 还没探测完。 */
        Checking,

        /** 内核控制通道可用：LKM 已加载，且本应用被 ncore 认定为 manager。 */
        Active,

        /** ncore 已就位，但拿不到内核控制通道（模块没加载 / 不是 manager）。 */
        Detached,

        /** 既没有内核通道，也没有 ncore。 */
        Absent,
    }

    val phase: Phase
        get() =
            when {
                !checked -> Phase.Checking
                kernelVersion != null -> Phase.Active
                ncore == NcoreImage.Present -> Phase.Detached
                else -> Phase.Absent
            }
}

/**
 * 安装状态的唯一来源。
 *
 * 界面只观察 [state]，不再自己拼「已安装」的判断；[refresh] 是唯一写入口，
 * 每次进入首页/模块页时重新探测，避免装完内核模块还要杀进程才能看到变化。
 */
object NekoStatusRepository {
    private const val TAG = "NekoSU/Status"

    /** 内核引导 ncore 时使用的固定路径。 */
    const val NCORE_PATH = "/data/adb/ncore"

    private val _state = MutableStateFlow(NekoStatus())
    val state: StateFlow<NekoStatus> = _state

    suspend fun refresh(context: Context) {
        val app = context.applicationContext

        val version =
            withContext(Dispatchers.IO) {
                runCatching { NekoKernel.version() }
                    .onFailure { Log.w(TAG, "读取内核版本失败：${it.message}") }
                    .getOrNull()
            }
        val image = probeNcore()
        // 模块数只有内核通道通了才有意义：否则 ncore 跑不起来，读到的只会是空。
        val modules = if (version != null) ModuleStore.load().orEmpty().size else null

        _state.value =
            NekoStatus(
                kernelVersion = version,
                ncore = image,
                moduleCount = modules,
                checked = true,
            )
        Log.i(TAG, "内核=${version ?: "不可用"} ncore=$image 模块=${modules ?: "未知"}")
    }

    /** 部署成功后立刻标成 Present，省掉一次 su 探测。 */
    fun markDeployed() {
        _state.value = _state.value.copy(ncore = NcoreImage.Present)
    }

    /**
     * 真实探测 `/data/adb/ncore`。
     *
     * 应用进程读不了 /data/adb（SELinux 直接拒），所以借 `su -c` 做一次
     * `test -x`：这是唯一能拿到「文件到底在不在」的办法。su 自己起不来时
     * 返回 [NcoreImage.Unknown]，而不是猜一个「已安装」。
     */
    private suspend fun probeNcore(): NcoreImage =
        withContext(Dispatchers.IO) {
            val result =
                runCatching {
                    RootShell.exec("test -x $NCORE_PATH && echo present || echo missing", 20L)
                }.getOrNull() ?: return@withContext NcoreImage.Unknown
            classifyNcore(result.output)
        }

    /** 把 `test` 的输出翻译成状态。抽出来是为了能直接跑单测。 */
    internal fun classifyNcore(output: String): NcoreImage =
        when {
            output.contains("present") -> NcoreImage.Present
            output.contains("missing") -> NcoreImage.Missing
            else -> NcoreImage.Unknown
        }
}
