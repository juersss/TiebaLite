package com.huanchengfly.tieba.post.ui.page.main.explore.personalized

import androidx.compose.runtime.Stable
import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.api.AgreeParams
import com.huanchengfly.tieba.post.api.interfaces.ITiebaApi
import com.huanchengfly.tieba.post.api.TiebaRateLimitedException
import com.huanchengfly.tieba.post.api.models.CommonResponse
import com.huanchengfly.tieba.post.core.network.model.protos.ThreadInfo
import com.huanchengfly.tieba.post.core.network.model.protos.personalized.DislikeReason
import com.huanchengfly.tieba.post.core.network.model.protos.personalized.PersonalizedResponse
import com.huanchengfly.tieba.post.api.retrofit.exception.getErrorMessage
import com.huanchengfly.tieba.post.arch.BaseViewModel
import com.huanchengfly.tieba.post.arch.CommonUiEvent
import com.huanchengfly.tieba.post.arch.ImmutableHolder
import com.huanchengfly.tieba.post.arch.PartialChange
import com.huanchengfly.tieba.post.arch.PartialChangeProducer
import com.huanchengfly.tieba.post.arch.UiEvent
import com.huanchengfly.tieba.post.arch.UiIntent
import com.huanchengfly.tieba.post.arch.UiState
import com.huanchengfly.tieba.post.arch.wrapImmutable
import com.huanchengfly.tieba.post.api.models.DislikeBean
import com.huanchengfly.tieba.post.repository.PersonalizedRepository
import com.huanchengfly.tieba.post.ui.models.ThreadItemData
import com.huanchengfly.tieba.post.ui.models.distinctById
import com.huanchengfly.tieba.post.utils.AgreeOpRunner
import com.huanchengfly.tieba.post.utils.ListAgreeOutcome
import com.huanchengfly.tieba.post.core.data.appPreferences
import com.huanchengfly.tieba.post.utils.FollowedForumsCache
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
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
class PersonalizedViewModel @Inject constructor(
    private val tiebaApi: ITiebaApi,
) :
    BaseViewModel<PersonalizedUiIntent, PersonalizedPartialChange, PersonalizedUiState, PersonalizedUiEvent>() {
    override fun createInitialState(): PersonalizedUiState = PersonalizedUiState()

    override fun createPartialChangeProducer(): PartialChangeProducer<PersonalizedUiIntent, PersonalizedPartialChange, PersonalizedUiState> =
        ExplorePartialChangeProducer(tiebaApi)

    override fun dispatchEvent(partialChange: PersonalizedPartialChange): UiEvent? =
        when (partialChange) {
            is PersonalizedPartialChange.Refresh.Failure -> CommonUiEvent.Toast(partialChange.error.getErrorMessage())
            is PersonalizedPartialChange.LoadMore.Failure -> CommonUiEvent.Toast(partialChange.error.getErrorMessage())
            is PersonalizedPartialChange.Refresh.Success -> {
                // 列表重载:本次返回的 agreeNum 基准已包含已确认操作,对齐标记跟进意图
                rebaseLoaded(partialChange.data)
                PersonalizedUiEvent.RefreshSuccess(partialChange.data.size)
            }

            is PersonalizedPartialChange.Agree.Start -> {
                // 乐观意图进记录表;显示数字/亮灯由 FeedCard.ThreadAgreeBtn 从 records 推导
                AgreeOpRunner.onStartAgree(partialChange.threadId, partialChange.hasAgree)
                null
            }

            is PersonalizedPartialChange.Agree.Failure -> {
                // 限流拦截的请求从未 setPending,绝不回退(判定与回滚收口在 runner)
                AgreeOpRunner.onFailureAgree(partialChange.threadId, partialChange.error)
                // 列表页点赞失败历来静默(与帖子页 Agree 口径一致),仅限流时提示
                if (partialChange.error is TiebaRateLimitedException)
                    CommonUiEvent.Toast(partialChange.error.message.orEmpty())
                else null
            }

            is PersonalizedPartialChange.LoadMore.Success -> {
                rebaseLoaded(partialChange.data); null
            }

            else -> null
        }

    private fun rebaseLoaded(data: List<ThreadItemData>) {
        AgreeOpRunner.rebaseLoaded(
            AgreeParams.OBJ_THREAD,
            data.map { it.thread.get { threadId } }
        )
    }

    private class ExplorePartialChangeProducer(
        private val tiebaApi: ITiebaApi,
    ) : PartialChangeProducer<PersonalizedUiIntent, PersonalizedPartialChange, PersonalizedUiState> {
        @OptIn(ExperimentalCoroutinesApi::class)
        override fun toPartialChangeFlow(intentFlow: Flow<PersonalizedUiIntent>): Flow<PersonalizedPartialChange> =
            merge(
                intentFlow.filterIsInstance<PersonalizedUiIntent.Refresh>().flatMapConcat { produceRefreshPartialChange() },
                intentFlow.filterIsInstance<PersonalizedUiIntent.LoadMore>().flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<PersonalizedUiIntent.Dislike>().flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<PersonalizedUiIntent.Agree>().flatMapConcat { it.producePartialChange() },
            )

        private fun produceRefreshPartialChange(): Flow<PersonalizedPartialChange.Refresh> =
            PersonalizedRepository
                .personalizedFlow(1, 1)
                .map<PersonalizedResponse, PersonalizedPartialChange.Refresh> { response ->
                    val data = response.toData()
                        .filter {
                            !App.INSTANCE.appPreferences.blockVideo.value || it.get { videoInfo } == null
                        }
                        .filter { it.get { ala_info } == null }
                        // 过滤未关注的吧
                        .filter {
                            val showFollowedOnly = App.INSTANCE.appPreferences.showFollowedOnly.value
                            !showFollowedOnly || FollowedForumsCache.isFollowed(it.get { forumId })
                        }
                    val threadPersonalizedData = response.data_?.thread_personalized ?: emptyList()
                    PersonalizedPartialChange.Refresh.Success(
                        data = data.map { thread ->
                            val threadPersonalized =
                                threadPersonalizedData.firstOrNull { it.tid == thread.get { id } }
                                    ?.wrapImmutable()
                            ThreadItemData(thread = thread, personalized = threadPersonalized)
                        }.toImmutableList(),
                    )
                }
                .onStart { emit(PersonalizedPartialChange.Refresh.Start) }
                .catch { emit(PersonalizedPartialChange.Refresh.Failure(it)) }

        private fun PersonalizedUiIntent.LoadMore.producePartialChange(): Flow<PersonalizedPartialChange.LoadMore> =
            PersonalizedRepository
                .personalizedFlow(2, page)
                .map<PersonalizedResponse, PersonalizedPartialChange.LoadMore> { response ->
                    val data = response.toData()
                        .filter {
                            !App.INSTANCE.appPreferences.blockVideo.value || it.get { videoInfo } == null
                        }
                        .filter { it.get { ala_info } == null }
                        // 过滤未关注的吧
                        .filter {
                            val showFollowedOnly = App.INSTANCE.appPreferences.showFollowedOnly.value
                            !showFollowedOnly || FollowedForumsCache.isFollowed(it.get { forumId })
                        }
                    val threadPersonalizedData = response.data_?.thread_personalized ?: emptyList()
                    PersonalizedPartialChange.LoadMore.Success(
                        currentPage = page,
                        data = data.map { thread ->
                            val threadPersonalized =
                                threadPersonalizedData.firstOrNull { it.tid == thread.get { id } }
                                    ?.wrapImmutable()
                            ThreadItemData(thread = thread, personalized = threadPersonalized)
                        }.toImmutableList(),
                    )
                }
                .onStart { emit(PersonalizedPartialChange.LoadMore.Start) }
                .catch { emit(PersonalizedPartialChange.LoadMore.Failure(currentPage = page, error = it)) }

        private fun PersonalizedUiIntent.Dislike.producePartialChange(): Flow<PersonalizedPartialChange.Dislike> =
            tiebaApi.submitDislikeFlow(
                DislikeBean(
                    threadId.toString(),
                    reasons.joinToString(",") { it.get { dislikeId }.toString() },
                    forumId?.toString(),
                    clickTime,
                    reasons.joinToString(",") { it.get { extra } },
                )
            ).map<CommonResponse, PersonalizedPartialChange.Dislike> { PersonalizedPartialChange.Dislike.Success(threadId) }
                .catch { emit(PersonalizedPartialChange.Dislike.Failure(threadId, it)) }
                .onStart { emit(PersonalizedPartialChange.Dislike.Start(threadId)) }

        private fun PersonalizedUiIntent.Agree.producePartialChange(): Flow<PersonalizedPartialChange.Agree> =
            // 赞踩链路统一收口在 AgreeOpRunner,本页只做中性结果 → PartialChange 的类型映射
            AgreeOpRunner.threadAgree(threadId, postId, hasAgree, forumId)
                .map<ListAgreeOutcome, PersonalizedPartialChange.Agree> { outcome ->
                    when (outcome) {
                        is ListAgreeOutcome.Started ->
                            PersonalizedPartialChange.Agree.Start(threadId, outcome.newHasAgree)

                        is ListAgreeOutcome.Accepted ->
                            PersonalizedPartialChange.Agree.Success(threadId, hasAgree xor 1)

                        is ListAgreeOutcome.Rejected ->
                            PersonalizedPartialChange.Agree.Failure(threadId, hasAgree, outcome.error)
                    }
                }

        private fun PersonalizedResponse.toData(): ImmutableList<ImmutableHolder<ThreadInfo>> {
            return (data_?.thread_list ?: emptyList()).wrapImmutable()
        }
    }
}

