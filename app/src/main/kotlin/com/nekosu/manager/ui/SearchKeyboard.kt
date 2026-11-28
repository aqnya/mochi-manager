package com.nekosu.manager.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * 搜索键按下后收起输入法。
 *
 * Mochi 的搜索框与输入框都不会主动收键盘：SearchBar 的 `onSearch` 默认是空实现，
 * TextField 的 `keyboardActions` 默认是 `Default`。结果是在授权页搜完应用后
 * 输入法一直盖着列表，点不到下面的开关。
 *
 * 这里把「清焦点 + 隐藏软键盘」合并成一个回调，供两种搜索框共用；
 * `clearFocus()` 让搜索框退出聚焦状态，`hide()` 则兼容部分不因失焦自动收键盘的 ROM。
 */
@Composable
fun rememberHideKeyboardOnSearch(): () -> Unit {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    return remember(focus, keyboard) {
        {
            focus.clearFocus()
            keyboard?.hide()
        }
    }
}
