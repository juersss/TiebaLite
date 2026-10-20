package com.huanchengfly.tieba.post.ui.widgets.compose.video

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView

@Composable
fun PlayerSurface(
    modifier: Modifier = Modifier,
    onPlayerViewAvailable: (PlayerView) -> Unit = {}
) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                onPlayerViewAvailable(this)
            }
        },
        // 2026-10-20:此前没有 onRelease。PlayerView 会把 ComponentListener 注册到 player 上，
        // 只有 player 释放才断开——拖进度条的预览视图 / 全屏进出每次都留下一份已销毁的 View 树
        // (含 SurfaceView)被 player 持有，故这里显式摘掉 player。
        onRelease = { it.player = null },
        modifier = modifier
    )
}