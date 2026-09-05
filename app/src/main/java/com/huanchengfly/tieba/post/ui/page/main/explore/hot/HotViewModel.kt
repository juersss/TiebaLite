package com.huanchengfly.tieba.post.ui.page.main.explore.hot

import androidx.compose.runtime.Stable
import com.huanchengfly.tieba.post.utils.OpRecordStore
import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.api.AgreeParams
import com.huanchengfly.tieba.post.api.AgreeRateLimiter
import com.huanchengfly.tieba.post.api.TiebaApi
import com.huanchengfly.tieba.post.api.TiebaRateLimitedException
import com.huanchengfly.tieba.post.api.models.protos.MyAgreeOp
import com.huanchengfly.tieba.post.api.models.protos.OpAgreeResult
import com.huanchengfly.tieba.post.api.models.protos.serverOpFromErrorCode
import com.huanchengfly.tieba.post.api.models.protos.serverOpFromErrorMessage
import com.huanchengfly.tieba.post.api.retrofit.exception.getErrorMessage
import com.huanchengfly.tieba.post.api.models.protos.toOpAgreeResult
import com.huanchengfly.tieba.post.api.models.AgreeBean
import com.huanchengfly.tieba.post.api.models.CommonResponse
import com.huanchengfly.tieba.post.api.models.protos.FrsTabInfo
import com.huanchengfly.tieba.post.api.models.protos.RecommendTopicList
import com.huanchengfly.tieba.post.api.models.protos.ThreadInfo
import com.huanchengfly.tieba.post.api.models.protos.hotThreadList.HotThreadListResponse
import com.huanchengfly.tieba.post.api.retrofit.exception.TiebaApiException
import com.huanchengfly.tieba.post.arch.BaseViewModel
import com.huanchengfly.tieba.post.arch.CommonUiEvent
import com.huanchengfly.tieba.post.arch.ImmutableHolder
import com.huanchengfly.tieba.post.arch.PartialChange
import com.huanchengfly.tieba.post.arch.PartialChangeProducer
import com.huanchengfly.tieba.post.arch.UiEvent
import com.huanchengfly.tieba.post.arch.UiIntent
import com.huanchengfly.tieba.post.arch.UiState
import com.huanchengfly.tieba.post.arch.wrapImmutable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject

