package com.huanchengfly.tieba.post.ui.page.hottopic.detail

import androidx.compose.runtime.Stable
import com.huanchengfly.tieba.post.utils.AgreeOpRunner
import com.huanchengfly.tieba.post.utils.ListAgreeOutcome
import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.api.AgreeParams
import com.huanchengfly.tieba.post.api.interfaces.ITiebaApi
import com.huanchengfly.tieba.post.api.TiebaRateLimitedException
import com.huanchengfly.tieba.post.api.models.RelateForumBean
import com.huanchengfly.tieba.post.api.models.ThreadBean
import com.huanchengfly.tieba.post.api.models.TopicDetailBean
import com.huanchengfly.tieba.post.api.models.TopicInfoBean
import com.huanchengfly.tieba.post.core.network.model.protos.userLike.ConcernData
import com.huanchengfly.tieba.post.api.retrofit.exception.getErrorMessage
import com.huanchengfly.tieba.post.arch.BaseViewModel
import com.huanchengfly.tieba.post.arch.CommonUiEvent
import com.huanchengfly.tieba.post.arch.PartialChange
import com.huanchengfly.tieba.post.arch.PartialChangeProducer
import com.huanchengfly.tieba.post.arch.UiEvent
import com.huanchengfly.tieba.post.arch.UiIntent
import com.huanchengfly.tieba.post.arch.UiState
import com.huanchengfly.tieba.post.ui.page.main.explore.concern.ConcernPartialChange
import com.huanchengfly.tieba.post.ui.page.main.explore.concern.ConcernUiIntent
import com.huanchengfly.tieba.post.ui.page.main.explore.concern.ConcernUiState
import com.huanchengfly.tieba.post.ui.page.main.explore.personalized.PersonalizedPartialChange
import com.huanchengfly.tieba.post.ui.page.main.explore.personalized.PersonalizedUiEvent
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
import kotlin.collections.map

