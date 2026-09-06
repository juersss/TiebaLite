package com.huanchengfly.tieba.post.arch

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.huanchengfly.tieba.post.BuildConfig
import com.huanchengfly.tieba.post.utils.PickMediasRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

sealed interface GlobalEvent : UiEvent {
    data object AccountSwitched : GlobalEvent

    data object ScrollToTop : GlobalEvent

    data class Refresh(val key: String) : GlobalEvent

    data class StartSelectImages(
        val id: String,
        val maxCount: Int,
        val mediaType: PickMediasRequest.MediaType
    ) : GlobalEvent

    data class SelectedImages(
        val id: String,
        val images: List<Uri>,
    ) : GlobalEvent

    data class ReplySuccess(
        val threadId: Long,
        val newPostId: Long,
        val postId: Long? = null,
        val subPostId: Long? = null,
        val newSubPostId: Long? = null,
    ) : GlobalEvent

    data class AddThreadSuccess(
        val newThreadId: Long,
        val newPostId: Long,
        val msg: String?,
    ) : GlobalEvent

    data class StartActivityForResult(
        val requesterId: String,
        val intent: Intent,
    ) : GlobalEvent

    data class ActivityResult(
        val requesterId: String,
        val resultCode: Int,
        val intent: Intent?,
    ) : GlobalEvent
}

private val globalEventSharedFlow: MutableSharedFlow<UiEvent> by lazy(mode = LazyThreadSafetyMode.SYNCHRONIZED) {
    MutableSharedFlow(0, 2, BufferOverflow.DROP_OLDEST)
}

val GlobalEventFlow = globalEventSharedFlow.asSharedFlow()

fun CoroutineScope.emitGlobalEvent(event: UiEvent) {
    launch {
        globalEventSharedFlow.emit(event)
    }
}

suspend fun emitGlobalEventSuspend(event: UiEvent) {
    globalEventSharedFlow.emit(event)
}

inline fun <reified Event : UiEvent> CoroutineScope.onGlobalEvent(
    noinline filter: (Event) -> Boolean = { true },
    noinline listener: suspend (Event) -> Unit,
): Job {
    return launch {
        GlobalEventFlow
            .filterIsInstance<Event>()
            .filter {
                filter(it)
            }
            .cancellable()
            .collect {
                // 全局事件携带用户相册 URI(SelectedImages)等,全量 toString 只在 debug、只打类名(R6-F2)
                if (BuildConfig.DEBUG) Log.d("GlobalEvent", "onGlobalEvent: ${it.javaClass.simpleName}") // DBG-LOG(遗留调试日志,诊断收尾时可一并移除)
                listener(it)
            }
    }
}

@Composable
inline fun <reified Event : UiEvent> onGlobalEvent(
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
    noinline filter: (Event) -> Boolean = { true },
    noinline listener: suspend (Event) -> Unit,
) {
    // filter/listener 的 lambda 身份随捕获值(如页面的 data 状态)变化会让 DisposableEffect
    // 反复 dispose/重注册,replay=0 的全局事件在间隙被 DROP_OLDEST 吞掉(R7-F3);
    // 注册只随 scope 生命周期建立一次,包装闭包捕获 State 委托、调用时取最新闭包
    val currentFilter by rememberUpdatedState(filter)
    val currentListener by rememberUpdatedState(listener)
    val registeredFilter: (Event) -> Boolean = { currentFilter(it) }
    val registeredListener: suspend (Event) -> Unit = { currentListener(it) }
    DisposableEffect(coroutineScope) {
        val job = coroutineScope.onGlobalEvent(
            filter = registeredFilter,
            listener = registeredListener
        )
        onDispose {
            job.cancel()
        }
    }
}