package com.huanchengfly.tieba.post.ui.page.main.explore.concern

import androidx.compose.runtime.Stable
import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.api.AgreeParams
import com.huanchengfly.tieba.post.api.interfaces.ITiebaApi
import com.huanchengfly.tieba.post.api.TiebaRateLimitedException
import com.huanchengfly.tieba.post.core.network.model.protos.userLike.ConcernData
import com.huanchengfly.tieba.post.core.network.model.protos.userLike.UserLikeResponse
import com.huanchengfly.tieba.post.api.retrofit.exception.getErrorMessage
import com.huanchengfly.tieba.post.utils.AgreeOpRunner
import com.huanchengfly.tieba.post.utils.ListAgreeOutcome
import com.huanchengfly.tieba.post.arch.BaseViewModel
import com.huanchengfly.tieba.post.arch.CommonUiEvent
import com.huanchengfly.tieba.post.arch.PartialChange
import com.huanchengfly.tieba.post.arch.PartialChangeProducer
import com.huanchengfly.tieba.post.arch.UiEvent
import com.huanchengfly.tieba.post.arch.UiIntent
import com.huanchengfly.tieba.post.arch.UiState
import com.huanchengfly.tieba.post.core.data.appPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject

@Stable
@HiltViewModel
class ConcernViewModel @Inject constructor(
    private val tiebaApi: ITiebaApi,
) :
    BaseViewModel<ConcernUiIntent, ConcernPartialChange, ConcernUiState, ConcernUiEvent>() {
    override fun createInitialState(): ConcernUiState = ConcernUiState()

    override fun createPartialChangeProducer(): PartialChangeProducer<ConcernUiIntent, ConcernPartialChange, ConcernUiState> =
        ExplorePartialChangeProducer(tiebaApi)

    override fun dispatchEvent(partialChange: ConcernPartialChange): UiEvent? =
        when (partialChange) {
            is ConcernPartialChange.Refresh.Failure -> CommonUiEvent.Toast(partialChange.error.getErrorMessage())
            is ConcernPartialChange.LoadMore.Failure -> CommonUiEvent.Toast(partialChange.error.getErrorMessage())

            is ConcernPartialChange.Agree.Start -> {
                // 乐观意图进记录表;显示数字/亮灯由 FeedCard.ThreadAgreeBtn 从 records 推导
                AgreeOpRunner.onStartAgree(partialChange.threadId, partialChange.hasAgree)
                null
            }

            is ConcernPartialChange.Agree.Failure -> {
                // 限流拦截的请求从未 setPending,绝不回退(判定与回滚收口在 runner)
                AgreeOpRunner.onFailureAgree(partialChange.threadId, partialChange.error)
                // 列表页点赞失败历来静默(与帖子页 Agree 口径一致),仅限流时提示
                if (partialChange.error is TiebaRateLimitedException)
                    CommonUiEvent.Toast(partialChange.error.message.orEmpty())
                else null
            }

            // 列表重载:本次返回的 agreeNum 基准已包含已确认操作,对齐标记跟进意图
            is ConcernPartialChange.Refresh.Success -> {
                rebaseLoaded(partialChange.data); null
            }

            is ConcernPartialChange.LoadMore.Success -> {
                rebaseLoaded(partialChange.data); null
            }

            else -> null
        }

    private fun rebaseLoaded(data: List<ConcernData>) {
        AgreeOpRunner.rebaseLoaded(
            AgreeParams.OBJ_THREAD,
            data.mapNotNull { it.threadList?.threadId }
        )
    }

    private class ExplorePartialChangeProducer(
        private val tiebaApi: ITiebaApi,
    ) : PartialChangeProducer<ConcernUiIntent, ConcernPartialChange, ConcernUiState> {
        @OptIn(ExperimentalCoroutinesApi::class)
        override fun toPartialChangeFlow(intentFlow: Flow<ConcernUiIntent>): Flow<ConcernPartialChange> =
            merge(
                intentFlow.filterIsInstance<ConcernUiIntent.Refresh>().flatMapConcat { produceRefreshPartialChange() },
                intentFlow.filterIsInstance<ConcernUiIntent.LoadMore>().flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<ConcernUiIntent.Agree>().flatMapConcat { it.producePartialChange() },
            )

        private fun produceRefreshPartialChange(): Flow<ConcernPartialChange.Refresh> =
            tiebaApi.userLikeFlow("", App.INSTANCE.appPreferences.userLikeLastRequestUnix.value, 1)
                .map<UserLikeResponse, ConcernPartialChange.Refresh> {
                    App.INSTANCE.appPreferences.userLikeLastRequestUnix.set(it.data_?.requestUnix ?: 0L)
                    ConcernPartialChange.Refresh.Success(
                        data = it.toData(),
                        hasMore = it.data_?.hasMore == 1,
                        nextPageTag = it.data_?.pageTag ?: ""
                    )
                }
                .onStart { emit(ConcernPartialChange.Refresh.Start) }
                .catch { emit(ConcernPartialChange.Refresh.Failure(it)) }

        private fun ConcernUiIntent.LoadMore.producePartialChange(): Flow<ConcernPartialChange.LoadMore> =
            tiebaApi.userLikeFlow(pageTag, App.INSTANCE.appPreferences.userLikeLastRequestUnix.value, 2)
                .map<UserLikeResponse, ConcernPartialChange.LoadMore> {
                    ConcernPartialChange.LoadMore.Success(
                        data = it.toData(),
                        hasMore = it.data_?.hasMore == 1,
                        nextPageTag = it.data_?.pageTag ?: ""
                    )
                }
                .onStart { emit(ConcernPartialChange.LoadMore.Start) }
                .catch { emit(ConcernPartialChange.LoadMore.Failure(error = it)) }

        private fun ConcernUiIntent.Agree.producePartialChange(): Flow<ConcernPartialChange.Agree> =
            // 赞踩链路统一收口在 AgreeOpRunner,本页只做中性结果 → PartialChange 的类型映射
            AgreeOpRunner.threadAgree(threadId, postId, hasAgree, forumId)
                .map<ListAgreeOutcome, ConcernPartialChange.Agree> { outcome ->
                    when (outcome) {
                        is ListAgreeOutcome.Started ->
                            ConcernPartialChange.Agree.Start(threadId, outcome.newHasAgree)

                        is ListAgreeOutcome.Accepted ->
                            ConcernPartialChange.Agree.Success(threadId, hasAgree xor 1)

                        is ListAgreeOutcome.Rejected ->
                            ConcernPartialChange.Agree.Failure(threadId, hasAgree, outcome.error)
                    }
                }

        private fun UserLikeResponse.toData(): List<ConcernData> {
            return data_?.threadInfo ?: emptyList()
        }
    }
}

