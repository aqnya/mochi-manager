package com.nekosu.manager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.nekosu.manager.data.DeviceInfoRepository
import com.nekosu.manager.data.NekoStatusRepository
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
        // 部署完再探一次状态，让首页看到的是部署后的真实结果，而不是部署前的快照。
        lifecycleScope.launch(Dispatchers.IO) {
            if (Ncore.ensureInstalled(applicationContext)) {
                NekoStatusRepository.markDeployed()
                NekoStatusRepository.refresh(applicationContext)
            }
        }
        setContent {
            NekoSUTheme {
                MochiRoot()
            }
        }
    }
}
