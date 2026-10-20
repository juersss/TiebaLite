package com.huanchengfly.tieba.post.ui.widgets.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.huanchengfly.tieba.post.ui.widgets.VoicePlayerView

@Composable
fun VoicePlayer(
    url: String,
    duration: Int,
    modifier: Modifier = Modifier
) {
    val viewRef: MutableState<VoicePlayerView?> = remember { mutableStateOf(null) }
    AndroidView(
        modifier = modifier,
        factory = {
            VoicePlayerView(it).also { view -> viewRef.value = view }
        },
        update = {
            it.url = url
            it.duration = duration
        }
    )
    // 2026-10-20：onDispose 曾调用 `VoicePlayerView.Manager.release()`——那是**进程级**
    // 单例的回收口，而本 effect 会在任意一条语音"滚出组合"时触发：一行滚走就把所有语音
    // 视图共用的 ExoPlayer 销毁，其余语音要么点了没反应要么 IllegalStateException。
    // 现在只让**本视图**脱钩（View 侧 release() 已改为非破坏性），全局回收仍归 Activity 销毁。
    DisposableEffect(Unit) {
        onDispose {
            viewRef.value?.release()
            viewRef.value = null
        }
    }
}
