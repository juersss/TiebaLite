package com.huanchengfly.tieba.post.ui.page.subposts

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.huanchengfly.tieba.post.api.AgreeParams
import com.huanchengfly.tieba.post.api.AgreeRateLimiter
import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.api.TiebaApi
import com.huanchengfly.tieba.post.api.TiebaRateLimitedException
import com.huanchengfly.tieba.post.api.models.AgreeBean
import com.huanchengfly.tieba.post.api.models.CommonResponse
import com.huanchengfly.tieba.post.api.models.protos.Anti
import com.huanchengfly.tieba.post.api.models.protos.serverOpFromErrorMessage
import com.huanchengfly.tieba.post.api.models.protos.MyAgreeOp
import com.huanchengfly.tieba.post.api.models.protos.Agree
import com.huanchengfly.tieba.post.api.models.protos.OpRecord
import com.huanchengfly.tieba.post.api.models.protos.serverEchoOp
import com.huanchengfly.tieba.post.api.models.protos.reverted
import com.huanchengfly.tieba.post.api.models.protos.OpAgreeResult
import com.huanchengfly.tieba.post.api.models.protos.serverOpFromErrorCode
import com.huanchengfly.tieba.post.api.models.protos.toOpAgreeResult
import com.huanchengfly.tieba.post.api.models.protos.Post
import com.huanchengfly.tieba.post.api.models.protos.SimpleForum
import com.huanchengfly.tieba.post.api.models.protos.SubPostList
import com.huanchengfly.tieba.post.api.models.protos.ThreadInfo
import com.huanchengfly.tieba.post.api.models.protos.User
import com.huanchengfly.tieba.post.api.models.protos.contentRenders
import com.huanchengfly.tieba.post.api.models.protos.pbFloor.PbFloorResponse
import com.huanchengfly.tieba.post.api.models.protos.renders
import com.huanchengfly.tieba.post.api.models.protos.withForumFallback
import com.huanchengfly.tieba.post.utils.OpRecordStore
import com.huanchengfly.tieba.post.api.retrofit.exception.getErrorMessage
import com.huanchengfly.tieba.post.api.retrofit.exception.getErrorCode
import com.huanchengfly.tieba.post.arch.BaseViewModel
import com.huanchengfly.tieba.post.arch.CommonUiEvent
import com.huanchengfly.tieba.post.arch.ImmutableHolder
import com.huanchengfly.tieba.post.arch.PartialChange
import com.huanchengfly.tieba.post.arch.PartialChangeProducer
import com.huanchengfly.tieba.post.arch.UiEvent
import com.huanchengfly.tieba.post.arch.UiIntent
import com.huanchengfly.tieba.post.arch.UiState
import com.huanchengfly.tieba.post.arch.wrapImmutable
import com.huanchengfly.tieba.post.ui.common.PbContentRender
import com.huanchengfly.tieba.post.ui.common.PicContentRender
import com.huanchengfly.tieba.post.ui.utils.getSubPostPhotoViewData
import com.huanchengfly.tieba.post.utils.BlockManager.shouldBlock
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject

@Stable
@HiltViewModel
class SubPostsViewModel @Inject constructor() :
    BaseViewModel<SubPostsUiIntent, SubPostsPartialChange, SubPostsUiState, SubPostsUiEvent>() {
    override fun createInitialState() = SubPostsUiState()

    // 赞踩差分计数模型的记录表(进程级单例,与帖子页共享)
    init {
        OpRecordStore.init(App.INSTANCE)
    }

    val opRecords: StateFlow<Map<String, OpRecord>> get() = OpRecordStore.records

    private fun updateOpRecord(objType: Int, id: Long, transform: (OpRecord) -> OpRecord) =
        OpRecordStore.update(App.INSTANCE, objType, id, transform)

    /**
     * 为无记录的对象按服务端回显批量播种初始记录(同帖子页逻辑)。
     * 父楼层 + 本页楼中楼一次写盘、一次发射,避免逐条 confirm 的
     * "每楼一次 prefs.apply + 一次 StateFlow 发射"放大。
     */
    private fun seedFromPage(post: ImmutableHolder<Post>, subPosts: ImmutableList<SubPostItemData>) {
        val seeds = HashMap<String, MyAgreeOp>(subPosts.size + 1)
        post.get { agree }.serverEchoOp().let { op ->
            if (op != MyAgreeOp.NONE) seeds[OpRecordStore.key(AgreeParams.OBJ_POST, post.get { id })] = op
        }
        subPosts.forEach { item ->
            val op = item.subPost.get { agree }.serverEchoOp()
            if (op != MyAgreeOp.NONE) {
                seeds[OpRecordStore.key(AgreeParams.OBJ_SUB_POST, item.subPost.get { id })] = op
            }
        }
        OpRecordStore.seedMissing(App.INSTANCE, seeds)
    }

    /**
     * 数据重载后只对齐**本次重载实际涉及**的对象(父楼层 + 本页楼中楼)。
     *
     * 不能用全表无差别对齐:会把基准从未重载的历史对象也一并对齐,
     * 导致在途请求失败后 `revertPending` 变空操作(计数永久偏移)
     * 以及跨对象计数漂移。详见 [OpRecordStore.rebase]。
     */
    private fun rebaseLoaded(post: ImmutableHolder<Post>, subPosts: ImmutableList<SubPostItemData>) {
        val keys = HashSet<String>(subPosts.size + 1)
        keys.add(OpRecordStore.key(AgreeParams.OBJ_POST, post.get { id }))
        subPosts.forEach { item ->
            keys.add(OpRecordStore.key(AgreeParams.OBJ_SUB_POST, item.subPost.get { id }))
        }
        OpRecordStore.rebase(App.INSTANCE, keys)
    }

    override fun createPartialChangeProducer() = SubPostsPartialChangeProducer

    override fun dispatchEvent(partialChange: SubPostsPartialChange): UiEvent? =
        when (partialChange) {
            // 数据重载:基准计数已包含本地已确认操作,对齐标记对齐当前意图
            is SubPostsPartialChange.Load.Success -> {
                rebaseLoaded(partialChange.post, partialChange.subPosts)
                seedFromPage(partialChange.post, partialChange.subPosts)
                SubPostsUiEvent.ScrollToSubPosts
            }

            // ---- 赞踩:差分计数模型的记录更新,显示数字由 UI 从记录推导 ----
            is SubPostsPartialChange.Disagree.Start -> {
                updateOpRecord(
                    if (partialChange.subPostId != null) AgreeParams.OBJ_SUB_POST else AgreeParams.OBJ_POST,
                    partialChange.subPostId ?: partialChange.postId
                ) {
                    it.copy(my = if (partialChange.hasDisagree) MyAgreeOp.DISAGREE else MyAgreeOp.NONE)
                }
                null
            }

            is SubPostsPartialChange.Disagree.Failure -> {
                // 限流拦截 = 请求根本没发出,本地状态不应有任何变化
                if (partialChange.throwable !is TiebaRateLimitedException) {
                    updateOpRecord(
                        if (partialChange.subPostId != null) AgreeParams.OBJ_SUB_POST else AgreeParams.OBJ_POST,
                        partialChange.subPostId ?: partialChange.postId
                    ) { it.reverted() }
                }
                CommonUiEvent.Toast(partialChange.throwable.getErrorMessage().ifBlank { "操作失败" })
            }

            is SubPostsPartialChange.Disagree.AuthoritativeReject -> {
                val op = serverOpFromErrorCode(partialChange.code)
                updateOpRecord(
                    if (partialChange.subPostId != null) AgreeParams.OBJ_SUB_POST else AgreeParams.OBJ_POST,
                    partialChange.subPostId ?: partialChange.postId
                ) { it.copy(my = op, server = op) }
                CommonUiEvent.Toast(partialChange.msg.ifBlank { partialChange.code })
            }

            is SubPostsPartialChange.Agree.Start -> {
                updateOpRecord(
                    if (partialChange.subPostId != null) AgreeParams.OBJ_SUB_POST else AgreeParams.OBJ_POST,
                    partialChange.subPostId ?: partialChange.postId
                ) {
                    it.copy(my = if (partialChange.hasAgree) MyAgreeOp.AGREE else MyAgreeOp.NONE)
                }
                null
            }

            is SubPostsPartialChange.Agree.Failure -> {
                if (partialChange.throwable is TiebaRateLimitedException) {
                    CommonUiEvent.Toast(partialChange.throwable.message.orEmpty())
                } else {
                    updateOpRecord(
                        if (partialChange.subPostId != null) AgreeParams.OBJ_SUB_POST else AgreeParams.OBJ_POST,
                        partialChange.subPostId ?: partialChange.postId
                    ) { it.reverted() }
                }
                null
            }

            // 与 Disagree.AuthoritativeReject 对称:采纳服务端陈述,my/server 同时对齐,
            // 否则记录会停留在 pending,跨页面显示错误
            is SubPostsPartialChange.Agree.AuthoritativeReject -> {
                val op = serverOpFromErrorCode(partialChange.code)
                updateOpRecord(
                    if (partialChange.subPostId != null) AgreeParams.OBJ_SUB_POST else AgreeParams.OBJ_POST,
                    partialChange.subPostId ?: partialChange.postId
                ) { it.copy(my = op, server = op) }
                CommonUiEvent.Toast(partialChange.msg.ifBlank { partialChange.code })
            }

            else -> null
        }

    object SubPostsPartialChangeProducer :
        PartialChangeProducer<SubPostsUiIntent, SubPostsPartialChange, SubPostsUiState> {
        @OptIn(ExperimentalCoroutinesApi::class)
        override fun toPartialChangeFlow(intentFlow: Flow<SubPostsUiIntent>): Flow<SubPostsPartialChange> =
            merge(
                intentFlow.filterIsInstance<SubPostsUiIntent.Load>()
                    .flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<SubPostsUiIntent.LoadMore>()
                    .flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<SubPostsUiIntent.Agree>()
                    .flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<SubPostsUiIntent.Disagree>()
                    .flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<SubPostsUiIntent.DeletePost>()
                    .flatMapConcat { it.producePartialChange() },
            )

        private fun SubPostsUiIntent.Load.producePartialChange(): Flow<SubPostsPartialChange.Load> =
            TiebaApi.getInstance()
                .pbFloorFlow(threadId, postId, forumId, page, subPostId)
                .map<PbFloorResponse, SubPostsPartialChange.Load> { response ->
                    val page = checkNotNull(response.data_?.page)
                    val forum = checkNotNull(response.data_?.forum)
                    val thread = checkNotNull(response.data_?.thread)
                    val anti = checkNotNull(response.data_?.anti)
                    // pb/floor 的 post 不带 from_forum,补齐后图片才能绑定大图浏览数据
                    val post = checkNotNull(response.data_?.post).withForumFallback(forum)
                    val subPosts = response.data_?.subpost_list.orEmpty().map {
                        SubPostItemData(
                            it.wrapImmutable(),
                            bindSubPostPhotoViewData(it.content.renders, post),
                        )
                    }.toImmutableList()
                    SubPostsPartialChange.Load.Success(
                        anti.wrapImmutable(),
                        forum.wrapImmutable(),
                        thread.wrapImmutable(),
                        post.wrapImmutable(),
                        post.contentRenders,
                        subPosts,
                        page.current_page < page.total_page,
                        page.current_page,
                        page.total_page,
                        page.total_count
                    )
                }
                .onStart { emit(SubPostsPartialChange.Load.Start) }
                .catch { emit(SubPostsPartialChange.Load.Failure(it)) }

        private fun SubPostsUiIntent.LoadMore.producePartialChange(): Flow<SubPostsPartialChange.LoadMore> =
            TiebaApi.getInstance()
                .pbFloorFlow(threadId, postId, forumId, page, subPostId)
                .map<PbFloorResponse, SubPostsPartialChange.LoadMore> { response ->
                    val page = checkNotNull(response.data_?.page)
                    val subPosts = response.data_?.subpost_list.orEmpty().map {
                        SubPostItemData(
                            it.wrapImmutable(),
                            bindSubPostPhotoViewData(
                                it.content.renders,
                                response.data_?.post?.withForumFallback(response.data_?.forum)
                            ),
                        )
                    }.toImmutableList()
                    SubPostsPartialChange.LoadMore.Success(
                        subPosts,
                        page.current_page < page.total_page,
                        page.current_page,
                        page.total_page,
                        page.total_count,
                    )
                }
                .onStart { emit(SubPostsPartialChange.LoadMore.Start) }
                .catch { emit(SubPostsPartialChange.LoadMore.Failure(it)) }

        private fun SubPostsUiIntent.Agree.producePartialChange(): Flow<SubPostsPartialChange.Agree> {
            // 限流检查先于任何状态变更:被拦截的请求不产生 Start,也不改本地状态
            val acquired = AgreeRateLimiter.tryAcquire(
                AgreeRateLimiter.keyFor(
                    if (subPostId == null) AgreeParams.OBJ_POST else AgreeParams.OBJ_SUB_POST,
                    subPostId ?: postId
                )
            )
            // 配对撤销只在主操作被服务端接受/权威对齐时执行(下方 Ok/Authoritative 分支调用)。
            // 主操作被字符串错误码拒绝(Business)时 HTTP 200 不抛异常,撤销若照发会把一次
            // "拒绝"放大成反向漂移:服务端另一侧记录被真删、本地却回滚,两边各自漂移
            suspend fun undoDisagreeIfAccepted() {
                if (!undoDisagree) return
                // 服务端赞踩相互独立,点赞时需显式撤销已有的踩,避免重进帖子后双双点亮
                // 撤销的响应不进主管线:否则会被下游 .map 二次映射成主操作结果,
                // 撤销失败/被限流时会被 .catch 当成主请求失败,回滚已成功的赞
                runCatching {
                    if (!AgreeRateLimiter.tryAcquire(
                            AgreeRateLimiter.keyFor(
                                if (subPostId == null) AgreeParams.OBJ_POST else AgreeParams.OBJ_SUB_POST,
                                subPostId ?: postId
                            ),
                            checkPerObject = false
                        )
                    ) {
                        // 撤销被限流时跳过,不影响已经成功的点赞
                        return@runCatching
                    }
                    TiebaApi.getInstance()
                        .opDisagreeFlow(
                            threadId.toString(),
                            (subPostId ?: postId).toString(),
                            objType = if (subPostId == null) AgreeParams.OBJ_POST else AgreeParams.OBJ_SUB_POST,
                            opType = AgreeParams.OP_UNDO
                        )
                        .collect { }
                }
            }
            return flow {
                if (!acquired) {
                    throw TiebaRateLimitedException()
                }
                emitAll(
                    TiebaApi.getInstance()
                        .opAgreeFlow(
                            threadId.toString(),
                            (subPostId ?: postId).toString(),
                            if (agree) 0 else 1,
                            objType = if (subPostId == null) AgreeParams.OBJ_POST else AgreeParams.OBJ_SUB_POST
                        )
                )
            }
                .map<AgreeBean, SubPostsPartialChange.Agree> { bean ->
                    when (val result = bean.toOpAgreeResult(subPostId ?: postId, agree)) {
                        is OpAgreeResult.Ok -> {
                            undoDisagreeIfAccepted()
                            SubPostsPartialChange.Agree.Success(postId, subPostId, agree)
                        }

                        is OpAgreeResult.Authoritative -> {
                            undoDisagreeIfAccepted()
                            SubPostsPartialChange.Agree.AuthoritativeReject(
                                postId = postId,
                                subPostId = subPostId,
                                code = result.code,
                                msg = result.msg,
                            )
                        }

                        is OpAgreeResult.Business ->
                            SubPostsPartialChange.Agree.Failure(
                                postId = postId,
                                subPostId = subPostId,
                                hasAgree = !agree,
                                throwable = IllegalStateException(result.msg),
                            )
                    }
                }
                .onStart {
                    if (acquired) emit(SubPostsPartialChange.Agree.Start(postId, subPostId, agree))
                }
                .catch {
                    val msg = it.getErrorMessage()
                    val authoritative = serverOpFromErrorMessage(msg)
                    emit(
                        if (authoritative != null) {
                            SubPostsPartialChange.Agree.AuthoritativeReject(
                                postId = postId,
                                subPostId = subPostId,
                                code = msg,
                                msg = msg,
                            )
                        } else {
                            SubPostsPartialChange.Agree.Failure(postId, subPostId, !agree, it)
                        }
                    )
                }
        }

        private fun SubPostsUiIntent.Disagree.producePartialChange(): Flow<SubPostsPartialChange.Disagree> {
            // 限流检查先于任何状态变更:被拦截的请求不产生 Start,也不改本地状态
            val acquired = AgreeRateLimiter.tryAcquire(
                AgreeRateLimiter.keyFor(
                    if (subPostId == null) AgreeParams.OBJ_POST else AgreeParams.OBJ_SUB_POST,
                    subPostId ?: postId
                )
            )
            // 配对撤销只在主操作被服务端接受/权威对齐时执行(Business 拒绝不撤销,
            // 理由同 Agree:字符串错误码路径不抛异常,撤销照发会反向漂移)
            suspend fun undoAgreeIfAccepted() {
                if (!undoAgree) return
                // 点踩时显式撤销已有的赞,与服务端状态对齐
                // 同上:撤销响应不进主管线,失败/被限流时跳过,不回滚已成功的踩
                runCatching {
                    if (!AgreeRateLimiter.tryAcquire(
                            AgreeRateLimiter.keyFor(
                                if (subPostId == null) AgreeParams.OBJ_POST else AgreeParams.OBJ_SUB_POST,
                                subPostId ?: postId
                            ),
                            checkPerObject = false
                        )
                    ) {
                        return@runCatching
                    }
                    TiebaApi.getInstance()
                        .opAgreeFlow(
                            threadId.toString(),
                            (subPostId ?: postId).toString(),
                            opType = AgreeParams.OP_UNDO,
                            objType = if (subPostId == null) AgreeParams.OBJ_POST else AgreeParams.OBJ_SUB_POST
                        )
                        .collect { }
                }
            }
            return flow {
                if (!acquired) {
                    throw TiebaRateLimitedException()
                }
                emitAll(
                    TiebaApi.getInstance()
                        .opDisagreeFlow(
                            threadId.toString(),
                            (subPostId ?: postId).toString(),
                            objType = if (subPostId == null) AgreeParams.OBJ_POST else AgreeParams.OBJ_SUB_POST,
                            opType = if (disagree) AgreeParams.OP_DO else AgreeParams.OP_UNDO
                        )
                )
            }
                .map<AgreeBean, SubPostsPartialChange.Disagree> { bean ->
                    when (val result = bean.toOpAgreeResult(subPostId ?: postId, disagree)) {
                        is OpAgreeResult.Ok -> {
                            undoAgreeIfAccepted()
                            SubPostsPartialChange.Disagree.Success(postId, subPostId, disagree)
                        }

                        is OpAgreeResult.Authoritative -> {
                            undoAgreeIfAccepted()
                            SubPostsPartialChange.Disagree.AuthoritativeReject(
                                postId = postId,
                                subPostId = subPostId,
                                code = result.code,
                                msg = result.msg,
                            )
                        }

                        is OpAgreeResult.Business ->
                            SubPostsPartialChange.Disagree.Failure(
                                postId = postId,
                                subPostId = subPostId,
                                hasDisagree = !disagree,
                                throwable = IllegalStateException(result.msg),
                            )
                    }
                }
                .onStart { if (acquired) emit(SubPostsPartialChange.Disagree.Start(postId, subPostId, disagree)) }
                .catch {
                    // 错误可能由 FailureResponseInterceptor 以异常形式抛出（error_code 为数字时），
                    // 此时 .map 不会被调用，必须在这里识别服务端权威陈述，
                    // 否则会走通用回滚——把状态还原成「踩」，与服务端「已取消踩」相反。
                    val msg = it.getErrorMessage()
                    val authoritative = serverOpFromErrorMessage(msg)
                    emit(
                        if (authoritative != null) {
                            SubPostsPartialChange.Disagree.AuthoritativeReject(
                                postId = postId,
                                subPostId = subPostId,
                                code = msg,
                                msg = msg,
                            )
                        } else {
                            SubPostsPartialChange.Disagree.Failure(postId, subPostId, !disagree, it)
                        }
                    )
                }
        }

        fun SubPostsUiIntent.DeletePost.producePartialChange(): Flow<SubPostsPartialChange.DeletePost> =
            TiebaApi.getInstance()
                .delPostFlow(
                    forumId,
                    forumName,
                    threadId,
                    subPostId ?: postId,
                    tbs,
                    false,
                    deleteMyPost
                )
                .map<CommonResponse, SubPostsPartialChange.DeletePost> {
                    SubPostsPartialChange.DeletePost.Success(postId, subPostId)
                }
                .catch {
                    emit(
                        SubPostsPartialChange.DeletePost.Failure(
                            it.getErrorCode(),
                            it.getErrorMessage()
                        )
                    )
                }
    }

}