@Stable
@HiltViewModel
class TopicDetailViewModel @Inject constructor(
    private val tiebaApi: ITiebaApi,
) :
    BaseViewModel<TopicDetailUiIntent, TopicDetailPartialChange, TopicDetailUiState, UiEvent>() {
    override fun createInitialState(): TopicDetailUiState = TopicDetailUiState()

    override fun createPartialChangeProducer(): PartialChangeProducer<TopicDetailUiIntent, TopicDetailPartialChange, TopicDetailUiState> =
        TopicDetailPartialChangeProducer(tiebaApi)

    override fun dispatchEvent(partialChange: TopicDetailPartialChange): UiEvent? =
        when (partialChange) {
            is TopicDetailPartialChange.Agree.Start -> {
                // 乐观意图进记录表;显示数字/亮灯由 FeedCard.ThreadAgreeBtn 从 records 推导
                AgreeOpRunner.onStartAgree(partialChange.threadId, partialChange.hasAgree)
                null
            }

            is TopicDetailPartialChange.Agree.Failure -> {
                // 限流拦截的请求从未 setPending,绝不回退(判定与回滚收口在 runner)
                AgreeOpRunner.onFailureAgree(partialChange.threadId, partialChange.error)
                // 列表页点赞失败历来静默(与帖子页 Agree 口径一致),仅限流时提示
                if (partialChange.error is TiebaRateLimitedException)
                    CommonUiEvent.Toast(partialChange.error.message.orEmpty())
                else null
            }

            // 列表重载:本次返回的 agreeNum 基准已包含已确认操作,对齐标记跟进意图
            is TopicDetailPartialChange.Refresh.Success -> {
                rebaseLoaded(partialChange.relateThread); null
            }

            is TopicDetailPartialChange.LoadMore.Success -> {
                rebaseLoaded(partialChange.relateThread); null
            }

            else -> null
        }

    private fun rebaseLoaded(relateThread: List<ThreadBean>) {
        // 键与 FeedCard.ThreadAgreeBtn 的显示键一致(threadInfo.threadId,feedId 是 feed 项 id 不是主题 id)
        AgreeOpRunner.rebaseLoaded(
            AgreeParams.OBJ_THREAD,
            relateThread.map { it.threadInfo.threadId }
        )
    }

    private class TopicDetailPartialChangeProducer(
        private val tiebaApi: ITiebaApi,
    ) :
        PartialChangeProducer<TopicDetailUiIntent, TopicDetailPartialChange, TopicDetailUiState> {
        @OptIn(ExperimentalCoroutinesApi::class)
        override fun toPartialChangeFlow(intentFlow: Flow<TopicDetailUiIntent>): Flow<TopicDetailPartialChange> =
            merge(
                intentFlow.filterIsInstance<TopicDetailUiIntent.LoadMore>()
                    .flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<TopicDetailUiIntent.Refresh>()
                    .flatMapConcat { it.produceLoadPartialChange() },
                intentFlow.filterIsInstance<TopicDetailUiIntent.Agree>()
                    .flatMapConcat { it.producePartialChange() }
            )

        private fun TopicDetailUiIntent.LoadMore.producePartialChange(): Flow<TopicDetailPartialChange.LoadMore> =
            tiebaApi.topicDetailFlow(
                topicId.toString(),
                topicName,
                1,
                1,
                page,
                pageSize,
                (page - 1) * pageSize,
                lastId.toString()
            )
                .map<TopicDetailBean, TopicDetailPartialChange.LoadMore> {
                    TopicDetailPartialChange.LoadMore.Success(
                        it.data.hasMore,
                        it.data.wreq.page,
                        it.data.topicInfo,
                        it.data.relateForum,
                        it.data.relateThread.threadList,
                    )
                }
                .onStart { emit(TopicDetailPartialChange.LoadMore.Start) }
                .catch { emit(TopicDetailPartialChange.LoadMore.Failure(it)) }

        private fun TopicDetailUiIntent.Refresh.produceLoadPartialChange(): Flow<TopicDetailPartialChange.Refresh> =
            tiebaApi.topicDetailFlow(
                topicId.toString(),
                topicName,
                1,
                1,
                1,
                pageSize,
                0,
                ""
            )
                .map<TopicDetailBean, TopicDetailPartialChange.Refresh> {
                    TopicDetailPartialChange.Refresh.Success(
                        it.data.hasMore,
                        it.data.topicInfo,
                        it.data.relateForum,
                        it.data.relateThread.threadList,
                        // 外部审查-8:置顶内容防御性提取(失败降级为空),并与主列表按
                        // threadId 去重,避免同一帖重复渲染
                        TopicDetailPinned.extract(it.data.specialTopic)
                            .filterNot { pinned ->
                                it.data.relateThread.threadList.any { thread ->
                                    thread.threadInfo.threadId == pinned.threadInfo.threadId
                                }
                            },
                    )
                }
                .onStart { emit(TopicDetailPartialChange.Refresh.Start) }
                .catch { emit(TopicDetailPartialChange.Refresh.Failure(it)) }

        private fun TopicDetailUiIntent.Agree.producePartialChange(): Flow<TopicDetailPartialChange.Agree> =
            // 赞踩链路统一收口在 AgreeOpRunner,本页只做中性结果 → PartialChange 的类型映射
            AgreeOpRunner.threadAgree(threadId, postId, hasAgree, forumId)
                .map<ListAgreeOutcome, TopicDetailPartialChange.Agree> { outcome ->
                    when (outcome) {
                        is ListAgreeOutcome.Started ->
                            TopicDetailPartialChange.Agree.Start(threadId, outcome.newHasAgree)

                        is ListAgreeOutcome.Accepted ->
                            TopicDetailPartialChange.Agree.Success(threadId, hasAgree xor 1)

                        is ListAgreeOutcome.Rejected ->
                            TopicDetailPartialChange.Agree.Failure(threadId, hasAgree, outcome.error)
                    }
                }
    }
}

sealed interface TopicDetailUiIntent : UiIntent {
    data class Refresh(
        val topicId: Long,
        val topicName: String,
        val pageSize: Int
    ) : TopicDetailUiIntent

