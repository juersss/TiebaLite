package com.huanchengfly.tieba.post.ui.page.forum.generaltablist

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
import com.huanchengfly.tieba.post.api.models.protos.FrsTabInfo
import com.huanchengfly.tieba.post.api.models.protos.GeneralTabList.GeneralTabListResponse
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
import com.huanchengfly.tieba.post.repository.GeneralTabListRepository
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

@Stable
@HiltViewModel
class GeneralTabListViewModel @Inject constructor() :
    BaseViewModel<GeneralTabListUiIntent, GeneralTabListPartialChange, GeneralTabListUiState, GeneralTabListUiEvent>() {
    override fun createInitialState(): GeneralTabListUiState = GeneralTabListUiState()

    override fun createPartialChangeProducer(): PartialChangeProducer<GeneralTabListUiIntent, GeneralTabListPartialChange, GeneralTabListUiState> =
        GeneralTabListPartialChangeProducer

    override fun dispatchEvent(partialChange: GeneralTabListPartialChange): UiEvent? =
        when (partialChange) {
            is GeneralTabListPartialChange.FirstLoad.Failure -> CommonUiEvent.Toast(partialChange.error.getErrorMessage())
            is GeneralTabListPartialChange.Refresh.Failure -> CommonUiEvent.Toast(partialChange.error.getErrorMessage())
            is GeneralTabListPartialChange.LoadMore.Failure -> CommonUiEvent.Toast(partialChange.error.getErrorMessage())
            is GeneralTabListPartialChange.Agree.Start -> {
                // 乐观意图进记录表;显示数字/亮灯由 FeedCard.ThreadAgreeBtn 从 records 推导
                OpRecordStore.setPending(
                    App.INSTANCE,
                    AgreeParams.OBJ_THREAD,
                    partialChange.threadId,
                    if (partialChange.hasAgree == 1) MyAgreeOp.AGREE else MyAgreeOp.NONE
                )
                null
            }
            is GeneralTabListPartialChange.Agree.Failure -> {
                // 限流异常不是 TiebaException,getErrorCode() 会退化成 -1——按类型判定;
                // 被拦截的请求从未 setPending,绝不 revertPending(否则会凭空写出
                // my=server=NONE 的记录,永久屏蔽服务端回显)
                if (partialChange.error !is TiebaRateLimitedException) {
                    OpRecordStore.revertPending(
                        App.INSTANCE,
                        AgreeParams.OBJ_THREAD,
                        partialChange.threadId
                    )
                }
                GeneralTabListUiEvent.AgreeFail(
                    partialChange.threadId,
                    partialChange.postId,
                    partialChange.hasAgree,
                    if (partialChange.error is TiebaRateLimitedException)
                        AgreeParams.RATE_LIMIT_ERROR_CODE
                    else partialChange.error.getErrorCode(),
                    partialChange.error.getErrorMessage()
                )
            }
            // 列表重载:本次返回的 agreeNum 基准已包含已确认操作,对齐标记跟进意图
            is GeneralTabListPartialChange.FirstLoad.Success -> {
                rebaseLoaded(partialChange.threadList); null
            }
            is GeneralTabListPartialChange.Refresh.Success -> {
                rebaseLoaded(partialChange.threadList); null
            }
            is GeneralTabListPartialChange.LoadMore.Success -> {
                rebaseLoaded(partialChange.threadList); null
            }
            else -> null
        }

    private fun rebaseLoaded(threadList: List<ThreadItemData>) {
        val keys = HashSet<String>(threadList.size)
        threadList.forEach {
            keys.add(OpRecordStore.key(AgreeParams.OBJ_THREAD, it.thread.get { threadId }))
        }
        OpRecordStore.rebase(App.INSTANCE, keys)
    }
}

