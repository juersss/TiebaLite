package com.huanchengfly.tieba.post.ui.page.forum.generaltablist

import androidx.compose.runtime.Stable
import com.huanchengfly.tieba.post.utils.AgreeOpRunner
import com.huanchengfly.tieba.post.utils.ListAgreeOutcome
import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.api.AgreeParams
import com.huanchengfly.tieba.post.api.TiebaRateLimitedException
import com.huanchengfly.tieba.post.core.network.model.protos.FrsTabInfo
import com.huanchengfly.tieba.post.core.network.model.protos.GeneralTabList.GeneralTabListResponse
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
                AgreeOpRunner.onStartAgree(partialChange.threadId, partialChange.hasAgree)
                null
            }
            is GeneralTabListPartialChange.Agree.Failure -> {
                // 限流拦截的请求从未 setPending,绝不回退(判定与回滚收口在 runner)
                AgreeOpRunner.onFailureAgree(partialChange.threadId, partialChange.error)
                GeneralTabListUiEvent.AgreeFail(
                    partialChange.threadId,
                    partialChange.postId,
                    partialChange.hasAgree,
                    if (partialChange.error is TiebaRateLimitedException)
                        AgreeParams.RATE_LIMIT_ERROR_CODE
                    else partialChange.error.getErrorCode(),
                    partialChange.error.getErrorMessage(),
                    // E1:重试必须与首发同参——forum_id 经事件透传给重试意图
                    partialChange.forumId,
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
        AgreeOpRunner.rebaseLoaded(
            AgreeParams.OBJ_THREAD,
            threadList.map { it.thread.get { threadId } }
        )
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
            val threadList = response.data_!!.general_list.map { ThreadItemData(it.wrapImmutable()) }
            GeneralTabListPartialChange.FirstLoad.Success(
                threadList = threadList,
                hasMore = response.data_!!.has_more == 1,
                lastThreadId = response.data_!!.general_list.lastOrNull()?.id ?: 0,
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
            val threadList = response.data_!!.general_list.map { ThreadItemData(it.wrapImmutable()) }
            GeneralTabListPartialChange.Refresh.Success(
                threadList = threadList,
                hasMore = response.data_!!.has_more == 1,
                lastThreadId = response.data_!!.general_list.lastOrNull()?.id ?: 0,
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
            val threadList = response.data_!!.general_list.map { ThreadItemData(it.wrapImmutable()) }
            GeneralTabListPartialChange.LoadMore.Success(
                threadList = threadList,
                // 过滤后的空列表不代表没有更多(直播贴/视频贴整页被滤时),hasMore
                // 只由服务端游标决定,否则翻页永久终止
                hasMore = response.data_!!.has_more == 1,
                currentPage = currentPage + 1,
                lastThreadId = response.data_!!.general_list.lastOrNull()?.id ?: lastThreadId,
            )
        }
            .onStart { emit(GeneralTabListPartialChange.LoadMore.Start) }
            .catch { emit(GeneralTabListPartialChange.LoadMore.Failure(it)) }

    private fun GeneralTabListUiIntent.Agree.producePartialChange(): Flow<GeneralTabListPartialChange.Agree> =
        // 赞踩链路统一收口在 AgreeOpRunner,本页只做中性结果 → PartialChange 的类型映射
        AgreeOpRunner.threadAgree(threadId, postId, hasAgree, forumId)
            .map<ListAgreeOutcome, GeneralTabListPartialChange.Agree> { outcome ->
                when (outcome) {
                    is ListAgreeOutcome.Started ->
                        GeneralTabListPartialChange.Agree.Start(threadId, outcome.newHasAgree)

                    is ListAgreeOutcome.Accepted ->
                        GeneralTabListPartialChange.Agree.Success(threadId, hasAgree xor 1)

                    is ListAgreeOutcome.Rejected ->
                        GeneralTabListPartialChange.Agree.Failure(
                            threadId,
                            postId,
                            hasAgree,
                            outcome.error,
                            // E1:与 ForumThreadList 同口径,重试经事件还原 forum_id,
                            // 否则重试请求整段丢 forum_id(与首发参数形状不一致)
                            forumId
                        )
                }
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
        val hasAgree: Int,
        // E1:opAgree 官方必带参数,从 ThreadInfo 透传;null 时不发送
        val forumId: Long? = null,
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
            val error: Throwable,
            // E1:透传给 AgreeFail 事件,供 snackbar 重试还原 forum_id
            val forumId: Long? = null,
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
        val errorMsg: String,
        // E1:snackbar 重试路径与首发同参(官方必带 forum_id);null 时维持不发送
        val forumId: Long? = null,
    ) : GeneralTabListUiEvent
}
