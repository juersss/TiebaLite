package com.huanchengfly.tieba.post.ui.page.user.post

import androidx.compose.runtime.Immutable
import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.api.AgreeParams
import com.huanchengfly.tieba.post.api.interfaces.ITiebaApi
import com.huanchengfly.tieba.post.core.network.model.protos.PostInfoList
import com.huanchengfly.tieba.post.core.network.model.protos.abstractText
import com.huanchengfly.tieba.post.core.network.model.protos.userPost.UserPostResponse
import com.huanchengfly.tieba.post.api.retrofit.exception.getErrorMessage
import com.huanchengfly.tieba.post.utils.AgreeOpRunner
import com.huanchengfly.tieba.post.utils.ListAgreeOutcome
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
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject

@HiltViewModel
class UserPostViewModel @Inject constructor(
    private val tiebaApi: ITiebaApi,
) :
    BaseViewModel<UserPostUiIntent, UserPostPartialChange, UserPostUiState, UiEvent>() {
    override fun createInitialState(): UserPostUiState = UserPostUiState()

    override fun createPartialChangeProducer(): PartialChangeProducer<UserPostUiIntent, UserPostPartialChange, UserPostUiState> =
        UserPostPartialChangeProducer(tiebaApi)

    override fun dispatchEvent(partialChange: UserPostPartialChange): UiEvent? =
        when (partialChange) {
            is UserPostPartialChange.Agree.Start -> {
                // 乐观意图进记录表;显示数字/亮灯由 FeedCard.ThreadAgreeBtn 从 records 推导
                AgreeOpRunner.onStartAgree(partialChange.threadId, partialChange.hasAgree)
                null
            }

            is UserPostPartialChange.Agree.Failure -> {
                // 限流拦截的请求从未 setPending,绝不回退(判定与回滚收口在 runner)
                AgreeOpRunner.onFailureAgree(partialChange.threadId, partialChange.error)
                CommonUiEvent.Toast(
                    App.INSTANCE.getString(
                        R.string.toast_agree_failed,
                        partialChange.error.getErrorMessage()
                    )
                )
            }

            // 列表重载:本次返回的 agree_num 基准已包含已确认操作,对齐标记跟进意图
            is UserPostPartialChange.Refresh.Success -> {
                rebaseLoaded(partialChange.posts); null
            }

            is UserPostPartialChange.LoadMore.Success -> {
                rebaseLoaded(partialChange.posts); null
            }

            else -> null
        }

    private fun rebaseLoaded(posts: List<PostInfoList>) {
        AgreeOpRunner.rebaseLoaded(
            AgreeParams.OBJ_THREAD,
            posts.map { it.thread_id }
        )
    }

    private class UserPostPartialChangeProducer(
        private val tiebaApi: ITiebaApi,
    ) :
        PartialChangeProducer<UserPostUiIntent, UserPostPartialChange, UserPostUiState> {
        @OptIn(ExperimentalCoroutinesApi::class)
        override fun toPartialChangeFlow(intentFlow: Flow<UserPostUiIntent>): Flow<UserPostPartialChange> =
            merge(
                intentFlow.filterIsInstance<UserPostUiIntent.Refresh>()
                    .flatMapConcat { it.toPartialChangeFlow() },
                intentFlow.filterIsInstance<UserPostUiIntent.LoadMore>()
                    .flatMapConcat { it.toPartialChangeFlow() },
                intentFlow.filterIsInstance<UserPostUiIntent.Agree>()
                    .flatMapConcat { it.toPartialChangeFlow() }
            )

        private fun UserPostUiIntent.Refresh.toPartialChangeFlow(): Flow<UserPostPartialChange> =
            tiebaApi
                .userPostFlow(uid, 1, isThread)
                .map<UserPostResponse, UserPostPartialChange.Refresh> {
                    checkNotNull(it.data_)
                    val postList = it.data_!!.post_list
                    UserPostPartialChange.Refresh.Success(
                        currentPage = 1,
                        hasMore = postList.isNotEmpty(),
                        posts = postList,
                        hidePost = it.data_!!.hide_post == 1
                    )
                }
                .onStart { emit(UserPostPartialChange.Refresh.Start) }
                .catch { emit(UserPostPartialChange.Refresh.Failure(it)) }

        private fun UserPostUiIntent.LoadMore.toPartialChangeFlow(): Flow<UserPostPartialChange> =
            tiebaApi
                .userPostFlow(uid, page + 1, isThread)
                .map<UserPostResponse, UserPostPartialChange.LoadMore> {
                    checkNotNull(it.data_)
                    val postList = it.data_!!.post_list
                    UserPostPartialChange.LoadMore.Success(
                        currentPage = page + 1,
                        hasMore = postList.isNotEmpty(),
                        posts = postList
                    )
                }
                .onStart { emit(UserPostPartialChange.LoadMore.Start) }
                .catch { emit(UserPostPartialChange.LoadMore.Failure(it)) }

        private fun UserPostUiIntent.Agree.toPartialChangeFlow(): Flow<UserPostPartialChange.Agree> =
            // 赞踩链路统一收口在 AgreeOpRunner,本页只做中性结果 → PartialChange 的类型映射。
            // 记录键用 OBJ_THREAD+threadId——与 FeedCard.ThreadAgreeBtn 的显示键一致
            AgreeOpRunner.threadAgree(threadId, postId, hasAgree, forumId)
                .map<ListAgreeOutcome, UserPostPartialChange.Agree> { outcome ->
                    when (outcome) {
                        is ListAgreeOutcome.Started ->
                            UserPostPartialChange.Agree.Start(threadId, postId, outcome.newHasAgree)

                        is ListAgreeOutcome.Accepted ->
                            UserPostPartialChange.Agree.Success(threadId, postId, hasAgree xor 1)

                        is ListAgreeOutcome.Rejected ->
                            UserPostPartialChange.Agree.Failure(
                                threadId,
                                postId,
                                hasAgree,
                                outcome.error
                            )
                    }
                }
    }
}

