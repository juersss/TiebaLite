package com.huanchengfly.tieba.post.ui.widgets.compose.video

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.STATE_ENDED
import androidx.media3.common.Player.STATE_IDLE
import androidx.media3.common.Player.STATE_READY
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.core.common.upgradeImageUrlToHttps
import com.huanchengfly.tieba.post.ui.widgets.compose.video.util.FlowDebouncer
import com.huanchengfly.tieba.post.ui.widgets.compose.video.util.set
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

interface OnFullScreenModeChangedListener {
    fun onFullScreenModeChanged(isFullScreen: Boolean)
}

internal class DefaultVideoPlayerController(
    private val context: Context,
    private val initialState: VideoPlayerState,
    private val coroutineScope: CoroutineScope,
    private val fullScreenModeChangedListener: OnFullScreenModeChangedListener? = null
) : VideoPlayerController {
    private val released = AtomicBoolean(false)

    private val _state = MutableStateFlow(initialState)
    override val state: StateFlow<VideoPlayerState>
        get() = _state.asStateFlow()

    /**
     * Some properties in initial state are not applicable until player is ready.
     * These are kept in this container. Once the player is ready for the first time,
     * they are applied and removed.
     */
    private var initialStateRunner: (() -> Unit)? = {
        exoPlayer.seekTo(initialState.currentPosition)
    }

    fun <T> currentState(filter: (VideoPlayerState) -> T): T {
        return filter(_state.value)
    }

    @Composable
    fun collect(): State<VideoPlayerState> {
        return _state.collectAsState()
    }

    @SuppressLint("StateFlowValueCalledInComposition")
    @Composable
    fun <T> collect(filter: VideoPlayerState.() -> T): State<T> {
        return remember(filter) {
            _state.map { it.filter() }
        }.collectAsState(
            initial = _state.value.filter()
        )
    }

    var videoPlayerBackgroundColor: Int = DefaultVideoPlayerBackgroundColor.value.toInt()
        set(value) {
            field = value
            playerView?.setBackgroundColor(value)
        }

    private lateinit var source: VideoPlayerSource
    private var playerView: PlayerView? = null

    private var updateDurationAndPositionJob: Job? = null
    private var autoHideControllerJob: Job? = null

    private val playerListener = object : Player.Listener {
        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (PlaybackState.of(playbackState) == PlaybackState.READY) {
                initialStateRunner = initialStateRunner?.let {
                    it.invoke()
                    null
                }

                updateDurationAndPositionJob?.cancel()
                updateDurationAndPositionJob = coroutineScope.launch {
                    while (this.isActive) {
                        updateDurationAndPosition()
                        delay(250)
                    }
                }
            }

            _state.set {
                copy(
                    playbackState = PlaybackState.of(playbackState),
                    startedPlay = playbackState != STATE_IDLE
                )
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            _state.set {
                copy(isPlaying = playWhenReady)
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            _state.set {
                copy(videoSize = videoSize.width.toFloat() to videoSize.height.toFloat())
            }
        }

        /**
         * 以前没有实现这个方法:任何加载/解码失败都只表现为"停在黑屏"——既没有提示,也没法重试
         * (`play()` 会立刻把 startedPlay 置 true,UI 从封面切到播放器表面,失败后就一直黑着)。
         * 现在:① 打日志(便于 adb logcat 定位);② 回到封面并让下次点播放能重新 prepare;
         * ③ 把失败原因弹出来。
         */
        override fun onPlayerError(error: PlaybackException) {
            Log.e(
                "VideoPlayerController",
                "playback failed: ${error.errorCodeName} | ${error.message} | cause=${error.cause}",
                error
            )
            // prepare 失败后 exoPlayer 停在 IDLE:复位这个闸门,下次点播放会重新 prepare 一次
            waitPlayerViewToPrepare.set(true)
            _state.set {
                copy(startedPlay = false, isPlaying = false, controlsVisible = true)
            }
            Toast.makeText(
                context,
                context.getString(
                    R.string.toast_video_play_failed,
                    error.cause?.message ?: error.errorCodeName
                ),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Internal exoPlayer instance
     */
    private var _exoPlayer: ExoPlayer? = null
    private val exoPlayer: ExoPlayer
        get() {
            if (_exoPlayer == null) {
                _exoPlayer = createExoPlayer()
            }
            return _exoPlayer!!
        }

    private fun createExoPlayer() = ExoPlayer.Builder(context)
        .build()
        .apply {
            playWhenReady = initialState.isPlaying
            addListener(playerListener)
        }

    /**
     * Not so efficient way of showing preview in video slider.
     */
    private var _previewExoPlayer: ExoPlayer? = null
    private val previewExoPlayer: ExoPlayer
        get() {
            if (_previewExoPlayer == null) {
                _previewExoPlayer = createPreviewExoPlayer()
            }
            return _previewExoPlayer!!
        }

    private fun createPreviewExoPlayer() = ExoPlayer.Builder(context)
        .build()
        .apply {
            playWhenReady = false
        }

    private val previewSeekDebouncer = FlowDebouncer<Long>(200L)

    init {
        exoPlayer.playWhenReady = initialState.isPlaying

        coroutineScope.launch {
            previewSeekDebouncer.collect { position ->
                previewExoPlayer.seekTo(position)
            }
        }
    }

    fun initialize() {
        Log.i("VideoPlayerController", "$this initialize")
        val currentState = _state.value
        exoPlayer.playWhenReady = currentState.isPlaying
        initialStateRunner = {
            exoPlayer.seekTo(currentState.currentPosition)
        }
        if (this::source.isInitialized) {
            setSource(source)
        }
        if (playerView != null) {
            playerViewAvailable(playerView!!)
        }
        released.set(false)
    }

    /**
     * A flag to indicate whether source is already set and waiting for
     * playerView to become available.
     */
    private val waitPlayerViewToPrepare = AtomicBoolean(false)

    override fun play() {
        _state.set { copy(startedPlay = true) }
        if (exoPlayer.playbackState == STATE_ENDED) {
            exoPlayer.seekTo(0)
        }
        exoPlayer.playWhenReady = true
         coroutineScope.launch {
            delay(200)
            hideControls()
        }
    }

    override fun pause() {
        exoPlayer.playWhenReady = false
        showControls(false)
    }

    override fun togglePlaying() {
        if (exoPlayer.isPlaying) pause()
        else play()
    }

    override fun quickSeekForward() {
        if (_state.value.quickSeekAction.direction != QuickSeekDirection.None) {
            // Currently animating
            return
        }
        // duration 未就绪时是 C.TIME_UNSET(-1):coerceAtMost(-1) 会 seekTo(-1) 崩
        val maxPosition = exoPlayer.duration.takeIf { it > 0 } ?: 0
        val target = (exoPlayer.currentPosition + 10_000).coerceAtMost(maxPosition)
        exoPlayer.seekTo(target)
        updateDurationAndPosition()
        _state.set { copy(quickSeekAction = QuickSeekAction.forward()) }
    }

    override fun quickSeekRewind() {
        if (_state.value.quickSeekAction.direction != QuickSeekDirection.None) {
            // Currently animating
            return
        }
        val target = (exoPlayer.currentPosition - 10_000).coerceAtLeast(0)
        exoPlayer.seekTo(target)
        updateDurationAndPosition()
        _state.set { copy(quickSeekAction = QuickSeekAction.rewind()) }
    }

    override fun seekTo(position: Long) {
        exoPlayer.seekTo(position)
        updateDurationAndPosition()
    }

    override fun setSource(source: VideoPlayerSource) {
        this.source = source
        if (playerView == null) {
            waitPlayerViewToPrepare.set(true)
        } else {
            prepare()
        }
    }

    fun enableGestures(isEnabled: Boolean) {
        _state.set { copy(gesturesEnabled = isEnabled) }
    }

    fun enableControls(enabled: Boolean) {
        _state.set { copy(controlsEnabled = enabled) }
    }

    fun showControls(autoHide: Boolean = true) {
        _state.set { copy(controlsVisible = true) }
        if (autoHide) {
            autoHideControls()
        } else {
            cancelAutoHideControls()
        }
    }

    private fun cancelAutoHideControls() {
        Log.i("VideoPlayerController", "cancelAutoHideControls")
        autoHideControllerJob?.cancel()
    }

    private fun autoHideControls() {
        cancelAutoHideControls()
        Log.i("VideoPlayerController", "autoHideControls")
        autoHideControllerJob = coroutineScope.launch {
            delay(3000)
            hideControls()
        }
    }

    fun hideControls() {
        _state.set { copy(controlsVisible = false) }
    }

    fun setDraggingProgress(draggingProgress: DraggingProgress?) {
        _state.set { copy(draggingProgress = draggingProgress) }
    }

    fun setQuickSeekAction(quickSeekAction: QuickSeekAction) {
        _state.set { copy(quickSeekAction = quickSeekAction) }
    }

    private fun updateDurationAndPosition() {
        if (exoPlayer.playbackState == STATE_READY || exoPlayer.playbackState == STATE_ENDED) {
            _state.set {
                copy(
                    duration = exoPlayer.duration.coerceAtLeast(0),
                    currentPosition = exoPlayer.currentPosition.coerceAtLeast(0),
                    secondaryProgress = exoPlayer.bufferedPosition.coerceAtLeast(0),
                    isPlaying = exoPlayer.isPlaying
                )
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun prepare() {
        fun createVideoSource(): MediaSource {
            val dataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(context)

            return when (val source = source) {
                is VideoPlayerSource.Raw -> {
                    ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(
                            MediaItem.fromUri(
                                RawResourceDataSource.buildRawResourceUri(
                                    source.resId
                                )
                            )
                        )
                }

                is VideoPlayerSource.Network -> {
                    // 全局禁明文(network_security_config 的 base-config=false + manifest 兜底):
                    // 播放地址若被接口下发成 http,请求在建立连接前就被网络策略拒掉——表现正是
                    // "点开视频停在黑屏"(与 2026-09-14 图页整屏全黑同因)。这里与图片同一口径
                    // 升级 https,不放宽明文白名单;已是 https 或命中白名单域名时原样返回。
                    val playableUrl = upgradeImageUrlToHttps(source.url)
                    // 只记 scheme/host(不记完整 URL 与签名参数):禁明文策略下 http 必失败,
                    // 这行日志让"黑屏到底是明文被拒还是 CDN 403"一眼可辨。
                    Log.i(
                        "VideoPlayerController",
                        "network source: ${Uri.parse(source.url).scheme}://${Uri.parse(source.url).host}" +
                            " -> ${Uri.parse(playableUrl).scheme}://${Uri.parse(playableUrl).host}"
                    )
                    ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(playableUrl))
                }
            }
        }

        exoPlayer.setMediaSource(createVideoSource())
        previewExoPlayer.setMediaSource(createVideoSource())

        exoPlayer.prepare()
        previewExoPlayer.prepare()
    }

    fun playerViewAvailable(playerView: PlayerView) {
        this.playerView = playerView
        playerView.player = exoPlayer
        playerView.setBackgroundColor(videoPlayerBackgroundColor)

        if (waitPlayerViewToPrepare.compareAndSet(true, false)) {
            prepare()
        }
    }

    fun previewPlayerViewAvailable(playerView: PlayerView) {
        playerView.player = previewExoPlayer
    }

    fun previewSeekTo(position: Long) {
        // position is very accurate. Thumbnail doesn't have to be.
        // Roll to the nearest "even" integer.
        val seconds = position.toInt() / 1000
        val nearestEven = (seconds - seconds.rem(2)).toLong()
        coroutineScope.launch {
            previewSeekDebouncer.put(nearestEven * 1000)
        }
    }

    override fun reset() {
        exoPlayer.stop()
        previewExoPlayer.stop()
    }

    override fun release() {
        Log.i("VideoPlayerController", "$this is release. is released $released")
        if (released.compareAndSet(false, true)) {
            // 先取消周期性更新循环:release 置空 _exoPlayer 后,250ms 循环若还在跑,
            // 会经 exoPlayer 惰性 getter 复活出一个无人 prepare/无人 release 的孤儿播放器
            updateDurationAndPositionJob?.cancel()
            updateDurationAndPositionJob = null
            exoPlayer.release()
            previewExoPlayer.release()
            _exoPlayer = null
            _previewExoPlayer = null
        }
    }

    override fun supportFullScreen(): Boolean {
        return fullScreenModeChangedListener != null
    }

    override fun toggleFullScreen() {
        require(fullScreenModeChangedListener != null) { "Full screen mode is not supported" }
        fullScreenModeChangedListener.onFullScreenModeChanged(!currentState { it.isFullScreen })
        _state.set { copy(isFullScreen = !isFullScreen) }
    }
}

val DefaultVideoPlayerBackgroundColor = Color.Black