sealed interface SubPostsUiIntent : UiIntent {
    data class Load(
        val forumId: Long,
        val threadId: Long,
        val postId: Long,
        val subPostId: Long = 0L,
        val page: Int = 1,
    ) : SubPostsUiIntent

    data class LoadMore(
        val forumId: Long,
        val threadId: Long,
        val postId: Long,
        val subPostId: Long = 0L,
        val page: Int = 1,
    ) : SubPostsUiIntent

    data class Agree(
        val forumId: Long,
        val threadId: Long,
        val postId: Long,
        val subPostId: Long? = null,
        val agree: Boolean,
        val undoDisagree: Boolean = false
    ) : SubPostsUiIntent

    data class Disagree(
        val forumId: Long,
        val threadId: Long,
        val postId: Long,
        val subPostId: Long? = null,
        val disagree: Boolean,
        val undoAgree: Boolean = false
    ) : SubPostsUiIntent

    data class DeletePost(
        val forumId: Long,
        val forumName: String,
        val threadId: Long,
        val postId: Long,
        val subPostId: Long? = null,
        val deleteMyPost: Boolean,
        val tbs: String? = null
    ) : SubPostsUiIntent
}

sealed interface SubPostsPartialChange : PartialChange<SubPostsUiState> {
    sealed class Load : SubPostsPartialChange {
        override fun reduce(oldState: SubPostsUiState): SubPostsUiState =
            when (this) {
                is Start -> oldState.copy(
                    isRefreshing = true
                )

                is Success -> oldState.copy(
                    isRefreshing = false,
                    hasMore = hasMore,
                    currentPage = currentPage,
                    totalPage = totalPage,
                    totalCount = totalCount,
                    forum = forum,
                    thread = thread,
                    post = post,
                    postContentRenders = postContentRenders,
                    subPosts = subPosts,
                )

                is Failure -> oldState.copy(
                    isRefreshing = false,
                )
            }

