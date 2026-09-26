package com.huanchengfly.tieba.post.ui.page.forum.threadlist

import androidx.compose.runtime.Stable
import com.huanchengfly.tieba.post.utils.AgreeOpRunner
import com.huanchengfly.tieba.post.utils.ListAgreeOutcome
import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.api.AgreeParams
import com.huanchengfly.tieba.post.api.TiebaRateLimitedException
import com.huanchengfly.tieba.post.core.network.model.protos.frsPage.Classify
import com.huanchengfly.tieba.post.core.network.model.protos.frsPage.FrsPageResponse
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
                AgreeOpRunner.onStartAgree(partialChange.threadId, partialChange.hasAgree)
                null
            }

            is ForumThreadListPartialChange.Agree.Failure -> {
                // 限流拦截的请求从未 setPending,绝不回退(判定与回滚收口在 runner)
                AgreeOpRunner.onFailureAgree(partialChange.threadId, partialChange.error)
                ForumThreadListUiEvent.AgreeFail(
                    partialChange.threadId,
                    partialChange.postId,
                    partialChange.hasAgree,
                    if (partialChange.error is TiebaRateLimitedException)
                        AgreeParams.RATE_LIMIT_ERROR_CODE
                    else partialChange.error.getErrorCode(),
                    partialChange.error.getErrorMessage(),
                    // E1:重试必须与首发同参——forum_id 经事件透传给重试意图,
                    // 否则首发带 forum_id、snackbar 重试不带,同一意图两次请求字段不一致
                    partialChange.forumId,
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
        AgreeOpRunner.rebaseLoaded(
            AgreeParams.OBJ_THREAD,
            threadList.map { it.thread.get { threadId } }
        )
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
        // 行为约定(2026-09-07 用户拍板):退到主页再重进吧 = 新的一次浏览,归零从头;
        // 导航栈内"进帖返回"的进度恢复由页面锚点机制负责,不走这里
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
                    response.data_!!.thread_list.map { ThreadItemData(it.wrapImmutable()) }
                ForumThreadListPartialChange.FirstLoad.Success(
                    response.data_!!.forum_rule?.title.takeIf {
                        type == ForumThreadListType.Latest && response.data_!!.forum_rule?.has_forum_rule == 1
                    },
                    threadList,
                    response.data_!!.thread_id_list,
                    (response.data_!!.forum?.good_classify ?: emptyList()).wrapImmutable(),
                    goodClassifyId.takeIf { type == ForumThreadListType.Good },
                    response.data_!!.page!!.has_more == 1
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
                    response.data_!!.thread_list.map { ThreadItemData(it.wrapImmutable()) }
                ForumThreadListPartialChange.Refresh.Success(
                    threadList,
                    response.data_!!.thread_id_list,
                    (response.data_!!.forum?.good_classify ?: emptyList()).wrapImmutable(),
                    goodClassifyId.takeIf { type == ForumThreadListType.Good },
                    response.data_!!.page!!.has_more == 1,
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
                    response.data_!!.thread_list.map { ThreadItemData(it.wrapImmutable()) }
                ForumThreadListPartialChange.LoadMore.Success(
                    threadList = threadList,
                    threadListIds = threadListIds.drop(size),
                    currentPage = currentPage,
                    // hasMore 恒 true:本页被直播贴/视频贴过滤成空列表不代表没有更多。
                    // 队列未耗尽→下一次 LoadMore 继续批量;队列耗尽→自动落到下方
                    // frsPage 分支,由服务端 has_more 裁决终止。批量响应无 page 字段,
                    // 无法在此读游标;恒 true 不会死循环(队列严格递减,终局由分支切换收敛)。
                    // 过滤成空即置 false 会让剩余队列与后续页永久不可达
                    hasMore = true
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
                        response.data_!!.thread_list.map { ThreadItemData(it.wrapImmutable()) }
                    ForumThreadListPartialChange.LoadMore.Success(
                        threadList = threadList,
                        threadListIds = response.data_!!.thread_id_list,
                        currentPage = currentPage + 1,
                        response.data_!!.page!!.has_more == 1
                    )
                }
        }
        return flow
            .onStart { emit(ForumThreadListPartialChange.LoadMore.Start) }
            .catch { emit(ForumThreadListPartialChange.LoadMore.Failure(it)) }
    }

    private fun ForumThreadListUiIntent.Agree.producePartialChange(): Flow<ForumThreadListPartialChange.Agree> =
        // 赞踩链路统一收口在 AgreeOpRunner(限流预检/配对撤销/三态判定/记录表收尾),
        // 本页只做中性结果 → PartialChange 的类型映射
        AgreeOpRunner.threadAgree(threadId, postId, hasAgree, forumId)
            .map<ListAgreeOutcome, ForumThreadListPartialChange.Agree> { outcome ->
                when (outcome) {
                    is ListAgreeOutcome.Started ->
                        ForumThreadListPartialChange.Agree.Start(threadId, outcome.newHasAgree)

                    is ListAgreeOutcome.Accepted ->
                        ForumThreadListPartialChange.Agree.Success(threadId, hasAgree xor 1)

                    is ListAgreeOutcome.Rejected ->
                        ForumThreadListPartialChange.Agree.Failure(
                            threadId,
                            postId,
                            hasAgree,
                            outcome.error,
                            forumId,
                        )
                }
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
        val hasAgree: Int,
        // E1:opAgree 的官方必带参数,从列表项 ThreadInfo 透传;null 时不发送该字段
        val forumId: Long? = null,
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
            val error: Throwable,
            // E1:透传给 AgreeFail 事件,供 snackbar 重试还原 forum_id
            val forumId: Long? = null,
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
        val errorMsg: String,
        // E1:snackbar 重试路径与首发同参(官方必带 forum_id);null 时维持不发送
        val forumId: Long? = null,
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