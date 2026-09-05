package com.huanchengfly.tieba.post.ui.page.main.explore.concern

import androidx.compose.runtime.Stable
import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.api.AgreeParams
import com.huanchengfly.tieba.post.api.AgreeRateLimiter
import com.huanchengfly.tieba.post.api.TiebaApi
import com.huanchengfly.tieba.post.api.TiebaRateLimitedException
import com.huanchengfly.tieba.post.api.models.AgreeBean
import com.huanchengfly.tieba.post.api.models.CommonResponse
import com.huanchengfly.tieba.post.api.models.protos.MyAgreeOp
import com.huanchengfly.tieba.post.api.models.protos.OpAgreeResult
import com.huanchengfly.tieba.post.api.models.protos.serverOpFromErrorCode
import com.huanchengfly.tieba.post.api.models.protos.serverOpFromErrorMessage
import com.huanchengfly.tieba.post.api.models.protos.toOpAgreeResult
import com.huanchengfly.tieba.post.api.models.protos.userLike.ConcernData
import com.huanchengfly.tieba.post.api.models.protos.userLike.UserLikeResponse
import com.huanchengfly.tieba.post.api.retrofit.exception.TiebaApiException
import com.huanchengfly.tieba.post.api.retrofit.exception.getErrorMessage
import com.huanchengfly.tieba.post.utils.OpRecordStore
import com.huanchengfly.tieba.post.arch.BaseViewModel
import com.huanchengfly.tieba.post.arch.CommonUiEvent
import com.huanchengfly.tieba.post.arch.PartialChange
import com.huanchengfly.tieba.post.arch.PartialChangeProducer
import com.huanchengfly.tieba.post.arch.UiEvent
import com.huanchengfly.tieba.post.arch.UiIntent
import com.huanchengfly.tieba.post.arch.UiState
import com.huanchengfly.tieba.post.utils.appPreferences
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
class ConcernViewModel @Inject constructor() :
    BaseViewModel<ConcernUiIntent, ConcernPartialChange, ConcernUiState, ConcernUiEvent>() {
    override fun createInitialState(): ConcernUiState = ConcernUiState()

    override fun createPartialChangeProducer(): PartialChangeProducer<ConcernUiIntent, ConcernPartialChange, ConcernUiState> =
        ExplorePartialChangeProducer

    override fun dispatchEvent(partialChange: ConcernPartialChange): UiEvent? =
        when (partialChange) {
            is ConcernPartialChange.Refresh.Failure -> CommonUiEvent.Toast(partialChange.error.getErrorMessage())
            is ConcernPartialChange.LoadMore.Failure -> CommonUiEvent.Toast(partialChange.error.getErrorMessage())

            is ConcernPartialChange.Agree.Start -> {
                // 乐观意图进记录表;显示数字/亮灯由 FeedCard.ThreadAgreeBtn 从 records 推导
                OpRecordStore.setPending(
                    App.INSTANCE,
                    AgreeParams.OBJ_THREAD,
                    partialChange.threadId,
                    if (partialChange.hasAgree == 1) MyAgreeOp.AGREE else MyAgreeOp.NONE
                )
                null
            }

            is ConcernPartialChange.Agree.Failure -> {
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
            is ConcernPartialChange.Refresh.Success -> {
                rebaseLoaded(partialChange.data); null
            }

            is ConcernPartialChange.LoadMore.Success -> {
                rebaseLoaded(partialChange.data); null
            }

            else -> null
        }

    private fun rebaseLoaded(data: List<ConcernData>) {
        val keys = HashSet<String>(data.size)
        data.forEach { item ->
            val tid = item.threadList?.threadId ?: return@forEach
            keys.add(OpRecordStore.key(AgreeParams.OBJ_THREAD, tid))
        }
        OpRecordStore.rebase(App.INSTANCE, keys)
    }

    private object ExplorePartialChangeProducer : PartialChangeProducer<ConcernUiIntent, ConcernPartialChange, ConcernUiState> {
        @OptIn(ExperimentalCoroutinesApi::class)
        override fun toPartialChangeFlow(intentFlow: Flow<ConcernUiIntent>): Flow<ConcernPartialChange> =
            merge(
                intentFlow.filterIsInstance<ConcernUiIntent.Refresh>().flatMapConcat { produceRefreshPartialChange() },
                intentFlow.filterIsInstance<ConcernUiIntent.LoadMore>().flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<ConcernUiIntent.Agree>().flatMapConcat { it.producePartialChange() },
            )

        private fun produceRefreshPartialChange(): Flow<ConcernPartialChange.Refresh> =
            TiebaApi.getInstance().userLikeFlow("", App.INSTANCE.appPreferences.userLikeLastRequestUnix, 1)
                .map<UserLikeResponse, ConcernPartialChange.Refresh> {
                    App.INSTANCE.appPreferences.userLikeLastRequestUnix = it.data_?.requestUnix ?: 0L
                    ConcernPartialChange.Refresh.Success(
                        data = it.toData(),
                        hasMore = it.data_?.hasMore == 1,
                        nextPageTag = it.data_?.pageTag ?: ""
                    )
                }
                .onStart { emit(ConcernPartialChange.Refresh.Start) }
                .catch { emit(ConcernPartialChange.Refresh.Failure(it)) }

        private fun ConcernUiIntent.LoadMore.producePartialChange(): Flow<ConcernPartialChange.LoadMore> =
            TiebaApi.getInstance().userLikeFlow(pageTag, App.INSTANCE.appPreferences.userLikeLastRequestUnix, 2)
                .map<UserLikeResponse, ConcernPartialChange.LoadMore> {
                    ConcernPartialChange.LoadMore.Success(
                        data = it.toData(),
                        hasMore = it.data_?.hasMore == 1,
                        nextPageTag = it.data_?.pageTag ?: ""
                    )
                }
                .onStart { emit(ConcernPartialChange.LoadMore.Start) }
                .catch { emit(ConcernPartialChange.LoadMore.Failure(error = it)) }

        private fun ConcernUiIntent.Agree.producePartialChange(): Flow<ConcernPartialChange.Agree> {
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
            }.map<AgreeBean, ConcernPartialChange.Agree> { bean ->
                val agree = (hasAgree xor 1) == 1
                // HTTP 200 不等于业务成功:与帖子页/其他列表页同构,先按 errorCode 三态判定
                when (val result = bean.toOpAgreeResult(threadId, agree)) {
                    // Ok 不写记录——Start 的 setPending 已表达意图,此刻 confirm 会抵消乐观偏移
                    is OpAgreeResult.Ok -> {
                        undoDisagreeIfAccepted()
                        ConcernPartialChange.Agree.Success(threadId, hasAgree xor 1)
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
                        ConcernPartialChange.Agree.Success(threadId, hasAgree xor 1)
                    }

                    is OpAgreeResult.Business -> {
                        // 普通业务拒绝:不 confirm,回滚统一交给 dispatchEvent(与网络失败同路)
                        ConcernPartialChange.Agree.Failure(
                            threadId,
                            hasAgree,
                            TiebaApiException(
                                CommonResponse(
                                    result.code.toIntOrNull() ?: CommonResponse.ERROR_CODE_UNKNOWN,
                                    result.msg
                                )
                            )
                        )
                    }
                }
            }
                .catch {
                    // 权威码异常路径采纳,收尾半截修复(R8 裁决 12,同 ForumThreadList 注释)
                    val authoritative = serverOpFromErrorMessage(it.getErrorMessage())
                    if (authoritative == null) {
                        emit(ConcernPartialChange.Agree.Failure(threadId, hasAgree, it))
                    } else {
                        OpRecordStore.confirm(App.INSTANCE, AgreeParams.OBJ_THREAD, threadId, authoritative)
                        undoDisagreeIfAccepted()
                        emit(ConcernPartialChange.Agree.Success(threadId, hasAgree xor 1))
                    }
                }
                .onStart { if (acquired) emit(ConcernPartialChange.Agree.Start(threadId, hasAgree xor 1)) }
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