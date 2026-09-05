package com.huanchengfly.tieba.post.ui.page.forum.threadlist

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
import com.huanchengfly.tieba.post.api.models.protos.frsPage.Classify
import com.huanchengfly.tieba.post.api.models.protos.frsPage.FrsPageResponse
import com.huanchengfly.tieba.post.api.retrofit.exception.TiebaApiException
import com.huanchengfly.tieba.post.api.retrofit.exception.TiebaUnknownException
import com.huanchengfly.tieba.post.api.retrofit.exception.getErrorCode
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
import com.huanchengfly.tieba.post.repository.FrsPageRepository
import com.huanchengfly.tieba.post.ui.models.ThreadItemData
import com.huanchengfly.tieba.post.ui.models.distinctById
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
import kotlin.math.min

abstract class ForumThreadListViewModel :
    BaseViewModel<ForumThreadListUiIntent, ForumThreadListPartialChange, ForumThreadListUiState, ForumThreadListUiEvent>() {
    override fun createInitialState(): ForumThreadListUiState = ForumThreadListUiState()

    override fun dispatchEvent(partialChange: ForumThreadListPartialChange): UiEvent? =
        when (partialChange) {
            is ForumThreadListPartialChange.FirstLoad.Failure -> CommonUiEvent.Toast(partialChange.error.getErrorMessage())
            is ForumThreadListPartialChange.Refresh.Failure -> CommonUiEvent.Toast(partialChange.error.getErrorMessage())
            is ForumThreadListPartialChange.LoadMore.Failure -> CommonUiEvent.Toast(partialChange.error.getErrorMessage())
            is ForumThreadListPartialChange.Agree.Start -> {
                // 乐观意图进记录表(与帖子页同一张表、同一键式);显示数字/亮灯由
                // FeedCard.ThreadAgreeBtn 统一从 records 推导,reducer 不再改 proto 计数
                OpRecordStore.setPending(
                    App.INSTANCE,
                    AgreeParams.OBJ_THREAD,
                    partialChange.threadId,
                    if (partialChange.hasAgree == 1) MyAgreeOp.AGREE else MyAgreeOp.NONE
                )
                null
            }

            is ForumThreadListPartialChange.Agree.Failure -> {
                // 限流拦截的异常不是 TiebaException,getErrorCode() 会退化成 -1,
                // 必须按类型判定:被拦截的请求从未 setPending,绝不 revertPending
                // (否则凭空写出 my=server=NONE 的记录,永久屏蔽服务端回显)
                if (partialChange.error !is TiebaRateLimitedException) {
                    OpRecordStore.revertPending(
                        App.INSTANCE,
                        AgreeParams.OBJ_THREAD,
                        partialChange.threadId
                    )
                }
                ForumThreadListUiEvent.AgreeFail(
                    partialChange.threadId,
                    partialChange.postId,
                    partialChange.hasAgree,
                    if (partialChange.error is TiebaRateLimitedException)
                        AgreeParams.RATE_LIMIT_ERROR_CODE
                    else partialChange.error.getErrorCode(),
                    partialChange.error.getErrorMessage()
                )
            }

            // 列表重载后:本次返回的 agreeNum 基准已包含已确认操作,
            // 把已有记录的对齐标记对齐到意图,避免"刷新后重复叠加 delta"
            is ForumThreadListPartialChange.FirstLoad.Success -> {
                rebaseLoaded(partialChange.threadList); null
            }

            is ForumThreadListPartialChange.Refresh.Success -> {
                rebaseLoaded(partialChange.threadList); null
            }

            is ForumThreadListPartialChange.LoadMore.Success -> {
                rebaseLoaded(partialChange.threadList); null
            }

            else -> null
        }

    /** 只对齐本次重载实际涉及的对象(未重载的历史记录不动,防止跨对象污染) */
    private fun rebaseLoaded(threadList: List<ThreadItemData>) {
        val keys = HashSet<String>(threadList.size)
        threadList.forEach {
            keys.add(OpRecordStore.key(AgreeParams.OBJ_THREAD, it.thread.get { threadId }))
        }
        OpRecordStore.rebase(App.INSTANCE, keys)
    }
}

