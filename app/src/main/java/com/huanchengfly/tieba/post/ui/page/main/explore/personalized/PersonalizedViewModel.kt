package com.huanchengfly.tieba.post.ui.page.main.explore.personalized

import androidx.compose.runtime.Stable
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
import com.huanchengfly.tieba.post.api.models.protos.ThreadInfo
import com.huanchengfly.tieba.post.api.models.protos.personalized.DislikeReason
import com.huanchengfly.tieba.post.api.models.protos.personalized.PersonalizedResponse
import com.huanchengfly.tieba.post.api.retrofit.exception.TiebaApiException
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
import com.huanchengfly.tieba.post.models.DislikeBean
import com.huanchengfly.tieba.post.repository.PersonalizedRepository
import com.huanchengfly.tieba.post.ui.models.ThreadItemData
import com.huanchengfly.tieba.post.ui.models.distinctById
import com.huanchengfly.tieba.post.utils.OpRecordStore
import com.huanchengfly.tieba.post.utils.appPreferences
import com.huanchengfly.tieba.post.utils.FollowedForumsCache
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
class PersonalizedViewModel @Inject constructor() :
    BaseViewModel<PersonalizedUiIntent, PersonalizedPartialChange, PersonalizedUiState, PersonalizedUiEvent>() {
    override fun createInitialState(): PersonalizedUiState = PersonalizedUiState()

    override fun createPartialChangeProducer(): PartialChangeProducer<PersonalizedUiIntent, PersonalizedPartialChange, PersonalizedUiState> =
        ExplorePartialChangeProducer

    override fun dispatchEvent(partialChange: PersonalizedPartialChange): UiEvent? =
        when (partialChange) {
            is PersonalizedPartialChange.Refresh.Failure -> CommonUiEvent.Toast(partialChange.error.getErrorMessage())
            is PersonalizedPartialChange.LoadMore.Failure -> CommonUiEvent.Toast(partialChange.error.getErrorMessage())
            is PersonalizedPartialChange.Refresh.Success -> {
                // 列表重载:本次返回的 agreeNum 基准已包含已确认操作,对齐标记跟进意图
                rebaseLoaded(partialChange.data)
                PersonalizedUiEvent.RefreshSuccess(partialChange.data.size)
            }

            is PersonalizedPartialChange.Agree.Start -> {
                // 乐观意图进记录表;显示数字/亮灯由 FeedCard.ThreadAgreeBtn 从 records 推导
                OpRecordStore.setPending(
                    App.INSTANCE,
                    AgreeParams.OBJ_THREAD,
                    partialChange.threadId,
                    if (partialChange.hasAgree == 1) MyAgreeOp.AGREE else MyAgreeOp.NONE
                )
                null
            }

            is PersonalizedPartialChange.Agree.Failure -> {
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

            is PersonalizedPartialChange.LoadMore.Success -> {
                rebaseLoaded(partialChange.data); null
            }

            else -> null
        }

    private fun rebaseLoaded(data: List<ThreadItemData>) {
        val keys = HashSet<String>(data.size)
        data.forEach {
            keys.add(OpRecordStore.key(AgreeParams.OBJ_THREAD, it.thread.get { threadId }))
        }
        OpRecordStore.rebase(App.INSTANCE, keys)
    }

    private object ExplorePartialChangeProducer : PartialChangeProducer<PersonalizedUiIntent, PersonalizedPartialChange, PersonalizedUiState> {
        @OptIn(ExperimentalCoroutinesApi::class)
        override fun toPartialChangeFlow(intentFlow: Flow<PersonalizedUiIntent>): Flow<PersonalizedPartialChange> =
            merge(
                intentFlow.filterIsInstance<PersonalizedUiIntent.Refresh>().flatMapConcat { produceRefreshPartialChange() },
                intentFlow.filterIsInstance<PersonalizedUiIntent.LoadMore>().flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<PersonalizedUiIntent.Dislike>().flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<PersonalizedUiIntent.Agree>().flatMapConcat { it.producePartialChange() },
            )

        private fun produceRefreshPartialChange(): Flow<PersonalizedPartialChange.Refresh> =
            PersonalizedRepository
                .personalizedFlow(1, 1)
                .map<PersonalizedResponse, PersonalizedPartialChange.Refresh> { response ->
                    val data = response.toData()
                        .filter {
                            !App.INSTANCE.appPreferences.blockVideo || it.get { videoInfo } == null
                        }
                        .filter { it.get { ala_info } == null }
                        // 过滤未关注的吧
                        .filter {
                            val showFollowedOnly = App.INSTANCE.appPreferences.showFollowedOnly
                            !showFollowedOnly || FollowedForumsCache.isFollowed(it.get { forumId })
                        }
                    val threadPersonalizedData = response.data_?.thread_personalized ?: emptyList()
                    PersonalizedPartialChange.Refresh.Success(
                        data = data.map { thread ->
                            val threadPersonalized =
                                threadPersonalizedData.firstOrNull { it.tid == thread.get { id } }
                                    ?.wrapImmutable()
                            ThreadItemData(thread = thread, personalized = threadPersonalized)
                        }.toImmutableList(),
                    )
                }
                .onStart { emit(PersonalizedPartialChange.Refresh.Start) }
                .catch { emit(PersonalizedPartialChange.Refresh.Failure(it)) }

        private fun PersonalizedUiIntent.LoadMore.producePartialChange(): Flow<PersonalizedPartialChange.LoadMore> =
            PersonalizedRepository
                .personalizedFlow(2, page)
                .map<PersonalizedResponse, PersonalizedPartialChange.LoadMore> { response ->
                    val data = response.toData()
                        .filter {
                            !App.INSTANCE.appPreferences.blockVideo || it.get { videoInfo } == null
                        }
                        .filter { it.get { ala_info } == null }
                        // 过滤未关注的吧
                        .filter {
                            val showFollowedOnly = App.INSTANCE.appPreferences.showFollowedOnly
                            !showFollowedOnly || FollowedForumsCache.isFollowed(it.get { forumId })
                        }
                    val threadPersonalizedData = response.data_?.thread_personalized ?: emptyList()
                    PersonalizedPartialChange.LoadMore.Success(
                        currentPage = page,
                        data = data.map { thread ->
                            val threadPersonalized =
                                threadPersonalizedData.firstOrNull { it.tid == thread.get { id } }
                                    ?.wrapImmutable()
                            ThreadItemData(thread = thread, personalized = threadPersonalized)
                        }.toImmutableList(),
                    )
                }
                .onStart { emit(PersonalizedPartialChange.LoadMore.Start) }
                .catch { emit(PersonalizedPartialChange.LoadMore.Failure(currentPage = page, error = it)) }

        private fun PersonalizedUiIntent.Dislike.producePartialChange(): Flow<PersonalizedPartialChange.Dislike> =
            TiebaApi.getInstance().submitDislikeFlow(
                DislikeBean(
                    threadId.toString(),
                    reasons.joinToString(",") { it.get { dislikeId }.toString() },
                    forumId?.toString(),
                    clickTime,
                    reasons.joinToString(",") { it.get { extra } },
                )
            ).map<CommonResponse, PersonalizedPartialChange.Dislike> { PersonalizedPartialChange.Dislike.Success(threadId) }
                .catch { emit(PersonalizedPartialChange.Dislike.Failure(threadId, it)) }
                .onStart { emit(PersonalizedPartialChange.Dislike.Start(threadId)) }

        private fun PersonalizedUiIntent.Agree.producePartialChange(): Flow<PersonalizedPartialChange.Agree> {
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
            }.map<AgreeBean, PersonalizedPartialChange.Agree> { bean ->
                val agree = (hasAgree xor 1) == 1
                // HTTP 200 不等于业务成功:先按 errorCode 做三态判定,再决定要不要写记录
                when (val result = bean.toOpAgreeResult(threadId, agree)) {
                    // Ok 不写记录——Start 的 setPending 已表达意图,此刻 confirm 会抵消乐观偏移
                    is OpAgreeResult.Ok -> {
                        undoDisagreeIfAccepted()
                        PersonalizedPartialChange.Agree.Success(
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
                        PersonalizedPartialChange.Agree.Success(
                            threadId,
                            hasAgree xor 1
                        )
                    }

                    is OpAgreeResult.Business -> {
                        // 普通业务拒绝:不 confirm(否则 my=server 落盘永不自愈),
                        // 回滚统一交给 dispatchEvent(与网络失败同路)
                        PersonalizedPartialChange.Agree.Failure(
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
                        emit(PersonalizedPartialChange.Agree.Failure(threadId, hasAgree, it))
                    } else {
                        OpRecordStore.confirm(App.INSTANCE, AgreeParams.OBJ_THREAD, threadId, authoritative)
                        undoDisagreeIfAccepted()
                        emit(PersonalizedPartialChange.Agree.Success(threadId, hasAgree xor 1))
                    }
                }
                .onStart { if (acquired) emit(PersonalizedPartialChange.Agree.Start(threadId, hasAgree xor 1)) }
        }

        private fun PersonalizedResponse.toData(): ImmutableList<ImmutableHolder<ThreadInfo>> {
            return (data_?.thread_list ?: emptyList()).wrapImmutable()
        }
    }
}

sealed interface PersonalizedUiIntent : UiIntent {
    data object Refresh : PersonalizedUiIntent

    data class LoadMore(val page: Int) : PersonalizedUiIntent

    data class Agree(
        val threadId: Long,
        val postId: Long,
        val hasAgree: Int
    ) : PersonalizedUiIntent

    data class Dislike(
        val forumId: Long?,
        val threadId: Long,
        val reasons: List<ImmutableHolder<DislikeReason>>,
        val clickTime: Long
    ) : PersonalizedUiIntent
}

sealed interface PersonalizedPartialChange : PartialChange<PersonalizedUiState> {
    sealed class Agree private constructor() : PersonalizedPartialChange {
        // 差分模型:显示由 FeedCard.ThreadAgreeBtn 从 OpRecordStore.records 推导,
        // reducer 不再改写 proto 计数;记录更新全部集中在 dispatchEvent
        override fun reduce(oldState: PersonalizedUiState): PersonalizedUiState =
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

    sealed class Dislike private constructor() : PersonalizedPartialChange {
        override fun reduce(oldState: PersonalizedUiState): PersonalizedUiState =
            when (this) {
                is Start -> {
                    if (!oldState.hiddenThreadIds.contains(threadId)) {
                        oldState.copy(hiddenThreadIds = (oldState.hiddenThreadIds + threadId).toImmutableList())
                    } else {
                        oldState
                    }
                }
                is Success -> {
                    if (!oldState.hiddenThreadIds.contains(threadId)) {
                        oldState.copy(hiddenThreadIds = (oldState.hiddenThreadIds + threadId).toImmutableList())
                    } else {
                        oldState
                    }
                }
                is Failure -> oldState
            }

        data class Start(
            val threadId: Long,
        ) : Dislike()

        data class Success(
            val threadId: Long,
        ) : Dislike()

        data class Failure(
            val threadId: Long,
            val error: Throwable,
        ) : Dislike()
    }

    sealed class Refresh private constructor() : PersonalizedPartialChange {
        override fun reduce(oldState: PersonalizedUiState): PersonalizedUiState =
            when (this) {
                Start -> oldState.copy(isRefreshing = true)
                is Success -> {
                    val oldSize = oldState.data.size
                    val newData = (data + oldState.data).distinctById()
                    oldState.copy(
                        isRefreshing = false,
                        currentPage = 1,
                        data = newData,
                        refreshPosition = if (oldState.data.isEmpty()) 0 else (newData.size - oldSize),
                    )
                }

                is Failure -> oldState.copy(
                    isRefreshing = false,
                    error = error.wrapImmutable()
                )
            }

        data object Start : Refresh()

        data class Success(
            val data: List<ThreadItemData>,
        ) : Refresh()

        data class Failure(
            val error: Throwable,
        ) : Refresh()
    }

    sealed class LoadMore private constructor() : PersonalizedPartialChange {
        override fun reduce(oldState: PersonalizedUiState): PersonalizedUiState =
            when (this) {
                Start -> oldState.copy(isLoadingMore = true)
                is Success -> oldState.copy(
                    isLoadingMore = false,
                    currentPage = currentPage,
                    data = (oldState.data + data).distinctById(),
                )

                is Failure -> oldState.copy(
                    isLoadingMore = false,
                    error = error.wrapImmutable()
                )
            }

        data object Start : LoadMore()

        data class Success(
            val currentPage: Int,
            val data: List<ThreadItemData>,
        ) : LoadMore()

        data class Failure(
            val currentPage: Int,
            val error: Throwable,
        ) : LoadMore()
    }
}

data class PersonalizedUiState(
    val isRefreshing: Boolean = true,
    val isLoadingMore: Boolean = false,
    val error: ImmutableHolder<Throwable>? = null,
    val currentPage: Int = 1,
    val data: ImmutableList<ThreadItemData> = persistentListOf(),
    val hiddenThreadIds: ImmutableList<Long> = persistentListOf(),
    val refreshPosition: Int = 0,
): UiState

sealed interface PersonalizedUiEvent : UiEvent {
    data class RefreshSuccess(val count: Int) : PersonalizedUiEvent
}