        data object Start : Load()

        data class Success(
            val anti: ImmutableHolder<Anti>,
            val forum: ImmutableHolder<SimpleForum>,
            val thread: ImmutableHolder<ThreadInfo>,
            val post: ImmutableHolder<Post>,
            val postContentRenders: ImmutableList<PbContentRender>,
            val subPosts: ImmutableList<SubPostItemData>,
            val hasMore: Boolean,
            val currentPage: Int,
            val totalPage: Int,
            val totalCount: Int,
        ) : Load()

        data class Failure(val throwable: Throwable) : Load()
    }

    sealed class LoadMore : SubPostsPartialChange {
        override fun reduce(oldState: SubPostsUiState): SubPostsUiState =
            when (this) {
                is Start -> oldState.copy(
                    isLoading = true
                )

                is Success -> oldState.copy(
                    isLoading = false,
                    hasMore = hasMore,
                    currentPage = currentPage,
                    totalPage = totalPage,
                    totalCount = totalCount,
                    subPosts = (oldState.subPosts + subPosts).toImmutableList(),
                )

                is Failure -> oldState.copy(
                    isLoading = false,
                )
            }

        data object Start : LoadMore()

        data class Success(
            val subPosts: ImmutableList<SubPostItemData>,
            val hasMore: Boolean,
            val currentPage: Int,
            val totalPage: Int,
            val totalCount: Int,
        ) : LoadMore()