enum class ForumThreadListType {
    Latest, Good
}

@Stable
@HiltViewModel
class LatestThreadListViewModel @Inject constructor() : ForumThreadListViewModel() {
    override fun createPartialChangeProducer(): PartialChangeProducer<ForumThreadListUiIntent, ForumThreadListPartialChange, ForumThreadListUiState> =
        ForumThreadListPartialChangeProducer(ForumThreadListType.Latest)
}

@Stable
@HiltViewModel
class GoodThreadListViewModel @Inject constructor() : ForumThreadListViewModel() {
    override fun createPartialChangeProducer(): PartialChangeProducer<ForumThreadListUiIntent, ForumThreadListPartialChange, ForumThreadListUiState> =
        ForumThreadListPartialChangeProducer(ForumThreadListType.Good)
}

private class ForumThreadListPartialChangeProducer(val type: ForumThreadListType) :
    PartialChangeProducer<ForumThreadListUiIntent, ForumThreadListPartialChange, ForumThreadListUiState> {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun toPartialChangeFlow(intentFlow: Flow<ForumThreadListUiIntent>): Flow<ForumThreadListPartialChange> =
        merge(
            intentFlow.filterIsInstance<ForumThreadListUiIntent.FirstLoad>()
                .flatMapConcat { it.producePartialChange() },
            intentFlow.filterIsInstance<ForumThreadListUiIntent.Refresh>()
                .flatMapConcat { it.producePartialChange() },
            intentFlow.filterIsInstance<ForumThreadListUiIntent.LoadMore>()
                .flatMapConcat { it.producePartialChange() },
            intentFlow.filterIsInstance<ForumThreadListUiIntent.Agree>()
                .flatMapConcat { it.producePartialChange() },
        )

    private fun ForumThreadListUiIntent.FirstLoad.producePartialChange() =
        FrsPageRepository.frsPage(
            forumName,
            1,
            1,
            sortType.takeIf { type == ForumThreadListType.Latest } ?: -1,
            goodClassifyId.takeIf { type == ForumThreadListType.Good }
        )
            .map<FrsPageResponse, ForumThreadListPartialChange.FirstLoad> { response ->
                if (response.data_?.page == null) throw TiebaUnknownException
                val threadList =
                    response.data_.thread_list.map { ThreadItemData(it.wrapImmutable()) }
                ForumThreadListPartialChange.FirstLoad.Success(
                    response.data_.forum_rule?.title.takeIf {
                        type == ForumThreadListType.Latest && response.data_.forum_rule?.has_forum_rule == 1
                    },
                    threadList,
                    response.data_.thread_id_list,
                    (response.data_.forum?.good_classify ?: emptyList()).wrapImmutable(),
                    goodClassifyId.takeIf { type == ForumThreadListType.Good },
                    response.data_.page.has_more == 1
                )
            }
            .onStart { emit(ForumThreadListPartialChange.FirstLoad.Start) }
            .catch { emit(ForumThreadListPartialChange.FirstLoad.Failure(it)) }

    private fun ForumThreadListUiIntent.Refresh.producePartialChange() =
        FrsPageRepository.frsPage(
            forumName,
            1,
            1,
            sortType.takeIf { type == ForumThreadListType.Latest } ?: -1,
            goodClassifyId.takeIf { type == ForumThreadListType.Good },
            forceNew = true
        )
            .map<FrsPageResponse, ForumThreadListPartialChange.Refresh> { response ->
                if (response.data_?.page == null) throw TiebaUnknownException
                val threadList =
                    response.data_.thread_list.map { ThreadItemData(it.wrapImmutable()) }
                ForumThreadListPartialChange.Refresh.Success(
                    threadList,
                    response.data_.thread_id_list,
                    (response.data_.forum?.good_classify ?: emptyList()).wrapImmutable(),
                    goodClassifyId.takeIf { type == ForumThreadListType.Good },
                    response.data_.page.has_more == 1,
                    preserveList = preserveList
                )
            }
            .onStart { emit(ForumThreadListPartialChange.Refresh.Start) }
            .catch { emit(ForumThreadListPartialChange.Refresh.Failure(it)) }

    private fun ForumThreadListUiIntent.LoadMore.producePartialChange(): Flow<ForumThreadListPartialChange.LoadMore> {
        val flow = if (threadListIds.isNotEmpty()) {
            val size = min(threadListIds.size, 30)
            FrsPageRepository.threadList(
                forumId,
                forumName,
                currentPage,
                sortType,
                threadListIds.subList(0, size).joinToString(separator = ",") { "$it" }
            ).map { response ->
                if (response.data_ == null) throw TiebaUnknownException
                val threadList =
                    response.data_.thread_list.map { ThreadItemData(it.wrapImmutable()) }
                ForumThreadListPartialChange.LoadMore.Success(
                    threadList = threadList,
                    threadListIds = threadListIds.drop(size),
                    currentPage = currentPage,
                    hasMore = response.data_.thread_list.isNotEmpty()
                )
            }
        } else {
            FrsPageRepository.frsPage(
                forumName,
                currentPage + 1,
                2,
                sortType.takeIf { type == ForumThreadListType.Latest } ?: -1,
                goodClassifyId.takeIf { type == ForumThreadListType.Good }
            )
                .map<FrsPageResponse, ForumThreadListPartialChange.LoadMore> { response ->
                    if (response.data_?.page == null) throw TiebaUnknownException
                    val threadList =
                        response.data_.thread_list.map { ThreadItemData(it.wrapImmutable()) }
                    ForumThreadListPartialChange.LoadMore.Success(
                        threadList = threadList,
                        threadListIds = response.data_.thread_id_list,
                        currentPage = currentPage + 1,
                        response.data_.page.has_more == 1
                    )
                }
        }
        return flow
            .onStart { emit(ForumThreadListPartialChange.LoadMore.Start) }
            .catch { emit(ForumThreadListPartialChange.LoadMore.Failure(it)) }
    }

    private fun ForumThreadListUiIntent.Agree.producePartialChange(): Flow<ForumThreadListPartialChange.Agree> {
        // 限流预检在任何状态变更之前(与帖子页同构):被拦截的请求不产生 Start,
        // 记录表零变更,只发一个带 TiebaRateLimitedException 的 Failure 去弹 Toast
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
                    threadId.toString(),
                    postId.toString(),
                    hasAgree,
                    objType = 3
                )
            )
        }.map<AgreeBean, ForumThreadListPartialChange.Agree> { bean ->
            val agree = (hasAgree xor 1) == 1
            // HTTP 200 不等于业务成功:先按 errorCode 做三态判定,再决定要不要写记录
            when (val result = bean.toOpAgreeResult(threadId, agree)) {
                // 不变量(与帖子页差分模型一致):Ok 不写记录——Start 的 setPending 已表达意图,
                // 此刻 confirm 会抵消乐观偏移(列表基准确实未含本次操作,数字会弹回)
                is OpAgreeResult.Ok -> {
                    undoDisagreeIfAccepted()
                    ForumThreadListPartialChange.Agree.Success(
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
                    ForumThreadListPartialChange.Agree.Success(
                        threadId,
                        hasAgree xor 1
                    )
                }

                is OpAgreeResult.Business -> {
                    // 普通业务拒绝:不 confirm(否则 my=server 落盘永不自愈),
                    // 回滚统一交给 dispatchEvent(与网络失败同路)
                    ForumThreadListPartialChange.Agree.Failure(
                        threadId,
                        postId,
                        hasAgree,
                        TiebaApiException(
                            CommonResponse(result.code.toIntOrNull() ?: CommonResponse.ERROR_CODE_UNKNOWN, result.msg)
                        )
                    )
                }
            }
        }
            .catch {
                // 数字 error_code 经 FailureResponseInterceptor 抛异常时 .map 不被调用;
                // 服务端权威陈述(如"你已赞过")必须采纳而非回滚——与 Thread/SubPosts 同口径,
                // 收尾半截修复(R8 裁决 12)。限流异常消息不匹配任何权威模式,仍走普通失败
                val authoritative = serverOpFromErrorMessage(it.getErrorMessage())
                if (authoritative == null) {
                    emit(
                        ForumThreadListPartialChange.Agree.Failure(
                            threadId,
                            postId,
                            hasAgree,
                            it
                        )
                    )
                } else {
                    OpRecordStore.confirm(App.INSTANCE, AgreeParams.OBJ_THREAD, threadId, authoritative)
                    undoDisagreeIfAccepted()
                    emit(ForumThreadListPartialChange.Agree.Success(threadId, hasAgree xor 1))
                }
            }
            .onStart { if (acquired) emit(ForumThreadListPartialChange.Agree.Start(threadId, hasAgree xor 1)) }
    }
}

