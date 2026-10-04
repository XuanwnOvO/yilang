package com.yilang.ui.common

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 全屏覆盖层防点击穿透：吞掉落在本层、未被子组件消费的点击，
 * 避免点到下层（被覆盖的）编辑器控件上。仅拦点击，不影响子组件滚动/输入。
 */
fun Modifier.consumeTaps(): Modifier =
    pointerInput(Unit) { detectTapGestures { } }