@Stable
@HiltViewModel
class HotViewModel @Inject constructor() :
    BaseViewModel<HotUiIntent, HotPartialChange, HotUiState, HotUiEvent>() {
    override fun createInitialState(): HotUiState = HotUiState()

    override fun createPartialChangeProducer(): PartialChangeProducer<HotUiIntent, HotPartialChange, HotUiState> =
        HotPartialChangeProducer

    override fun dispatchEvent(partialChange: HotPartialChange): UiEvent? =
        when (partialChange) {
            is HotPartialChange.Agree.Start -> {
                // 乐观意图进记录表;显示数字/亮灯由 FeedCard.ThreadAgreeBtn 从 records 推导
                OpRecordStore.setPending(
                    App.INSTANCE,
                    AgreeParams.OBJ_THREAD,
                    partialChange.threadId,
                    if (partialChange.hasAgree == 1) MyAgreeOp.AGREE else MyAgreeOp.NONE
                )
                null
            }

            is HotPartialChange.Agree.Failure -> {
                // 限流异常不是 TiebaException——按类型判定;被拦截的请求从未 setPending,
                // 绝不 revertPending(否则凭空写出 my=server=NONE 记录,永久屏蔽服务端回显)
                if (partialChange.error !is TiebaRateLimitedException) {
                    OpRecordStore.revertPending(
                        App.INSTANCE,
                        AgreeParams.OBJ_THREAD,
                        partialChange.threadId
                    )
                }
                // 列表页点赞失败历来静默(与帖子页 Agree 口径一致),仅限流时提示
                if (partialChange.error is TiebaRateLimitedException)
                    CommonUiEvent.Toast(partialChange.error.message.orEmpty())
                else null
            }

            // 列表重载:本次返回的 agreeNum 基准已包含已确认操作,对齐标记跟进意图
            is HotPartialChange.Load.Success -> {
                rebaseLoaded(partialChange.threadList); null
            }

            is HotPartialChange.RefreshThreadList.Success -> {
                rebaseLoaded(partialChange.threadList); null
            }

            else -> null
        }

    private fun rebaseLoaded(threadList: List<ThreadInfo>) {
        val keys = HashSet<String>(threadList.size)
        threadList.forEach {
            keys.add(OpRecordStore.key(AgreeParams.OBJ_THREAD, it.threadId))
        }
        OpRecordStore.rebase(App.INSTANCE, keys)
    }

    private object HotPartialChangeProducer :
        PartialChangeProducer<HotUiIntent, HotPartialChange, HotUiState> {
        @OptIn(ExperimentalCoroutinesApi::class)
        override fun toPartialChangeFlow(intentFlow: Flow<HotUiIntent>): Flow<HotPartialChange> =
            merge(
                intentFlow.filterIsInstance<HotUiIntent.Load>()
                    .flatMapConcat { produceLoadPartialChange() },
                intentFlow.filterIsInstance<HotUiIntent.RefreshThreadList>()
                    .flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<HotUiIntent.Agree>()
                    .flatMapConcat { it.producePartialChange() },
            )

        private fun produceLoadPartialChange(): Flow<HotPartialChange.Load> =
            TiebaApi.getInstance().hotThreadListFlow("all")
                .map<HotThreadListResponse, HotPartialChange.Load> {
                    HotPartialChange.Load.Success(
                        it.data_?.topicList ?: emptyList(),
                        it.data_?.hotThreadTabInfo ?: emptyList(),
                        it.data_?.threadInfo ?: emptyList()
                    )
                }
                .onStart { emit(HotPartialChange.Load.Start) }
                .catch { emit(HotPartialChange.Load.Failure(it)) }

        private fun HotUiIntent.RefreshThreadList.producePartialChange(): Flow<HotPartialChange.RefreshThreadList> =
            TiebaApi.getInstance().hotThreadListFlow(tabCode)
                .map<HotThreadListResponse, HotPartialChange.RefreshThreadList> {
                    HotPartialChange.RefreshThreadList.Success(
                        tabCode,
                        it.data_?.threadInfo ?: emptyList()
                    )
                }
                .onStart { emit(HotPartialChange.RefreshThreadList.Start(tabCode)) }
                .catch { emit(HotPartialChange.RefreshThreadList.Failure(tabCode, it)) }

        private fun HotUiIntent.Agree.producePartialChange(): Flow<HotPartialChange.Agree> {
            // 限流预检在任何状态变更之前(与帖子页同构):被拦截不产生 Start、零记录变更
            val acquired = AgreeRateLimiter.tryAcquire(
                AgreeRateLimiter.keyFor(AgreeParams.OBJ_THREAD, threadId)
            )
            // 配对撤销判定取自 Start 之前的记录(此前在帖子页点过踩):服务端赞踩相互独立,
            // 列表点赞若不撤销已有的踩,踩会变孤儿(服务端留着、UI 永久不可见)。撤销仅在
            // 主操作被服务端接受/权威对齐时执行,Business 拒绝不撤销(防把拒绝放大成反向漂移)
            // 意图判定直读 prefs 真值而非内存镜像:异步 init 窗口内内存表未加载,
            // 读内存表会把 prefs 已有的踩判成无→配对撤销失效→服务端孤儿踩(R8-NEW1/链 C)
            val undoDisagree =
                OpRecordStore.currentMy(App.INSTANCE, AgreeParams.OBJ_THREAD, threadId) ==
                    MyAgreeOp.DISAGREE
            suspend fun undoDisagreeIfAccepted() {
                if (!undoDisagree) return
                runCatching {
                    if (!AgreeRateLimiter.tryAcquire(
                            AgreeRateLimiter.keyFor(AgreeParams.OBJ_THREAD, threadId),
                            checkPerObject = false
                        )
                    ) return@runCatching
                    TiebaApi.getInstance()
                        .opDisagreeFlow(
                            threadId.toString(),
                            postId.toString(),
                            objType = AgreeParams.OBJ_THREAD,
                            opType = AgreeParams.OP_UNDO
                        )
                        .collect { }
                }
            }
            return flow {
                if (!acquired) throw TiebaRateLimitedException()
                emitAll(
                    TiebaApi.getInstance().opAgreeFlow(
                        threadId.toString(), postId.toString(), hasAgree, objType = 3
                    )
                )
            }.map<AgreeBean, HotPartialChange.Agree> { bean ->
                val agree = (hasAgree xor 1) == 1
                // HTTP 200 不等于业务成功:先按 errorCode 做三态判定,再决定要不要写记录
                when (val result = bean.toOpAgreeResult(threadId, agree)) {
                    // Ok 不写记录——Start 的 setPending 已表达意图,此刻 confirm 会抵消乐观偏移
                    is OpAgreeResult.Ok -> {
                        undoDisagreeIfAccepted()
                        HotPartialChange.Agree.Success(threadId, hasAgree xor 1)
                    }

                    is OpAgreeResult.Authoritative -> {
                        // 服务端权威陈述("你已赞过"等):无条件采纳,my 与 server 一并对齐过去
                        OpRecordStore.confirm(
                            App.INSTANCE,
                            AgreeParams.OBJ_THREAD,
                            threadId,
                            serverOpFromErrorCode(result.code)
                        )
                        undoDisagreeIfAccepted()
                        HotPartialChange.Agree.Success(threadId, hasAgree xor 1)
                    }

                    is OpAgreeResult.Business -> {
                        // 普通业务拒绝:不 confirm(否则 my=server 落盘永不自愈),
                        // 回滚统一交给 dispatchEvent(与网络失败同路)
                        HotPartialChange.Agree.Failure(
                            threadId,
                            hasAgree,
                            TiebaApiException(
                                CommonResponse(result.code.toIntOrNull() ?: CommonResponse.ERROR_CODE_UNKNOWN, result.msg)
                            )
                        )
                    }
                }
            }
                .onStart { if (acquired) emit(HotPartialChange.Agree.Start(threadId, hasAgree xor 1)) }
                .catch {
                    // 权威码异常路径采纳,收尾半截修复(R8 裁决 12,同 ForumThreadList 注释)
                    val authoritative = serverOpFromErrorMessage(it.getErrorMessage())
                    if (authoritative == null) {
                        emit(HotPartialChange.Agree.Failure(threadId, hasAgree, it))
                    } else {
                        OpRecordStore.confirm(App.INSTANCE, AgreeParams.OBJ_THREAD, threadId, authoritative)
                        undoDisagreeIfAccepted()
                        emit(HotPartialChange.Agree.Success(threadId, hasAgree xor 1))
                    }
                }
        }
    }
}

