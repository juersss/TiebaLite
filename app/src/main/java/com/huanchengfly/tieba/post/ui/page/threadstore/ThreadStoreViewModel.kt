package com.huanchengfly.tieba.post.ui.page.threadstore

import androidx.compose.runtime.Stable
import com.huanchengfly.tieba.post.api.interfaces.ITiebaApi
import com.huanchengfly.tieba.post.api.models.CommonResponse
import com.huanchengfly.tieba.post.api.models.ThreadStoreBean
import com.huanchengfly.tieba.post.api.retrofit.exception.getErrorCode
import com.huanchengfly.tieba.post.api.retrofit.exception.getErrorMessage
import com.huanchengfly.tieba.post.arch.BaseViewModel
import com.huanchengfly.tieba.post.arch.ImmutableHolder
import com.huanchengfly.tieba.post.arch.PartialChange
import com.huanchengfly.tieba.post.arch.PartialChangeProducer
import com.huanchengfly.tieba.post.arch.UiEvent
import com.huanchengfly.tieba.post.arch.UiIntent
import com.huanchengfly.tieba.post.arch.UiState
import com.huanchengfly.tieba.post.arch.wrapImmutable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject

/**
 * 列表代际:Refresh.Start 时递增;在途 LoadMore 携带发起时刻的代际,reducer 按代丢弃
 * 旧代结果——删除后补发的 Refresh 与在途 LoadMore 跨分支并发(merge 不保证跨类顺序),
 * OFFSET 左移的旧页在 Refresh 之后追加会产生重复行+游标错位。
 */
private val threadStoreListGeneration = java.util.concurrent.atomic.AtomicInteger(0)

@Stable
@HiltViewModel
class ThreadStoreViewModel @Inject constructor(
    private val tiebaApi: ITiebaApi,
) :
    BaseViewModel<ThreadStoreUiIntent, ThreadStorePartialChange, ThreadStoreUiState, ThreadStoreUiEvent>() {
    override fun createInitialState(): ThreadStoreUiState = ThreadStoreUiState()

    override fun createPartialChangeProducer(): PartialChangeProducer<ThreadStoreUiIntent, ThreadStorePartialChange, ThreadStoreUiState> =
        ThreadStorePartialChangeProducer(tiebaApi)

    override fun dispatchEvent(partialChange: ThreadStorePartialChange): UiEvent? {
        return when (partialChange) {
            // Refresh 起始递增列表代际:删除后补发的 Refresh 与在途 LoadMore 跨分支
            // 并发(merge 不保证跨类顺序),旧代 LoadMore 的 OFFSET 左移结果若在
            // Refresh 之后落 reduce,会产生重复行+游标错位——reducer 按代丢弃
            is ThreadStorePartialChange.Refresh.Start -> {
                threadStoreListGeneration.incrementAndGet()
                null
            }
            is ThreadStorePartialChange.Delete.Success -> ThreadStoreUiEvent.Delete.Success
            is ThreadStorePartialChange.Delete.Failure -> ThreadStoreUiEvent.Delete.Failure(
                partialChange.error.getErrorCode(),
                partialChange.error.getErrorMessage()
            )

            else -> null
        }
    }

    private class ThreadStorePartialChangeProducer(
        private val tiebaApi: ITiebaApi,
    ) :
        PartialChangeProducer<ThreadStoreUiIntent, ThreadStorePartialChange, ThreadStoreUiState> {
        @OptIn(ExperimentalCoroutinesApi::class)
        override fun toPartialChangeFlow(intentFlow: Flow<ThreadStoreUiIntent>): Flow<ThreadStorePartialChange> =
            merge(
                intentFlow.filterIsInstance<ThreadStoreUiIntent.Refresh>()
                    .flatMapConcat { produceRefreshPartialChange() },
                intentFlow.filterIsInstance<ThreadStoreUiIntent.LoadMore>()
                    .flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<ThreadStoreUiIntent.Delete>()
                    .flatMapConcat { it.producePartialChange() },
            )

        private fun produceRefreshPartialChange() =
            tiebaApi
                .threadStoreFlow()
                .map {
                    if (it.storeThread != null) ThreadStorePartialChange.Refresh.Success(
                        it.storeThread!!,
                        it.storeThread!!.isNotEmpty()
                    )
                    else ThreadStorePartialChange.Refresh.Failure(NullPointerException("未知错误"))
                }
                .onStart { emit(ThreadStorePartialChange.Refresh.Start) }
                .catch { emit(ThreadStorePartialChange.Refresh.Failure(it)) }

        private fun ThreadStoreUiIntent.LoadMore.producePartialChange(): Flow<ThreadStorePartialChange.LoadMore> {
            // 发起时刻捕获代际:期间若发生 Refresh,结果按旧代丢弃
            val generation = threadStoreListGeneration.get()
            return tiebaApi
                .threadStoreFlow(page)
                .map {
                    if (it.storeThread != null) ThreadStorePartialChange.LoadMore.Success(
                        it.storeThread!!,
                        it.storeThread!!.isNotEmpty(),
                        page,
                        generation
                    )
                    else ThreadStorePartialChange.LoadMore.Failure(NullPointerException("未知错误"))
                }
                .onStart { emit(ThreadStorePartialChange.LoadMore.Start) }
                .catch { emit(ThreadStorePartialChange.LoadMore.Failure(it)) }
        }

        private fun ThreadStoreUiIntent.Delete.producePartialChange() =
            tiebaApi
                .removeStoreFlow(threadId)
                .map<CommonResponse, ThreadStorePartialChange.Delete> {
                    ThreadStorePartialChange.Delete.Success(threadId)
                }
                .catch { emit(ThreadStorePartialChange.Delete.Failure(it)) }
    }
}