        data class Failure(val throwable: Throwable) : LoadMore()
    }

    sealed class Agree : SubPostsPartialChange {
        // 计数与我的态度均由 opRecords 差分推导,reducer 不再改动状态
        override fun reduce(oldState: SubPostsUiState): SubPostsUiState = oldState

        data class Start(
            val postId: Long,
            val subPostId: Long?,
            val hasAgree: Boolean
        ) : Agree()

        data class Success(
            val postId: Long,
            val subPostId: Long?,
            val hasAgree: Boolean
        ) : Agree()

        data class Failure(
            val postId: Long,
            val subPostId: Long?,
            val hasAgree: Boolean,
            val throwable: Throwable,
        ) : Agree()

        data class AuthoritativeReject(
            val postId: Long,
            val subPostId: Long?,
            val code: String,
            val msg: String,
        ) : Agree()
    }

    sealed class Disagree : SubPostsPartialChange {
        // 计数与我的态度均由 opRecords 差分推导,reducer 不再改动状态
        override fun reduce(oldState: SubPostsUiState): SubPostsUiState = oldState

        data class Start(
            val postId: Long,
            val subPostId: Long?,
            val hasDisagree: Boolean
        ) : Disagree()

        data class Success(
            val postId: Long,
            val subPostId: Long?,
            val hasDisagree: Boolean
        ) : Disagree()

