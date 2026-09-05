package com.huanchengfly.tieba.post.ui.page.hottopic.detail

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
import com.huanchengfly.tieba.post.api.models.protos.toOpAgreeResult
import com.huanchengfly.tieba.post.api.models.AgreeBean
import com.huanchengfly.tieba.post.api.models.CommonResponse
import com.huanchengfly.tieba.post.api.models.RelateForumBean
import com.huanchengfly.tieba.post.api.models.ThreadBean
import com.huanchengfly.tieba.post.api.models.TopicDetailBean
import com.huanchengfly.tieba.post.api.models.TopicInfoBean
import com.huanchengfly.tieba.post.api.models.protos.userLike.ConcernData
import com.huanchengfly.tieba.post.api.retrofit.exception.TiebaApiException
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
class TopicDetailViewModel @Inject constructor() :
    BaseViewModel<TopicDetailUiIntent, TopicDetailPartialChange, TopicDetailUiState, UiEvent>() {
    override fun createInitialState(): TopicDetailUiState = TopicDetailUiState()

    override fun createPartialChangeProducer(): PartialChangeProducer<TopicDetailUiIntent, TopicDetailPartialChange, TopicDetailUiState> =
        TopicDetailPartialChangeProducer

    override fun dispatchEvent(partialChange: TopicDetailPartialChange): UiEvent? =
        when (partialChange) {
            is TopicDetailPartialChange.Agree.Start -> {
                // 乐观意图进记录表;显示数字/亮灯由 FeedCard.ThreadAgreeBtn 从 records 推导
                OpRecordStore.setPending(
                    App.INSTANCE,
                    AgreeParams.OBJ_THREAD,
                    partialChange.threadId,
                    if (partialChange.hasAgree == 1) MyAgreeOp.AGREE else MyAgreeOp.NONE
                )
                null
            }

            is TopicDetailPartialChange.Agree.Failure -> {
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
        val keys = HashSet<String>(relateThread.size)
        relateThread.forEach {
            keys.add(OpRecordStore.key(AgreeParams.OBJ_THREAD, it.threadInfo.threadId))
        }
        OpRecordStore.rebase(App.INSTANCE, keys)
    }

    private object TopicDetailPartialChangeProducer :
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
            TiebaApi.getInstance().topicDetailFlow(
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
            TiebaApi.getInstance().topicDetailFlow(
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
                    )
                }
                .onStart { emit(TopicDetailPartialChange.Refresh.Start) }
                .catch { emit(TopicDetailPartialChange.Refresh.Failure(it)) }

        private fun TopicDetailUiIntent.Agree.producePartialChange(): Flow<TopicDetailPartialChange.Agree> {
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
            }.map<AgreeBean, TopicDetailPartialChange.Agree> { bean ->
                val agree = (hasAgree xor 1) == 1
                // HTTP 200 不等于业务成功:先按 errorCode 做三态判定,再决定要不要写记录
                when (val result = bean.toOpAgreeResult(threadId, agree)) {
                    // Ok 不写记录——Start 的 setPending 已表达意图,此刻 confirm 会抵消乐观偏移
                    is OpAgreeResult.Ok -> {
                        undoDisagreeIfAccepted()
                        TopicDetailPartialChange.Agree.Success(
                            threadId,
                            hasAgree xor 1
                        )
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
                        TopicDetailPartialChange.Agree.Success(
                            threadId,
                            hasAgree xor 1
                        )
                    }

                    is OpAgreeResult.Business -> {
                        // 普通业务拒绝:不 confirm(否则 my=server 落盘永不自愈),
                        // 回滚统一交给 dispatchEvent(与网络失败同路)
                        TopicDetailPartialChange.Agree.Failure(
                            threadId,
                            hasAgree,
                            TiebaApiException(
                                CommonResponse(result.code.toIntOrNull() ?: CommonResponse.ERROR_CODE_UNKNOWN, result.msg)
                            )
                        )
                    }
                }
            }
                .catch {
                    // 权威码异常路径采纳,收尾半截修复(R8 裁决 12,同 ForumThreadList 注释)
                    val authoritative = serverOpFromErrorMessage(it.getErrorMessage())
                    if (authoritative == null) {
                        emit(TopicDetailPartialChange.Agree.Failure(threadId, hasAgree, it))
                    } else {
                        OpRecordStore.confirm(App.INSTANCE, AgreeParams.OBJ_THREAD, threadId, authoritative)
                        undoDisagreeIfAccepted()
                        emit(TopicDetailPartialChange.Agree.Success(threadId, hasAgree xor 1))
                    }
                }
                .onStart { if (acquired) emit(TopicDetailPartialChange.Agree.Start(threadId, hasAgree xor 1)) }
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
                hasMore = hasMore,
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
                hasMore = hasMore,
                topicInfo = topicInfo,
                relateForum = relateForum.distinctBy { it.forumId }.toImmutableList(),
                relateThread = relateThread.distinctBy { it.feedId }.toImmutableList(),
            )

            is Failure -> oldState.copy(isRefreshing = false)
        }

        object Start : Refresh()

        data class Success(
            val hasMore: Boolean,
            val topicInfo: TopicInfoBean,
            val relateForum: List<RelateForumBean>,
            val relateThread: List<ThreadBean>
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
    val relateThread: ImmutableList<ThreadBean> = persistentListOf()
) : UiState