sealed interface PersonalizedUiIntent : UiIntent {
    data object Refresh : PersonalizedUiIntent

    data class LoadMore(val page: Int) : PersonalizedUiIntent

    data class Agree(
        val threadId: Long,
        val postId: Long,
        val hasAgree: Int,
        // E1:opAgree 官方必带参数,从 ThreadInfo 透传;null 时不发送
        val forumId: Long? = null,
    ) : PersonalizedUiIntent

    data class Dislike(
        val forumId: Long?,
        val threadId: Long,
        val reasons: List<ImmutableHolder<DislikeReason>>,
        val clickTime: Long
    ) : PersonalizedUiIntent
}

sealed interface PersonalizedPartialChange : PartialChange<PersonalizedUiState> {
    sealed class Agree private constructor() : PersonalizedPartialChange {
        // 差分模型:显示由 FeedCard.ThreadAgreeBtn 从 OpRecordStore.records 推导,
        // reducer 不再改写 proto 计数;记录更新全部集中在 dispatchEvent
        override fun reduce(oldState: PersonalizedUiState): PersonalizedUiState =
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

    sealed class Dislike private constructor() : PersonalizedPartialChange {
        override fun reduce(oldState: PersonalizedUiState): PersonalizedUiState =
            when (this) {
                is Start -> {
                    if (!oldState.hiddenThreadIds.contains(threadId)) {
                        oldState.copy(hiddenThreadIds = (oldState.hiddenThreadIds + threadId).toImmutableList())
                    } else {
                        oldState
                    }
                }
                is Success -> {
                    if (!oldState.hiddenThreadIds.contains(threadId)) {
                        oldState.copy(hiddenThreadIds = (oldState.hiddenThreadIds + threadId).toImmutableList())
                    } else {
                        oldState
                    }
                }
                is Failure -> oldState
            }

