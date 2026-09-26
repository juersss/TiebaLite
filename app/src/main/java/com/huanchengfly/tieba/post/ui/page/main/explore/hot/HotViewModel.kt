package com.huanchengfly.tieba.post.ui.page.main.explore.hot

import androidx.compose.runtime.Stable
import com.huanchengfly.tieba.post.utils.AgreeOpRunner
import com.huanchengfly.tieba.post.utils.ListAgreeOutcome
import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.api.AgreeParams
import com.huanchengfly.tieba.post.api.interfaces.ITiebaApi
import com.huanchengfly.tieba.post.api.TiebaRateLimitedException
import com.huanchengfly.tieba.post.core.network.model.protos.FrsTabInfo
import com.huanchengfly.tieba.post.core.network.model.protos.RecommendTopicList
import com.huanchengfly.tieba.post.core.network.model.protos.ThreadInfo
import com.huanchengfly.tieba.post.core.network.model.protos.hotThreadList.HotThreadListResponse
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
class HotViewModel @Inject constructor(
    // B3 Repository/注入试点(2026-09-12):TiebaApi 对象本身就是 Hilt Module
    // (@Provides ITiebaApi),ViewModel 直接构造注入即可获得接口,页面不再触达单例。
    // 后续页面迁入同一模式,单测用 fake ITiebaApi 即可在纯 JVM 验证 producer 逻辑
    private val tiebaApi: ITiebaApi,
) :
    BaseViewModel<HotUiIntent, HotPartialChange, HotUiState, HotUiEvent>() {
    override fun createInitialState(): HotUiState = HotUiState()

    override fun createPartialChangeProducer(): PartialChangeProducer<HotUiIntent, HotPartialChange, HotUiState> =
        HotPartialChangeProducer(tiebaApi)

    override fun dispatchEvent(partialChange: HotPartialChange): UiEvent? =
        when (partialChange) {
            is HotPartialChange.Agree.Start -> {
                // 乐观意图进记录表;显示数字/亮灯由 FeedCard.ThreadAgreeBtn 从 records 推导
                AgreeOpRunner.onStartAgree(partialChange.threadId, partialChange.hasAgree)
                null
            }

            is HotPartialChange.Agree.Failure -> {
                // 限流拦截的请求从未 setPending,绝不回退(判定与回滚收口在 runner)
                AgreeOpRunner.onFailureAgree(partialChange.threadId, partialChange.error)
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
        AgreeOpRunner.rebaseLoaded(
            AgreeParams.OBJ_THREAD,
            threadList.map { it.threadId }
        )
    }

    private class HotPartialChangeProducer(
        private val tiebaApi: ITiebaApi,
    ) : PartialChangeProducer<HotUiIntent, HotPartialChange, HotUiState> {
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
            tiebaApi.hotThreadListFlow("all")
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
            tiebaApi.hotThreadListFlow(tabCode)
                .map<HotThreadListResponse, HotPartialChange.RefreshThreadList> {
                    HotPartialChange.RefreshThreadList.Success(
                        tabCode,
                        it.data_?.threadInfo ?: emptyList()
                    )
                }
                .onStart { emit(HotPartialChange.RefreshThreadList.Start(tabCode)) }
                .catch { emit(HotPartialChange.RefreshThreadList.Failure(tabCode, it)) }

        private fun HotUiIntent.Agree.producePartialChange(): Flow<HotPartialChange.Agree> =
            // 赞踩链路统一收口在 AgreeOpRunner,本页只做中性结果 → PartialChange 的类型映射
            AgreeOpRunner.threadAgree(threadId, postId, hasAgree, forumId)
                .map<ListAgreeOutcome, HotPartialChange.Agree> { outcome ->
                    when (outcome) {
                        is ListAgreeOutcome.Started ->
                            HotPartialChange.Agree.Start(threadId, outcome.newHasAgree)

                        is ListAgreeOutcome.Accepted ->
                            HotPartialChange.Agree.Success(threadId, hasAgree xor 1)

                        is ListAgreeOutcome.Rejected ->
                            HotPartialChange.Agree.Failure(threadId, hasAgree, outcome.error)
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
        val hasAgree: Int,
        // E1:opAgree 官方必带参数,从 ThreadInfo 透传;null 时不发送
        val forumId: Long? = null,
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