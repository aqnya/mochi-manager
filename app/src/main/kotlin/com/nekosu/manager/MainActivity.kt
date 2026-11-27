package com.nekosu.manager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.nekosu.manager.data.DeviceInfoRepository
import com.nekosu.manager.data.NekoStatusRepository
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
        // ncore 的引导与 manager 鉴权都由内核在 boot 阶段完成，这里只重新探一次真实状态，
        // 让首页读到的是内核通道现在的样子（可能刚装完模块、也可能还没装）。
        lifecycleScope.launch(Dispatchers.IO) {
            NekoStatusRepository.refresh(applicationContext)
        }
        setContent {
            NekoSUTheme {
                MochiRoot()
            }
        }
    }
}