sealed interface ForumThreadListUiIntent : UiIntent {
    data class FirstLoad(
        val forumName: String,
        val sortType: Int = -1,
        val goodClassifyId: Int? = null,
    ) : ForumThreadListUiIntent

    data class Refresh(
        val forumName: String,
        val sortType: Int = -1,
        val goodClassifyId: Int? = null,
        // true = 下拉/FAB 手动刷新:新帖合并到顶部、旧列表保留在后,浏览位置不被顶走
        // false(默认) = 排序/分区切换:整表替换,回到顶部是预期行为
        val preserveList: Boolean = false,
    ) : ForumThreadListUiIntent

    data class LoadMore(
        val forumId: Long,
        val forumName: String,
        val currentPage: Int,
        val threadListIds: List<Long>,
        val sortType: Int = -1,
        val goodClassifyId: Int? = null,
    ) : ForumThreadListUiIntent

    data class Agree(
        val threadId: Long,
        val postId: Long,
        val hasAgree: Int
    ) : ForumThreadListUiIntent
}

sealed interface ForumThreadListPartialChange : PartialChange<ForumThreadListUiState> {
    sealed class FirstLoad : ForumThreadListPartialChange {
        override fun reduce(oldState: ForumThreadListUiState): ForumThreadListUiState =
            when (this) {
                Start -> oldState
                is Success -> oldState.copy(
                    isRefreshing = false,
                    forumRuleTitle = forumRuleTitle,
                    threadList = threadList.distinctById(),
                    threadListIds = threadListIds.toImmutableList(),
                    goodClassifies = goodClassifies.toImmutableList(),
                    goodClassifyId = goodClassifyId,
                    currentPage = 1,
                    hasMore = hasMore
                )

                is Failure -> oldState.copy(isRefreshing = false)
            }