    data class LoadMore(
        val topicId: Long,
        val topicName: String,
        val page: Int,
        val pageSize: Int,
        val lastId: Long,
    ) : TopicDetailUiIntent

    data class Agree(
        val threadId: Long,
        val postId: Long,
        val hasAgree: Int,
        // E1:opAgree 官方必带参数,从 ThreadInfo 透传;null 时不发送
        val forumId: Long? = null,
    ) : TopicDetailUiIntent
}

sealed interface TopicDetailPartialChange : PartialChange<TopicDetailUiState> {
    sealed class Agree private constructor() : TopicDetailPartialChange {
        // 差分模型:显示由 FeedCard.ThreadAgreeBtn 从 OpRecordStore.records 推导,
        // reducer 不再改写 bean 字段;记录更新全部集中在 dispatchEvent
        override fun reduce(oldState: TopicDetailUiState): TopicDetailUiState =
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

    sealed class LoadMore : TopicDetailPartialChange {
        override fun reduce(oldState: TopicDetailUiState): TopicDetailUiState = when (this) {
            Start -> oldState.copy(isLoadingMore = true)
            is Success -> oldState.copy(
                isLoadingMore = false,
                currentPage = currentPage,
                // 空页=游标无法前移(last 取自 relateThread 末元素),继续开着只有
                // "拉了没反应"的死区;按分页终止处理让指示器落到"没有更多"
                hasMore = hasMore && relateThread.isNotEmpty(),
                topicInfo = topicInfo,
                relateForum = (oldState.relateForum + relateForum).distinctBy { it.forumId }
                    .toImmutableList(),
                relateThread = (oldState.relateThread + relateThread).distinctBy { it.feedId }
                    .toImmutableList(),
            )

            is Failure -> oldState.copy(isLoadingMore = false)
        }

        object Start : LoadMore()

        data class Success(
            val hasMore: Boolean,
            val currentPage: Int,
            val topicInfo: TopicInfoBean,
            val relateForum: List<RelateForumBean>,
            val relateThread: List<ThreadBean>
        ) : LoadMore()

        data class Failure(
            val error: Throwable
        ) : LoadMore()
    }


    sealed class Refresh : TopicDetailPartialChange {
        override fun reduce(oldState: TopicDetailUiState): TopicDetailUiState = when (this) {
            Start -> oldState.copy(isRefreshing = true)
            is Success -> oldState.copy(
                isRefreshing = false,
                currentPage = 1,
                // 首页即空(has_more 与条目数无本地关联)时无游标可续,直接终止分页
                hasMore = hasMore && relateThread.isNotEmpty(),
                topicInfo = topicInfo,
                relateForum = relateForum.distinctBy { it.forumId }.toImmutableList(),
                relateThread = relateThread.distinctBy { it.feedId }.toImmutableList(),
                pinnedThread = pinnedThread.distinctBy { it.threadInfo.threadId }.toImmutableList(),
            )

            is Failure -> oldState.copy(isRefreshing = false)
        }

        object Start : Refresh()

        data class Success(
            val hasMore: Boolean,
            val topicInfo: TopicInfoBean,
            val relateForum: List<RelateForumBean>,
            val relateThread: List<ThreadBean>,
            // 外部审查-8:置顶/特殊内容(提取失败为空列表,不展示)
            val pinnedThread: List<ThreadBean> = emptyList(),
        ) : Refresh()

        data class Failure(
            val error: Throwable
        ) : Refresh()
    }
}

data class TopicDetailUiState(
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isError: Boolean = false,
    val hasMore: Boolean = true,
    val currentPage: Int = 1,
    val topicInfo: TopicInfoBean? = null,
    val relateForum: ImmutableList<RelateForumBean> = persistentListOf(),
    val relateThread: ImmutableList<ThreadBean> = persistentListOf(),
    // 外部审查-8:置顶/特殊内容,独立展示区;解析/形状不符时为空
    val pinnedThread: ImmutableList<ThreadBean> = persistentListOf(),
) : UiState
