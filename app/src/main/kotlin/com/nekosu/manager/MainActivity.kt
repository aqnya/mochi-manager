package com.nekosu.manager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.nekosu.manager.data.DeviceInfoRepository
import com.nekosu.manager.neko.Ncore
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
        // 把 ncore 铺到 /data/adb：模块安装/启停、su、resetprop 都靠它。
        // 无 root 时这里会静默失败，模块页退回空态而不是假数据。
        lifecycleScope.launch(Dispatchers.IO) { Ncore.ensureInstalled(applicationContext) }
        setContent {
            NekoSUTheme {
                MochiRoot()
            }
        }
    }
}