private object GeneralTabListPartialChangeProducer :
    PartialChangeProducer<GeneralTabListUiIntent, GeneralTabListPartialChange, GeneralTabListUiState> {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun toPartialChangeFlow(intentFlow: Flow<GeneralTabListUiIntent>): Flow<GeneralTabListPartialChange> =
        merge(
            intentFlow.filterIsInstance<GeneralTabListUiIntent.FirstLoad>()
                .flatMapConcat { it.producePartialChange() },
            intentFlow.filterIsInstance<GeneralTabListUiIntent.Refresh>()
                .flatMapConcat { it.producePartialChange() },
            intentFlow.filterIsInstance<GeneralTabListUiIntent.LoadMore>()
                .flatMapConcat { it.producePartialChange() },
            intentFlow.filterIsInstance<GeneralTabListUiIntent.Agree>()
                .flatMapConcat { it.producePartialChange() },
        )

    private fun GeneralTabListUiIntent.FirstLoad.producePartialChange() =
        GeneralTabListRepository.generalTabList(
            forumId = forumId,
            forumName = forumName,
            tabId = navTabInfo.tabId,
            tabType = navTabInfo.tabType,
            tabName = navTabInfo.tabName,
            isGeneralTab = navTabInfo.isGeneralTab,
            pn = 1,
            sortType = this.sortType,
            lastThreadId = 0,
            isDefaultNavTab = navTabInfo.isDefault,
        ).map<GeneralTabListResponse, GeneralTabListPartialChange.FirstLoad> { response ->
            if (response.data_ == null) throw TiebaUnknownException
            val threadList = response.data_.general_list.map { ThreadItemData(it.wrapImmutable()) }
            GeneralTabListPartialChange.FirstLoad.Success(
                threadList = threadList,
                hasMore = response.data_.has_more == 1,
                lastThreadId = response.data_.general_list.lastOrNull()?.id ?: 0,
                sortType = sortType,
            )
        }
            .onStart { emit(GeneralTabListPartialChange.FirstLoad.Start) }
            .catch { emit(GeneralTabListPartialChange.FirstLoad.Failure(it)) }

    private fun GeneralTabListUiIntent.Refresh.producePartialChange() =
        GeneralTabListRepository.generalTabList(
            forumId = forumId,
            forumName = forumName,
            tabId = navTabInfo.tabId,
            tabType = navTabInfo.tabType,
            tabName = navTabInfo.tabName,
            isGeneralTab = navTabInfo.isGeneralTab,
            pn = 1,
            sortType = this.sortType,
            lastThreadId = 0,
            isDefaultNavTab = navTabInfo.isDefault,
            forceNew = true,
        ).map<GeneralTabListResponse, GeneralTabListPartialChange.Refresh> { response ->
            if (response.data_ == null) throw TiebaUnknownException
            val threadList = response.data_.general_list.map { ThreadItemData(it.wrapImmutable()) }
            GeneralTabListPartialChange.Refresh.Success(
                threadList = threadList,
                hasMore = response.data_.has_more == 1,
                lastThreadId = response.data_.general_list.lastOrNull()?.id ?: 0,
                sortType = sortType,
            )
        }
            .onStart { emit(GeneralTabListPartialChange.Refresh.Start) }
            .catch { emit(GeneralTabListPartialChange.Refresh.Failure(it)) }

    private fun GeneralTabListUiIntent.LoadMore.producePartialChange() =
        GeneralTabListRepository.generalTabList(
            forumId = forumId,
            forumName = forumName,
            tabId = navTabInfo.tabId,
            tabType = navTabInfo.tabType,
            tabName = navTabInfo.tabName,
            isGeneralTab = navTabInfo.isGeneralTab,
            pn = currentPage + 1,
            sortType = this.sortType,
            lastThreadId = lastThreadId,
            isDefaultNavTab = navTabInfo.isDefault,
        ).map<GeneralTabListResponse, GeneralTabListPartialChange.LoadMore> { response ->
            if (response.data_ == null) throw TiebaUnknownException
            val threadList = response.data_.general_list.map { ThreadItemData(it.wrapImmutable()) }
            GeneralTabListPartialChange.LoadMore.Success(
                threadList = threadList,
                hasMore = (response.data_.has_more == 1) && threadList.isNotEmpty(),
                currentPage = currentPage + 1,
                lastThreadId = response.data_.general_list.lastOrNull()?.id ?: lastThreadId,
            )
        }
            .onStart { emit(GeneralTabListPartialChange.LoadMore.Start) }
            .catch { emit(GeneralTabListPartialChange.LoadMore.Failure(it)) }

    private fun GeneralTabListUiIntent.Agree.producePartialChange(): Flow<GeneralTabListPartialChange.Agree> {
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
                    threadId.toString(),
                    postId.toString(),
                    hasAgree,
                    objType = 3
                )
            )
        }.map<AgreeBean, GeneralTabListPartialChange.Agree> { bean ->
            val agree = (hasAgree xor 1) == 1
            // HTTP 200 不等于业务成功:先按 errorCode 做三态判定,再决定要不要写记录
            when (val result = bean.toOpAgreeResult(threadId, agree)) {
                // Ok 不写记录——Start 的 setPending 已表达意图,此刻 confirm 会抵消乐观偏移
                is OpAgreeResult.Ok -> {
                    undoDisagreeIfAccepted()
                    GeneralTabListPartialChange.Agree.Success(
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
                    GeneralTabListPartialChange.Agree.Success(
                        threadId,
                        hasAgree xor 1
                    )
                }

                is OpAgreeResult.Business -> {
                    // 普通业务拒绝:不 confirm(否则 my=server 落盘永不自愈),
                    // 回滚统一交给 dispatchEvent(与网络失败同路)
                    GeneralTabListPartialChange.Agree.Failure(
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
                // 服务端权威陈述必须采纳而非回滚(同口径收尾,R8 裁决 12)
                val authoritative = serverOpFromErrorMessage(it.getErrorMessage())
                if (authoritative == null) {
                    emit(
                        GeneralTabListPartialChange.Agree.Failure(
                            threadId,
                            postId,
                            hasAgree,
                            it
                        )
                    )
                } else {
                    OpRecordStore.confirm(App.INSTANCE, AgreeParams.OBJ_THREAD, threadId, authoritative)
                    undoDisagreeIfAccepted()
                    emit(GeneralTabListPartialChange.Agree.Success(threadId, hasAgree xor 1))
                }
            }
            .onStart { if (acquired) emit(GeneralTabListPartialChange.Agree.Start(threadId, hasAgree xor 1)) }
    }
}

sealed interface GeneralTabListUiIntent : UiIntent {
    data class FirstLoad(
        val forumId: Long,
        val forumName: String,
        val navTabInfo: FrsTabInfo,
        val sortType: Int = -1,
    ) : GeneralTabListUiIntent

    data class Refresh(
        val forumId: Long,
        val forumName: String,
        val navTabInfo: FrsTabInfo,
        val sortType: Int = -1,
    ) : GeneralTabListUiIntent

    data class LoadMore(
        val forumId: Long,
        val forumName: String,
        val navTabInfo: FrsTabInfo,
        val currentPage: Int,
        val lastThreadId: Long,
        val sortType: Int = -1,
    ) : GeneralTabListUiIntent

    data class Agree(
        val threadId: Long,
        val postId: Long,
        val hasAgree: Int
    ) : GeneralTabListUiIntent
}

sealed interface GeneralTabListPartialChange : PartialChange<GeneralTabListUiState> {
    sealed class FirstLoad : GeneralTabListPartialChange {
        override fun reduce(oldState: GeneralTabListUiState): GeneralTabListUiState = when (this) {
            Start -> oldState.copy(isRefreshing = true)
            is Success -> oldState.copy(
                isRefreshing = false,
                threadList = threadList.distinctById(),
                hasMore = hasMore,
                currentPage = 1,
                lastThreadId = lastThreadId,
                sortType = sortType,
            )
            is Failure -> oldState.copy(isRefreshing = false)
        }

        data object Start : FirstLoad()
        data class Success(
            val threadList: List<ThreadItemData>,
            val hasMore: Boolean,
            val lastThreadId: Long,
            val sortType: Int = -1,
        ) : FirstLoad()
        data class Failure(val error: Throwable) : FirstLoad()
    }

    sealed class Refresh : GeneralTabListPartialChange {
        override fun reduce(oldState: GeneralTabListUiState): GeneralTabListUiState = when (this) {
            Start -> oldState.copy(isRefreshing = true)
            is Success -> oldState.copy(
                isRefreshing = false,
                threadList = threadList.distinctById(),
                hasMore = hasMore,
                currentPage = 1,
                lastThreadId = lastThreadId,
                sortType = sortType,
            )
            is Failure -> oldState.copy(isRefreshing = false)
        }

        data object Start : Refresh()
        data class Success(
            val threadList: List<ThreadItemData>,
            val hasMore: Boolean,
            val lastThreadId: Long,
            val sortType: Int = -1,
        ) : Refresh()
        data class Failure(val error: Throwable) : Refresh()
    }

    sealed class LoadMore : GeneralTabListPartialChange {
        override fun reduce(oldState: GeneralTabListUiState): GeneralTabListUiState = when (this) {
            Start -> oldState.copy(isLoadingMore = true)
            is Success -> oldState.copy(
                isLoadingMore = false,
                threadList = (oldState.threadList + threadList).distinctById(),
                hasMore = hasMore,
                currentPage = currentPage,
                lastThreadId = lastThreadId,
            )
            is Failure -> oldState.copy(isLoadingMore = false)
        }

        data object Start : LoadMore()
        data class Success(
            val threadList: List<ThreadItemData>,
            val hasMore: Boolean,
            val currentPage: Int,
            val lastThreadId: Long,
        ) : LoadMore()
        data class Failure(val error: Throwable) : LoadMore()
    }

    sealed class Agree private constructor() : GeneralTabListPartialChange {
        // 差分模型:显示由 FeedCard.ThreadAgreeBtn 从 OpRecordStore.records 推导,
        // reducer 不再改写 proto 计数;记录更新全部集中在 dispatchEvent
        override fun reduce(oldState: GeneralTabListUiState): GeneralTabListUiState =
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

data class GeneralTabListUiState(
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val threadList: ImmutableList<ThreadItemData> = persistentListOf(),
    val currentPage: Int = 1,
    val hasMore: Boolean = true,
    val lastThreadId: Long = 0,
    val sortType: Int = -1,
) : UiState

sealed interface GeneralTabListUiEvent : UiEvent {
    data object BackToTop : GeneralTabListUiEvent
    data class Refresh(val sortType: Int = -1) : GeneralTabListUiEvent

    data class AgreeFail(
        val threadId: Long,
        val postId: Long,
        val hasAgree: Int,
        val errorCode: Int,
        val errorMsg: String
    ) : GeneralTabListUiEvent
}