sealed interface ConcernUiIntent : UiIntent {
    data object Refresh : ConcernUiIntent

    data class LoadMore(val pageTag: String) : ConcernUiIntent

    data class Agree(
        val threadId: Long,
        val postId: Long,
        val hasAgree: Int,
        // E1:opAgree 官方必带参数,从 ThreadInfo 透传;null 时不发送
        val forumId: Long? = null,
    ) : ConcernUiIntent
}

internal fun List<ConcernData>.distinctById(): ImmutableList<ConcernData> {
    return distinctBy {
        it.threadList?.id
    }.toImmutableList()
}

sealed interface ConcernPartialChange : PartialChange<ConcernUiState> {
    sealed class Agree private constructor() : ConcernPartialChange {
        // 差分模型:显示由 FeedCard.ThreadAgreeBtn 从 OpRecordStore.records 推导,
        // reducer 不再改写 proto 计数;记录更新全部集中在 dispatchEvent
        override fun reduce(oldState: ConcernUiState): ConcernUiState =
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

    sealed class Refresh private constructor() : ConcernPartialChange {
        override fun reduce(oldState: ConcernUiState): ConcernUiState =
            when (this) {
                Start -> oldState.copy(isRefreshing = true)
                is Success -> oldState.copy(
                    isRefreshing = false,
                    data = data.distinctById(),
                    hasMore = hasMore,
                    nextPageTag = nextPageTag,
                )
                is Failure -> oldState.copy(isRefreshing = false)
            }

        data object Start : Refresh()

        data class Success(
            val data: List<ConcernData>,
            val hasMore: Boolean,
            val nextPageTag: String,
        ) : Refresh()

        data class Failure(
            val error: Throwable,
        ) : Refresh()
    }

    sealed class LoadMore private constructor() : ConcernPartialChange {
        override fun reduce(oldState: ConcernUiState): ConcernUiState =
            when (this) {
                Start -> oldState.copy(isLoadingMore = true)
                is Success -> oldState.copy(
                    isLoadingMore = false,
                    data = (oldState.data + data).distinctById(),
                    hasMore = hasMore,
                    nextPageTag = nextPageTag,
                )
                is Failure -> oldState.copy(isLoadingMore = false)
            }

        data object Start : LoadMore()

        data class Success(
            val data: List<ConcernData>,
            val hasMore: Boolean,
            val nextPageTag: String,
        ) : LoadMore()

        data class Failure(
            val error: Throwable,
        ) : LoadMore()
    }
}

data class ConcernUiState(
    val isRefreshing: Boolean = true,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val nextPageTag: String = "",
    val data: ImmutableList<ConcernData> = persistentListOf(),
): UiState

sealed interface ConcernUiEvent : UiEvent