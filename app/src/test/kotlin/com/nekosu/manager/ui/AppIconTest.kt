package com.nekosu.manager.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nekosu.manager.ui.mochi.AppIcon
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 回归测试：同一个组合位置换应用时，图标状态必须跟着重建。
 *
 * 之前的 bug 是 `produceState` 复用了上一个应用的 value，新页面先显示旧图标，
 * 并且因为 value 非空直接跳过加载，一直停在旧图标上。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h914dp-xxhdpi")
class AppIconTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `switching package rebuilds the icon state`() {
        var packageName by mutableStateOf("com.nekosu.manager")
        compose.setContent {
            AppIcon(packageName, "NekoSU", Color.Blue, 36.dp)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("app-icon-com.nekosu.manager").assertExists()

        packageName = "com.android.settings"
        compose.waitForIdle()

        compose.onNodeWithTag("app-icon-com.android.settings").assertExists()
        compose.onNodeWithTag("app-icon-com.nekosu.manager").assertDoesNotExist()
    }
}