        data object Start : FirstLoad()

        data class Success(
            val forumRuleTitle: String?,
            val threadList: List<ThreadItemData>,
            val threadListIds: List<Long>,
            val goodClassifies: List<ImmutableHolder<Classify>>,
            val goodClassifyId: Int?,
            val hasMore: Boolean,
        ) : FirstLoad()

        data class Failure(
            val error: Throwable
        ) : FirstLoad()
    }

    sealed class Refresh : ForumThreadListPartialChange {
        override fun reduce(oldState: ForumThreadListUiState): ForumThreadListUiState =
            when (this) {
                Start -> oldState.copy(isRefreshing = true)
                is Success -> oldState.copy(
                    isRefreshing = false,
                    // preserveList(下拉/FAB 刷新):新页在前、旧列表在后去重合并,
                    // 已加载的历史页与用户浏览位置得以保留;否则整表替换(排序/分区切换)
                    threadList = (if (preserveList) threadList + oldState.threadList else threadList)
                        .distinctById(),
                    // 保位刷新时分页游标沿用旧值、id 队列清空——LoadMore 从旧流的第 N+1 页
                    // 续拉。若沿用新页队列+currentPage=1,会重放旧表已加载的第 2..N 页,
                    // distinctById 虽兜底去重,但加载条会空转 N-1 次(R4-F1)
                    threadListIds = if (preserveList) persistentListOf() else threadListIds.toImmutableList(),
                    goodClassifies = goodClassifies.toImmutableList(),
                    goodClassifyId = goodClassifyId,
                    currentPage = if (preserveList) oldState.currentPage else 1,
                    hasMore = hasMore
                )

                is Failure -> oldState.copy(isRefreshing = false)
            }