        data class Failure(
            val postId: Long,
            val subPostId: Long?,
            val hasDisagree: Boolean,
            val throwable: Throwable,
        ) : Disagree()

        data class AuthoritativeReject(
            val postId: Long,
            val subPostId: Long?,
            val code: String,
            val msg: String,
        ) : Disagree()
    }

    sealed class DeletePost : SubPostsPartialChange {
        override fun reduce(oldState: SubPostsUiState): SubPostsUiState = when (this) {
            is Success -> {
                if (subPostId == null) {
                    oldState
                } else {
                    oldState.copy(
                        subPosts = oldState.subPosts.filter { it.id != subPostId }
                            .toImmutableList(),
                    )
                }
            }

            is Failure -> oldState
        }

        data class Success(
            val postId: Long,
            val subPostId: Long? = null,
        ) : DeletePost()

        data class Failure(
            val errorCode: Int,
            val errorMessage: String,
        ) : DeletePost()
    }
}

@Immutable
data class SubPostItemData(
    val subPost: ImmutableHolder<SubPostList>,
    val subPostContentRenders: ImmutableList<PbContentRender>,
    val blocked: Boolean = subPost.get { shouldBlock() },
) {
    constructor(
        subPost: SubPostList,
    ) : this(
        subPost.wrapImmutable(),
        subPost.content.renders.toImmutableList(),
        subPost.shouldBlock()
    )

    val id: Long
        get() = subPost.get { id }

    val author: ImmutableHolder<User>?
        get() = subPost.get { author }?.wrapImmutable()
}