        data class Start(
            val threadId: Long,
        ) : Dislike()

        data class Success(
            val threadId: Long,
        ) : Dislike()

        data class Failure(
            val threadId: Long,
            val error: Throwable,
        ) : Dislike()
    }

    sealed class Refresh private constructor() : PersonalizedPartialChange {
        override fun reduce(oldState: PersonalizedUiState): PersonalizedUiState =
            when (this) {
                Start -> oldState.copy(isRefreshing = true)
                is Success -> {
                    val oldSize = oldState.data.size
                    val newData = (data + oldState.data).distinctById()
                    oldState.copy(
                        isRefreshing = false,
                        currentPage = 1,
                        data = newData,
                        refreshPosition = if (oldState.data.isEmpty()) 0 else (newData.size - oldSize),
                    )
                }

                is Failure -> oldState.copy(
                    isRefreshing = false,
                    error = error.wrapImmutable()
                )
            }

        data object Start : Refresh()

        data class Success(
            val data: List<ThreadItemData>,
        ) : Refresh()

        data class Failure(
            val error: Throwable,
        ) : Refresh()
    }

    sealed class LoadMore private constructor() : PersonalizedPartialChange {
        override fun reduce(oldState: PersonalizedUiState): PersonalizedUiState =
            when (this) {
                Start -> oldState.copy(isLoadingMore = true)
                is Success -> oldState.copy(
                    isLoadingMore = false,
                    currentPage = currentPage,
                    data = (oldState.data + data).distinctById(),
                )

                is Failure -> oldState.copy(
                    isLoadingMore = false,
                    error = error.wrapImmutable()
                )
            }

        data object Start : LoadMore()

        data class Success(
            val currentPage: Int,
            val data: List<ThreadItemData>,
        ) : LoadMore()

        data class Failure(
            val currentPage: Int,
            val error: Throwable,
        ) : LoadMore()
    }
}

data class PersonalizedUiState(
    val isRefreshing: Boolean = true,
    val isLoadingMore: Boolean = false,
    val error: ImmutableHolder<Throwable>? = null,
    val currentPage: Int = 1,
    val data: ImmutableList<ThreadItemData> = persistentListOf(),
    val hiddenThreadIds: ImmutableList<Long> = persistentListOf(),
    val refreshPosition: Int = 0,
): UiState

sealed interface PersonalizedUiEvent : UiEvent {
    data class RefreshSuccess(val count: Int) : PersonalizedUiEvent
}