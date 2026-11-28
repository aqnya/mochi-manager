package com.nekosu.manager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.nekosu.manager.data.DeviceInfoRepository
import com.nekosu.manager.data.NekoStatusRepository
import com.nekosu.manager.data.installer.NcoreBoot
import com.nekosu.manager.ui.MochiRoot
import com.nekosu.manager.ui.theme.NekoSUTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        // 首帧之前把设备信息取好：首页直接从缓存读，不再有「—」的中间态
        DeviceInfoRepository.preload(applicationContext)
        // 安装状态同样在首帧之前同步探完，首页第一帧就是「已安装 / 未安装」；
        // 后台异步探测会让第一帧落在「检查中」，这里不接受任何中间态。
        // ncore 的引导与 manager 鉴权都由内核在 boot 阶段完成，这里只探真实状态。
        NekoStatusRepository.preload(applicationContext)

        // 每次打开时比对缓存版本与运行版本，变了就以 root 跑一次 APK 自带 ncore
        // 的 install 完成换代。放后台执行，不阻塞首帧；su 授权弹窗也不影响界面。
        lifecycleScope.launch(Dispatchers.IO) {
            NcoreBoot.install(applicationContext)
        }
        setContent {
            NekoSUTheme {
                MochiRoot()
            }
        }
    }
}
