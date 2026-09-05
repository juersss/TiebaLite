package com.huanchengfly.tieba.post.ui.page.user.post

import androidx.compose.runtime.Immutable
import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.api.AgreeParams
import com.huanchengfly.tieba.post.api.AgreeRateLimiter
import com.huanchengfly.tieba.post.api.TiebaApi
import com.huanchengfly.tieba.post.api.TiebaRateLimitedException
import com.huanchengfly.tieba.post.api.models.AgreeBean
import com.huanchengfly.tieba.post.api.models.CommonResponse
import com.huanchengfly.tieba.post.api.models.protos.MyAgreeOp
import com.huanchengfly.tieba.post.api.models.protos.OpAgreeResult
import com.huanchengfly.tieba.post.api.models.protos.PostInfoList
import com.huanchengfly.tieba.post.api.models.protos.abstractText
import com.huanchengfly.tieba.post.api.models.protos.serverOpFromErrorCode
import com.huanchengfly.tieba.post.api.models.protos.serverOpFromErrorMessage
import com.huanchengfly.tieba.post.api.models.protos.toOpAgreeResult
import com.huanchengfly.tieba.post.api.models.protos.userPost.UserPostResponse
import com.huanchengfly.tieba.post.api.retrofit.exception.TiebaApiException
import com.huanchengfly.tieba.post.api.retrofit.exception.getErrorMessage
import com.huanchengfly.tieba.post.utils.OpRecordStore
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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject

@HiltViewModel
class UserPostViewModel @Inject constructor() :
    BaseViewModel<UserPostUiIntent, UserPostPartialChange, UserPostUiState, UiEvent>() {
    override fun createInitialState(): UserPostUiState = UserPostUiState()

    override fun createPartialChangeProducer(): PartialChangeProducer<UserPostUiIntent, UserPostPartialChange, UserPostUiState> =
        UserPostPartialChangeProducer

    override fun dispatchEvent(partialChange: UserPostPartialChange): UiEvent? =
        when (partialChange) {
            is UserPostPartialChange.Agree.Start -> {
                // 乐观意图进记录表;显示数字/亮灯由 FeedCard.ThreadAgreeBtn 从 records 推导
                OpRecordStore.setPending(
                    App.INSTANCE,
                    AgreeParams.OBJ_THREAD,
                    partialChange.threadId,
                    if (partialChange.hasAgree == 1) MyAgreeOp.AGREE else MyAgreeOp.NONE
                )
                null
            }

            is UserPostPartialChange.Agree.Failure -> {
                // 限流异常不是 TiebaException——按类型判定;被拦截的请求从未 setPending,
                // 绝不 revertPending(否则凭空写出 my=server=NONE 记录,永久屏蔽服务端回显)
                if (partialChange.error !is TiebaRateLimitedException) {
                    OpRecordStore.revertPending(
                        App.INSTANCE,
                        AgreeParams.OBJ_THREAD,
                        partialChange.threadId
                    )
                }
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
        val keys = HashSet<String>(posts.size)
        posts.forEach {
            keys.add(OpRecordStore.key(AgreeParams.OBJ_THREAD, it.thread_id))
        }
        OpRecordStore.rebase(App.INSTANCE, keys)
    }

    private object UserPostPartialChangeProducer :
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
            TiebaApi.getInstance()
                .userPostFlow(uid, 1, isThread)
                .map<UserPostResponse, UserPostPartialChange.Refresh> {
                    checkNotNull(it.data_)
                    val postList = it.data_.post_list
                    UserPostPartialChange.Refresh.Success(
                        currentPage = 1,
                        hasMore = postList.isNotEmpty(),
                        posts = postList,
                        hidePost = it.data_.hide_post == 1
                    )
                }
                .onStart { emit(UserPostPartialChange.Refresh.Start) }
                .catch { emit(UserPostPartialChange.Refresh.Failure(it)) }

        private fun UserPostUiIntent.LoadMore.toPartialChangeFlow(): Flow<UserPostPartialChange> =
            TiebaApi.getInstance()
                .userPostFlow(uid, page + 1, isThread)
                .map<UserPostResponse, UserPostPartialChange.LoadMore> {
                    checkNotNull(it.data_)
                    val postList = it.data_.post_list
                    UserPostPartialChange.LoadMore.Success(
                        currentPage = page + 1,
                        hasMore = postList.isNotEmpty(),
                        posts = postList
                    )
                }
                .onStart { emit(UserPostPartialChange.LoadMore.Start) }
                .catch { emit(UserPostPartialChange.LoadMore.Failure(it)) }

        private fun UserPostUiIntent.Agree.toPartialChangeFlow(): Flow<UserPostPartialChange.Agree> {
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
                    TiebaApi.getInstance()
                        .opAgreeFlow(
                            threadId.toString(), postId.toString(), hasAgree, objType = 3
                        )
                )
            }.map<AgreeBean, UserPostPartialChange.Agree> { bean ->
                    val agree = (hasAgree xor 1) == 1
                    // HTTP 200 不等于业务成功:与帖子页/其他列表页同构,先按 errorCode 三态判定。
                    // 记录键用 OBJ_THREAD+threadId——与 FeedCard.ThreadAgreeBtn 的显示键一致
                    when (val result = bean.toOpAgreeResult(threadId, agree)) {
                        // Ok 不写记录——Start 的 setPending 已表达意图,此刻 confirm 会抵消乐观偏移
                        is OpAgreeResult.Ok -> {
                            undoDisagreeIfAccepted()
                            UserPostPartialChange.Agree.Success(threadId, postId, hasAgree xor 1)
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
                            UserPostPartialChange.Agree.Success(threadId, postId, hasAgree xor 1)
                        }

                        is OpAgreeResult.Business -> {
                            // 普通业务拒绝:不 confirm(否则 my=server 落盘永不自愈),
                            // 回滚统一交给 dispatchEvent(与网络失败同路)
                            UserPostPartialChange.Agree.Failure(
                                threadId,
                                postId,
                                hasAgree,
                                TiebaApiException(
                                    CommonResponse(
                                        result.code.toIntOrNull()
                                            ?: CommonResponse.ERROR_CODE_UNKNOWN,
                                        result.msg
                                    )
                                )
                            )
                        }
                    }
                }
                .onStart {
                    if (acquired) emit(
                        UserPostPartialChange.Agree.Start(
                            threadId,
                            postId,
                            hasAgree xor 1
                        )
                    )
                }
                .catch {
                    // 权威码异常路径采纳,收尾半截修复(R8 裁决 12,同 ForumThreadList 注释)
                    val authoritative = serverOpFromErrorMessage(it.getErrorMessage())
                    if (authoritative == null) {
                        emit(UserPostPartialChange.Agree.Failure(threadId, postId, hasAgree, it))
                    } else {
                        OpRecordStore.confirm(App.INSTANCE, AgreeParams.OBJ_THREAD, threadId, authoritative)
                        undoDisagreeIfAccepted()
                        emit(UserPostPartialChange.Agree.Success(threadId, postId, hasAgree xor 1))
                    }
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