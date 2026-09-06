package com.huanchengfly.tieba.post.arch

import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huanchengfly.tieba.post.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

interface PartialChangeProducer<Intent : UiIntent, PC : PartialChange<State>, State : UiState> {
    fun toPartialChangeFlow(intentFlow: Flow<Intent>): Flow<PC>
}

@Stable
abstract class BaseViewModel<
        Intent : UiIntent,
        PC : PartialChange<State>,
        State : UiState,
        Event : UiEvent
        > :
    ViewModel() {

    // 首载门闩改为 Compose 状态(自省修正):此前是普通 var,LazyLoad 以它为
    // LaunchedEffect key 却不具备快照可观察性——仅因赋值恰好发生在 effect 体内
    // 才未出问题;状态化后 key 语义成立,跨帧变更也能正确触发
    var initialized by mutableStateOf(false)

    // 缓冲 64 + DROP_OLDEST(R9-F1):onEvent 的 listener 可能挂起(snackbar 数秒),
    // 无缓冲的 emit 会反压冻结整条 partial-change 管线(记录写入/dispatchEvent 全部排队);
    // 与 GlobalEventFlow 同款配置,溢出丢最老事件(事件为稀疏 UI 反馈,64 深度实际不触顶)
    private val _internalUiEventFlow: MutableSharedFlow<UiEvent> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val uiEventFlow: Flow<UiEvent> = _internalUiEventFlow

    private val _intentFlow = MutableSharedFlow<Intent>()

    private val initialState: State by lazy { createInitialState() }

    private val partialChangeProducer: PartialChangeProducer<Intent, PC, State> by lazy { createPartialChangeProducer() }

    protected abstract fun createInitialState(): State
    protected abstract fun createPartialChangeProducer(): PartialChangeProducer<Intent, PC, State>

    val uiState = partialChangeProducer.toPartialChangeFlow(_intentFlow)
        .onEach {
            // 仅在 debug 下输出，且只打类名：状态对象可能携带上千条数据，全量 toString 开销极大
            if (BuildConfig.DEBUG) Log.d("ViewModel", "partialChange ${it.javaClass.simpleName}") // DBG-LOG(遗留调试日志,诊断收尾时可一并移除)
            val event = dispatchEvent(it)
            if (event != null) {
                // 收缩与 partialChange 同口径:事件/意图对象可能携凭据(tbs)或用户正文,
                // 全量 toString 是 debug 日志卫生问题与开销(R6-F1)
                if (BuildConfig.DEBUG) Log.d("ViewModel", "event ${event.javaClass.simpleName}") // DBG-LOG(遗留调试日志,诊断收尾时可一并移除)
                _internalUiEventFlow.emit(event)
            }
        }
        .scan(initialState) { oldState, partialChange ->
            partialChange.reduce(oldState)
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialState)

    protected open fun dispatchEvent(partialChange: PC): UiEvent? = null

    fun send(intent: Intent) {
        // 同上:ReplyUiIntent.Send 等意图携带 content/tbs,只打类名(R6-F1)
        if (BuildConfig.DEBUG) Log.d("ViewModel", "send ${intent.javaClass.simpleName}") // DBG-LOG(遗留调试日志,诊断收尾时可一并移除)
        viewModelScope.launch {
            _intentFlow.emit(intent)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BaseViewModel<*, *, *, *>

        if (initialized != other.initialized) return false

        return true
    }
}