/**
 * 为楼中楼内容中的图片绑定大图浏览数据（多图可翻页）
 *
 * @param parentPost 父楼层，用于取得 from_forum / tid 等上下文，可能为空
 */
private fun bindSubPostPhotoViewData(
    renders: List<PbContentRender>,
    parentPost: Post?,
): ImmutableList<PbContentRender> {
    val pics = renders.filterIsInstance<PicContentRender>()
    if (pics.isEmpty()) return renders.toImmutableList()
    // 楼中楼图片直接用自身 URL 浏览,不走 pb 图页(该接口按楼层取图,会返回父楼层图片)
    return renders.map { render ->
        if (render is PicContentRender) {
            render.copy(
                photoViewData = getSubPostPhotoViewData(pics, pics.indexOf(render))
            )
        } else render
    }.toImmutableList()
}

data class SubPostsUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,

    val hasMore: Boolean = true,
    val currentPage: Int = 1,
    val totalPage: Int = 1,
    val totalCount: Int = 0,

    val anti: ImmutableHolder<Anti>? = null,
    val forum: ImmutableHolder<SimpleForum>? = null,
    val thread: ImmutableHolder<ThreadInfo>? = null,
    val post: ImmutableHolder<Post>? = null,
    val postContentRenders: ImmutableList<PbContentRender> = persistentListOf(),
    val subPosts: ImmutableList<SubPostItemData> = persistentListOf(),

) : UiState

sealed interface SubPostsUiEvent : UiEvent {
    data object ScrollToSubPosts : SubPostsUiEvent
}