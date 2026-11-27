package com.nekosu.manager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.nekosu.manager.data.DeviceInfoRepository
import com.nekosu.manager.ui.MochiRoot
import com.nekosu.manager.ui.theme.NekoSUTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        // 首帧之前把设备信息取好：首页直接从缓存读，不再有「—」的中间态
        DeviceInfoRepository.preload(applicationContext)
        setContent {
            NekoSUTheme {
                MochiRoot()
            }
        }
    }
}