        data object Start : Refresh()

        data class Success(
            val threadList: List<ThreadItemData>,
            val threadListIds: List<Long>,
            val goodClassifies: List<ImmutableHolder<Classify>>,
            val goodClassifyId: Int? = null,
            val hasMore: Boolean,
            val preserveList: Boolean = false,
        ) : Refresh()

        data class Failure(
            val error: Throwable
        ) : Refresh()
    }

    sealed class LoadMore : ForumThreadListPartialChange {
        override fun reduce(oldState: ForumThreadListUiState): ForumThreadListUiState =
            when (this) {
                Start -> oldState.copy(isLoadingMore = true)
                is Success -> oldState.copy(
                    isLoadingMore = false,
                    threadList = (oldState.threadList + threadList).distinctById(),
                    threadListIds = threadListIds.toImmutableList(),
                    currentPage = currentPage,
                    hasMore = hasMore
                )

                is Failure -> oldState.copy(isLoadingMore = false)
            }

        data object Start : LoadMore()

        data class Success(
            val threadList: List<ThreadItemData>,
            val threadListIds: List<Long>,
            val currentPage: Int,
            val hasMore: Boolean,
        ) : LoadMore()

        data class Failure(
            val error: Throwable
        ) : LoadMore()
    }

    sealed class Agree private constructor() : ForumThreadListPartialChange {
        // 差分模型:显示数字与亮灯由 FeedCard.ThreadAgreeBtn 从 OpRecordStore.records 推导,
        // reducer 不再改写 proto 的 agreeNum/hasAgree——那是一切计数漂移的根源。
        // 记录更新(setPending/confirm/revertPending)全部集中在 dispatchEvent。
        override fun reduce(oldState: ForumThreadListUiState): ForumThreadListUiState =
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
            val postId: Long,
            val hasAgree: Int,
            val error: Throwable
        ) : Agree()
    }
}

data class ForumThreadListUiState(
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val goodClassifyId: Int? = null,
    val forumRuleTitle: String? = null,
    val threadList: ImmutableList<ThreadItemData> = persistentListOf(),
    val threadListIds: ImmutableList<Long> = persistentListOf(),
    val goodClassifies: ImmutableList<ImmutableHolder<Classify>> = persistentListOf(),
    val currentPage: Int = 1,
    val hasMore: Boolean = true,
) : UiState

sealed interface ForumThreadListUiEvent : UiEvent {
    data class AgreeFail(
        val threadId: Long,
        val postId: Long,
        val hasAgree: Int,
        val errorCode: Int,
        val errorMsg: String
    ) : ForumThreadListUiEvent

    data class Refresh(
        val isGood: Boolean,
        val sortType: Int,
        // 透传给 ForumThreadListUiIntent.Refresh.preserveList(下拉/FAB 刷新 = true)
        val preserveList: Boolean = false,
    ) : ForumThreadListUiEvent

    data class BackToTop(
        val isGood: Boolean
    ) : ForumThreadListUiEvent

    data class AddThread(
        val forumName: String,
    ):ForumThreadListUiEvent
}