sealed interface HotUiIntent : UiIntent {
    object Load : HotUiIntent

    data class RefreshThreadList(val tabCode: String) : HotUiIntent

    data class Agree(
        val threadId: Long,
        val postId: Long,
        val hasAgree: Int
    ) : HotUiIntent
}

sealed interface HotPartialChange : PartialChange<HotUiState> {
    sealed class Load : HotPartialChange {
        override fun reduce(oldState: HotUiState): HotUiState =
            when (this) {
                Start -> oldState.copy(isRefreshing = true)
                is Success -> oldState.copy(
                    isRefreshing = false,
                    currentTabCode = "all",
                    topicList = topicList.wrapImmutable(),
                    tabList = tabList.wrapImmutable(),
                    threadList = threadList.wrapImmutable()
                )

                is Failure -> oldState.copy(isRefreshing = false)
            }

        object Start : Load()

        data class Success(
            val topicList: List<RecommendTopicList>,
            val tabList: List<FrsTabInfo>,
            val threadList: List<ThreadInfo>,
        ) : Load()

        data class Failure(
            val error: Throwable
        ) : Load()
    }

    sealed class RefreshThreadList : HotPartialChange {
        override fun reduce(oldState: HotUiState): HotUiState =
            when (this) {
                is Start -> oldState.copy(isLoadingThreadList = true, currentTabCode = tabCode)
                is Success -> oldState.copy(
                    isLoadingThreadList = false,
                    currentTabCode = tabCode,
                    threadList = threadList.wrapImmutable()
                )

                is Failure -> oldState.copy(isLoadingThreadList = false)
            }

        data class Start(val tabCode: String) : RefreshThreadList()

        data class Success(
            val tabCode: String,
            val threadList: List<ThreadInfo>
        ) : RefreshThreadList()

        data class Failure(
            val tabCode: String,
            val error: Throwable
        ) : RefreshThreadList()
    }

    sealed class Agree private constructor() : HotPartialChange {
        // 差分模型:显示由 FeedCard.ThreadAgreeBtn 从 OpRecordStore.records 推导,
        // reducer 不再改写 proto 计数;记录更新全部集中在 dispatchEvent
        override fun reduce(oldState: HotUiState): HotUiState =
            when (this) {
                is Start -> oldState
                is Success -> oldState
                is Failure -> oldState
            }

        data class Start(
            val threadId: Long,
            val hasAgree: Int
        ) : Agree()

        data class Success(
            val threadId: Long,
            val hasAgree: Int
        ) : Agree()

        data class Failure(
            val threadId: Long,
            val hasAgree: Int,
            val error: Throwable
        ) : Agree()
    }
}

data class HotUiState(
    val isRefreshing: Boolean = true,
    val currentTabCode: String = "all",
    val isLoadingThreadList: Boolean = false,
    val topicList: ImmutableList<ImmutableHolder<RecommendTopicList>> = persistentListOf(),
    val tabList: ImmutableList<ImmutableHolder<FrsTabInfo>> = persistentListOf(),
    val threadList: ImmutableList<ImmutableHolder<ThreadInfo>> = persistentListOf(),
) : UiState

sealed interface HotUiEvent : UiEvent