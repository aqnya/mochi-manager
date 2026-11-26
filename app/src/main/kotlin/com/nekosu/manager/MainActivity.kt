package com.nekosu.manager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.nekosu.manager.ui.MochiRoot
import com.nekosu.manager.ui.theme.NekoSUTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            NekoSUTheme {
                MochiRoot()
            }
        }
    }
}