sealed interface ThreadStoreUiIntent : UiIntent {
    object Refresh : ThreadStoreUiIntent

    data class LoadMore(val page: Int) : ThreadStoreUiIntent

    data class Delete(val threadId: String) : ThreadStoreUiIntent
}

sealed interface ThreadStorePartialChange : PartialChange<ThreadStoreUiState> {
    sealed class Refresh : ThreadStorePartialChange {
        override fun reduce(oldState: ThreadStoreUiState): ThreadStoreUiState = when (this) {
            is Failure -> oldState.copy(isRefreshing = false, error = wrapImmutable(error))
            Start -> oldState.copy(isRefreshing = true)
            is Success -> oldState.copy(
                isRefreshing = false,
                data = data,
                currentPage = 0,
                hasMore = hasMore,
                error = null
            )
        }

        object Start : Refresh()

        data class Success(
            val data: List<ThreadStoreBean.ThreadStoreInfo>,
            val hasMore: Boolean
        ) : Refresh()

        data class Failure(
            val error: Throwable
        ) : Refresh()
    }

    sealed class LoadMore : ThreadStorePartialChange {
        override fun reduce(oldState: ThreadStoreUiState): ThreadStoreUiState = when (this) {
            is Failure -> oldState.copy(isLoadingMore = false)
            Start -> oldState.copy(isLoadingMore = true)
            is Success ->
                if (generation != threadStoreListGeneration.get()) {
                    // 旧代:在途期间发生了 Refresh,旧 OFFSET 页此时追加会重复行+游标错位。
                    // 结果丢弃也必须清 isLoadingMore:该标志只由 LoadMore.Start 置位,
                    // Refresh 三个分支都不碰它;若保留 true,LoadMoreLayout 的
                    // !curIsLoading 双门控会拦住后续所有加载,收藏页从此翻页卡死。
                    // LoadMore 走 flatMapConcat 串行,此分支 reduce 时无其他在途
                    // LoadMore,清标志不会误伤。
                    oldState.copy(isLoadingMore = false)
                } else {
                    oldState.copy(
                        isLoadingMore = false,
                        data = oldState.data + data,
                        currentPage = currentPage,
                        hasMore = hasMore
                    )
                }
        }

        object Start : LoadMore()

        data class Success(
            val data: List<ThreadStoreBean.ThreadStoreInfo>,
            val hasMore: Boolean,
            val currentPage: Int,
            val generation: Int
        ) : LoadMore()

        data class Failure(
            val error: Throwable
        ) : LoadMore()
    }

    sealed class Delete : ThreadStorePartialChange {
        override fun reduce(oldState: ThreadStoreUiState): ThreadStoreUiState = when (this) {
            is Failure -> oldState
            is Success -> oldState.copy(data = oldState.data.filterNot { it.threadId == threadId })
        }

        data class Success(
            val threadId: String
        ) : Delete()

        data class Failure(
            val error: Throwable
        ) : Delete()
    }
}

data class ThreadStoreUiState(
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val currentPage: Int = 1,
    val data: List<ThreadStoreBean.ThreadStoreInfo> = emptyList(),
    val error: ImmutableHolder<Throwable>? = null
) : UiState

sealed interface ThreadStoreUiEvent : UiEvent {
    sealed interface Delete : ThreadStoreUiEvent {
        object Success : Delete

        data class Failure(
            val errorCode: Int,
            val errorMsg: String
        ) : Delete
    }
}