sealed interface UserPostUiIntent : UiIntent {
    data class Refresh(
        val uid: Long,
        val isThread: Boolean,
    ) : UserPostUiIntent

    data class LoadMore(
        val uid: Long,
        val isThread: Boolean,
        val page: Int,
    ) : UserPostUiIntent

    data class Agree(
        val threadId: Long,
        val postId: Long,
        val hasAgree: Int,
        // E1:opAgree 官方必带参数,从 ThreadInfo 透传;null 时不发送
        val forumId: Long? = null,
    ) : UserPostUiIntent
}

sealed interface UserPostPartialChange : PartialChange<UserPostUiState> {
    sealed class Refresh : UserPostPartialChange {
        override fun reduce(oldState: UserPostUiState): UserPostUiState = when (this) {
            is Start -> oldState.copy(
                isRefreshing = true,
            )

            is Success -> {
                val uniquePosts = posts.distinctBy {
                    "${it.thread_id}_${it.post_id}"
                }.toData()
                oldState.copy(
                    isRefreshing = false,
                    error = null,
                    currentPage = currentPage,
                    hasMore = hasMore,
                    hidePost = hidePost,
                    posts = uniquePosts.toImmutableList()
                )
            }

            is Failure -> oldState.copy(
                isRefreshing = false,
                error = error.wrapImmutable()
            )
        }

        data object Start : Refresh()

        data class Success(
            val currentPage: Int,
            val hasMore: Boolean,
            val posts: List<PostInfoList>,
            val hidePost: Boolean,
        ) : Refresh()

        data class Failure(
            val error: Throwable,
        ) : Refresh()
    }

    sealed class LoadMore : UserPostPartialChange {
        override fun reduce(oldState: UserPostUiState): UserPostUiState = when (this) {
            is Start -> oldState.copy(
                isLoadingMore = true,
            )

            is Success -> {
                val uniquePosts = (oldState.posts + posts.toData()).distinctBy {
                    "${it.data.get { thread_id }}_${it.data.get { post_id }}"
                }.toImmutableList()
                oldState.copy(
                    isLoadingMore = false,
                    error = null,
                    currentPage = currentPage,
                    hasMore = hasMore,
                    posts = uniquePosts
                )
            }

            is Failure -> oldState.copy(
                isLoadingMore = false,
                error = error.wrapImmutable()
            )
        }

        data object Start : LoadMore()

        data class Success(
            val currentPage: Int,
            val hasMore: Boolean,
            val posts: List<PostInfoList>,
        ) : LoadMore()

        data class Failure(
            val error: Throwable,
        ) : LoadMore()
    }

    sealed class Agree : UserPostPartialChange {
        // 差分模型:显示由 FeedCard.ThreadAgreeBtn 从 OpRecordStore.records 推导,
        // reducer 不再改写 proto 计数;记录更新全部集中在 dispatchEvent
        override fun reduce(oldState: UserPostUiState): UserPostUiState =
            when (this) {
                is Start -> oldState
                is Success -> oldState
                is Failure -> oldState
            }

        data class Start(
            val threadId: Long,
            val postId: Long,
            val hasAgree: Int,
        ) : Agree()

        data class Success(
            val threadId: Long,
            val postId: Long,
            val hasAgree: Int,
        ) : Agree()

        data class Failure(
            val threadId: Long,
            val postId: Long,
            val hasAgree: Int,
            val error: Throwable,
        ) : Agree()
    }
}

data class UserPostUiState(
    val isRefreshing: Boolean = true,
    val isLoadingMore: Boolean = false,
    val error: ImmutableHolder<Throwable>? = null,

    val currentPage: Int = 1,
    val hasMore: Boolean = false,
    val posts: ImmutableList<PostListItemData> = persistentListOf(),
    val hidePost: Boolean = false,
) : UiState

private fun List<PostInfoList>.toData(): ImmutableList<PostListItemData> {
    return map { postInfo ->
        PostListItemData(
            data = postInfo.wrapImmutable(),
            contents = postInfo.content.map {
                PostContentData(
                    contentText = it.post_content.abstractText,
                    createTime = it.create_time,
                    postId = it.post_id,
                    isSubPost = (it.post_type == 1L),
                )
            }.toImmutableList()
        )
    }.toImmutableList()
}

@Immutable
data class PostListItemData(
    val data: ImmutableHolder<PostInfoList>,
//    val blocked: Boolean,
    val isThread: Boolean = data.get { is_thread } == 1,
    val contents: ImmutableList<PostContentData> = persistentListOf(),
)

@Immutable
data class PostContentData(
    val contentText: String,
    val createTime: Long,
    val postId: Long,
    val isSubPost: Boolean,
)